package com.cyclingtv.app

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.webkit.*
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.cyclingtv.app.databinding.ActivityMainBinding
import com.google.android.gms.cast.framework.CastButtonFactory
import com.google.android.gms.cast.framework.CastContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 主界面：内嵌 WebView 加载 cycling.today（屏蔽广告），
 * 同时后台多源抓取直播流地址供投屏使用。
 *
 * 支持来源：
 * 1. cycling.today（主源，WebView 内嵌 + JS 注入 + 后台抓取）
 * 2. tiz-cycling-live.io（后台抓取）
 * 3. freestreams-live.mp（后台抓取）
 * 4. YouTube 搜索（后台抓取）
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    // 多源抓取结果
    private val sourceResults = mutableListOf<StreamExtractor.StreamResult>()
    // 合并后的流列表
    private val foundStreams get() = sourceResults.flatMap { sr ->
        sr.streamUrls.map { StreamInfo(it, sr.sourceName) }
    }

    // 用户启用的源（默认全部启用）
    private val enabledSources = StreamExtractor.allSources.toMutableSet()

    // 广告域名黑名单
    private val adHosts = setOf(
        "doubleclick.net", "googlesyndication.com", "adnxs.com",
        "adsrvr.org", "amazon-adsystem.com", "bidswitch.net",
        "criteo.com", "outbrain.com", "taboola.com", "adform.net",
        "pubmatic.com", "openx.net", "rubiconproject.com",
        "smartadserver.com", "33across.com", "contextweb.com",
        "lijit.com", "sovrn.com", "spotxchange.com", "springserve.com"
    )

    // WebView 即时拦截的流（与多源后端抓取互补）
    private val webViewStreams = mutableListOf<StreamInfo>()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        // 初始化 Cast
        try { CastContext.getSharedInstance(this) } catch (_: Exception) {}

        setupWebView()
        setupButtons()

        // 加载主页
        binding.webView.loadUrl("https://cycling.today/")
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val settings = binding.webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        settings.userAgentString = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36"

        binding.webView.webViewClient = object : WebViewClient() {

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest
            ): WebResourceResponse? {
                val url = request.url.toString()
                val host = request.url.host ?: ""

                // 1. 拦截广告域名
                if (adHosts.any { host.endsWith(it) }) {
                    return WebResourceResponse("text/plain", "utf-8", null)
                }

                // 2. 检测是否为直播流 URL，记录下来
                if (isStreamUrl(url)) {
                    val info = StreamInfo(url, "WebView拦截")
                    runOnUiThread {
                        if (webViewStreams.none { it.url == url }) {
                            webViewStreams.add(info)
                            updateStreamBadge()
                        }
                    }
                }

                return super.shouldInterceptRequest(view, request)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                // 注入 JS 提取页面内 video/source/iframe src
                view?.evaluateJavascript(JS_EXTRACT_STREAMS) { result ->
                    if (result != null && result != "null" && result != "\"\"") {
                        val clean = result.trim('"').replace("\\n", "\n").replace("\\\"", "\"")
                        clean.split("\n").filter { it.isNotBlank() }.forEach { u ->
                            val info = StreamInfo(u.trim(), "JS提取")
                            if (webViewStreams.none { it.url == u.trim() }) {
                                webViewStreams.add(info)
                            }
                        }
                        updateStreamBadge()
                    }
                }
                binding.swipeRefresh.isRefreshing = false
            }
        }

        binding.webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                binding.progressBar.progress = newProgress
                binding.progressBar.visibility =
                    if (newProgress < 100) android.view.View.VISIBLE else android.view.View.GONE
            }
        }

        // 下拉刷新
        binding.swipeRefresh.setOnRefreshListener {
            sourceResults.clear()
            webViewStreams.clear()
            updateStreamBadge()
            binding.webView.reload()
            // 同时后台多源抓取
            fetchStreamsInBackground()
        }
    }

    private fun setupButtons() {
        // 手动多源抓流按钮
        binding.btnFetch.setOnClickListener {
            sourceResults.clear()
            webViewStreams.clear()
            updateStreamBadge()
            // 弹出源选择器
            showSourcePicker()
        }

        // 显示已发现的流列表
        binding.btnStreams.setOnClickListener {
            showStreamsDialog()
        }
    }

    // ─── 源选择器 ────────────────────────────────────────────────────────────

    private fun showSourcePicker() {
        val sources = StreamExtractor.allSources
        val names = sources.map { it.name }.toTypedArray()
        val checked = BooleanArray(sources.size) { sources[it] in enabledSources }

        AlertDialog.Builder(this)
            .setTitle("选择抓取来源")
            .setMultiChoiceItems(names, checked) { _, which, isChecked ->
                val src = sources[which]
                if (isChecked) enabledSources.add(src) else enabledSources.remove(src)
            }
            .setPositiveButton("开始抓取") { _, _ ->
                if (enabledSources.isEmpty()) {
                    Toast.makeText(this, "请至少选择一个来源", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                Toast.makeText(this, "正在多源抓取直播流...", Toast.LENGTH_SHORT).show()
                fetchStreamsInBackground()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ─── 多源后台抓取 ────────────────────────────────────────────────────────

    private fun fetchStreamsInBackground() {
        binding.progressBar.visibility = android.view.View.VISIBLE
        lifecycleScope.launch(Dispatchers.IO) {
            val tmpResults = mutableListOf<StreamExtractor.StreamResult>()

            // 对每个启用的源做后台抓取（cycling.today 已由 WebView 覆盖，但也做一遍）
            enabledSources.forEach { src ->
                val urls = StreamExtractor.scrapeSource(src)
                if (urls.isNotEmpty()) {
                    tmpResults.add(StreamExtractor.StreamResult(src.name, urls.distinct()))
                }
            }

            withContext(Dispatchers.Main) {
                binding.progressBar.visibility = android.view.View.GONE
                sourceResults.clear()
                sourceResults.addAll(tmpResults)

                val totalStreams = foundStreams.size

                if (totalStreams == 0) {
                    Toast.makeText(
                        this@MainActivity,
                        "当前未检测到直播流\n（比赛时段才会有，可尝试切换来源）",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    val sourceNames = tmpResults.joinToString("、") { it.sourceName }
                    Toast.makeText(
                        this@MainActivity,
                        "✓ 从 $sourceNames\n共发现 $totalStreams 条流地址，点击「流列表」查看",
                        Toast.LENGTH_LONG
                    ).show()
                }
                updateStreamBadge()
            }
        }
    }

    private fun updateStreamBadge() {
        val count = foundStreams.size
        binding.btnStreams.text = if (count > 0) "流列表 ($count)" else "流列表"
    }

    // ─── 流列表弹窗（按来源分组）─────────────────────────────────────────────

    private fun showStreamsDialog() {
        // 合并 WebView 即时拦截的流
        val allStreams = foundStreams.toMutableList()
        webViewStreams.forEach { ws ->
            if (allStreams.none { it.url == ws.url }) {
                allStreams.add(ws)
            }
        }

        if (allStreams.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("📡 直播流")
                .setMessage("当前没有发现直播流。\n\n• 比赛时段请点击「抓取」按钮\n• 可尝试切换来源（tiz/freestreams/YouTube）")
                .setPositiveButton("去抓取") { _, _ -> showSourcePicker() }
                .setNegativeButton("关闭", null)
                .show()
            return
        }

        // 流列表分组展示
        val listView = android.widget.ListView(this).apply {
            adapter = android.widget.ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_list_item_2,
                android.R.id.text1,
                allStreams.map { "${it.source} | ${truncate(it.url, 55)}" }
            )
        }

        AlertDialog.Builder(this)
            .setTitle("📡 共 ${allStreams.size} 条直播流")
            .setView(listView)
            .setPositiveButton("管理来源") { _, _ -> showSourcePicker() }
            .setNegativeButton("关闭", null)
            .setOnDismissListener { updateStreamBadge() }
            .create().also { dialog ->
                listView.setOnItemClickListener { _, _, position, _ ->
                    dialog.dismiss()
                    showStreamOptions(allStreams[position])
                }
                dialog.show()
            }
    }

    private fun showStreamOptions(stream: StreamInfo) {
        val options = arrayOf("▶️ 本机播放", "📺 投屏到电视", "📋 复制链接")
        AlertDialog.Builder(this)
            .setTitle("选择操作")
            .setMessage(truncate(stream.url, 80))
            .setItems(options) { _, which ->
                when (which) {
                    0 -> openPlayer(stream.url, false)
                    1 -> openPlayer(stream.url, true)
                    2 -> copyToClipboard(stream.url)
                }
            }
            .show()
    }

    private fun openPlayer(url: String, castMode: Boolean) {
        val intent = Intent(this, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_STREAM_URL, url)
            putExtra(PlayerActivity.EXTRA_CAST_MODE, castMode)
        }
        startActivity(intent)
    }

    private fun copyToClipboard(text: String) {
        val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("stream_url", text))
        Toast.makeText(this, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
    }

    private fun isStreamUrl(url: String): Boolean {
        return url.contains(".m3u8") || url.contains(".mpd") ||
                url.startsWith("rtmp") || url.contains("/hls/") ||
                url.contains("/live/") || url.contains("/stream/")
    }

    private fun truncate(s: String, max: Int) = if (s.length <= max) s else s.take(max) + "..."

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        val castItem = menu.findItem(R.id.media_route_menu_item)
        CastButtonFactory.setUpMediaRouteButton(applicationContext, menu, R.id.media_route_menu_item)
        return true
    }

    override fun onBackPressed() {
        if (binding.webView.canGoBack()) binding.webView.goBack()
        else super.onBackPressed()
    }

    companion object {
        // 注入 JS：提取页面里所有 video/source/iframe 的 src
        private const val JS_EXTRACT_STREAMS = """
            (function() {
                var urls = [];
                document.querySelectorAll('video, video source, source').forEach(function(el) {
                    ['src','data-src','data-url'].forEach(function(a) {
                        var v = el.getAttribute(a);
                        if (v && v.trim()) urls.push(v.trim());
                    });
                });
                document.querySelectorAll('iframe').forEach(function(el) {
                    ['src','data-src'].forEach(function(a) {
                        var v = el.getAttribute(a);
                        if (v && v.startsWith('http')) urls.push(v.trim());
                    });
                });
                return urls.join('\n');
            })();
        """
    }
}

data class StreamInfo(val url: String, val source: String)
