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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 主界面：内嵌 WebView 加载 cycling.today（屏蔽广告），
 * 同时后台多源抓取直播流地址供投屏使用。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val sourceResults = mutableListOf<StreamExtractor.StreamResult>()
    private val foundStreams get() = sourceResults.flatMap { sr ->
        sr.streamUrls.map { StreamInfo(it, sr.sourceName) }
    }

    private val enabledSources = StreamExtractor.allSources.toMutableSet()
    private val webViewStreams = mutableListOf<StreamInfo>()

    private val adHosts = setOf(
        "doubleclick.net", "googlesyndication.com", "adnxs.com",
        "adsrvr.org", "amazon-adsystem.com", "bidswitch.net",
        "criteo.com", "outbrain.com", "taboola.com", "adform.net",
        "pubmatic.com", "openx.net", "rubiconproject.com",
        "smartadserver.com", "33across.com", "contextweb.com",
        "lijit.com", "sovrn.com", "spotxchange.com", "springserve.com"
    )

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        setupWebView()
        setupButtons()

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
                view: WebView?, request: WebResourceRequest
            ): WebResourceResponse? {
                val host = request.url.host ?: ""
                if (adHosts.any { host.endsWith(it) }) {
                    return WebResourceResponse("text/plain", "utf-8", null)
                }
                val url = request.url.toString()
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

        binding.swipeRefresh.setOnRefreshListener {
            sourceResults.clear()
            webViewStreams.clear()
            updateStreamBadge()
            binding.webView.reload()
            fetchStreamsInBackground()
        }
    }

    private fun setupButtons() {
        binding.btnFetch.setOnClickListener {
            sourceResults.clear()
            webViewStreams.clear()
            updateStreamBadge()
            showSourcePicker()
        }
        binding.btnStreams.setOnClickListener { showStreamsDialog() }
    }

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

    private fun fetchStreamsInBackground() {
        binding.progressBar.visibility = android.view.View.VISIBLE
        lifecycleScope.launch(Dispatchers.IO) {
            val tmpResults = mutableListOf<StreamExtractor.StreamResult>()
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
                    Toast.makeText(this@MainActivity, "当前未检测到直播流\n（比赛时段才会有，可尝试切换来源）", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this@MainActivity, "✓ 发现 $totalStreams 条流地址", Toast.LENGTH_LONG).show()
                }
                updateStreamBadge()
            }
        }
    }

    private fun updateStreamBadge() {
        val count = foundStreams.size
        binding.btnStreams.text = if (count > 0) "流列表 ($count)" else "流列表"
    }

    private fun showStreamsDialog() {
        val allStreams = foundStreams.toMutableList()
        webViewStreams.forEach { ws ->
            if (allStreams.none { it.url == ws.url }) allStreams.add(ws)
        }
        if (allStreams.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("📡 直播流")
                .setMessage("当前没有发现直播流。\n\n• 比赛时段请点击「抓取」按钮\n• 可尝试切换来源")
                .setPositiveButton("去抓取") { _, _ -> showSourcePicker() }
                .setNegativeButton("关闭", null).show()
            return
        }
        val listView = android.widget.ListView(this).apply {
            adapter = android.widget.ArrayAdapter(this@MainActivity, android.R.layout.simple_list_item_2, android.R.id.text1,
                allStreams.map { "${it.source} | ${truncate(it.url, 55)}" })
        }
        AlertDialog.Builder(this)
            .setTitle("📡 共 ${allStreams.size} 条直播流")
            .setView(listView)
            .setPositiveButton("管理来源") { _, _ -> showSourcePicker() }
            .setNegativeButton("关闭", null).setOnDismissListener { updateStreamBadge() }
            .create().also { d ->
                listView.setOnItemClickListener { _, _, pos, _ ->
                    d.dismiss()
                    showStreamOptions(allStreams[pos])
                }
                d.show()
            }
    }

    private fun showStreamOptions(stream: StreamInfo) {
        AlertDialog.Builder(this)
            .setTitle("选择操作")
            .setMessage(truncate(stream.url, 80))
            .setItems(arrayOf("▶️ 本机播放", "📺 投屏到电视", "📋 复制链接")) { _, which ->
                when (which) {
                    0 -> openPlayer(stream.url, false)
                    1 -> openPlayer(stream.url, true)
                    2 -> copyToClipboard(stream.url)
                }
            }.show()
    }

    private fun openPlayer(url: String, castMode: Boolean) {
        startActivity(Intent(this, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_STREAM_URL, url)
            putExtra(PlayerActivity.EXTRA_CAST_MODE, castMode)
        })
    }

    private fun copyToClipboard(text: String) {
        val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("stream_url", text))
        Toast.makeText(this, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
    }

    private fun isStreamUrl(url: String) =
        url.contains(".m3u8") || url.contains(".mpd") || url.startsWith("rtmp") ||
        url.contains("/hls/") || url.contains("/live/") || url.contains("/stream/")

    private fun truncate(s: String, max: Int) = if (s.length <= max) s else s.take(max) + "..."

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onBackPressed() {
        if (binding.webView.canGoBack()) binding.webView.goBack() else super.onBackPressed()
    }

    companion object {
        private const val JS_EXTRACT_STREAMS = """
            (function() { var urls = [];
            document.querySelectorAll('video, video source, source').forEach(function(el) {
                ['src','data-src','data-url'].forEach(function(a){
                    var v=el.getAttribute(a); if(v && v.trim()) urls.push(v.trim()); }); });
            document.querySelectorAll('iframe').forEach(function(el) {
                ['src','data-src'].forEach(function(a){
                    var v=el.getAttribute(a); if(v && v.startsWith('http')) urls.push(v.trim()); }); });
            return urls.join('\n'); })();
        """
    }
}

data class StreamInfo(val url: String, val source: String)
