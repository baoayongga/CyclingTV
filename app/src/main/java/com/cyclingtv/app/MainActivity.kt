package com.cyclingtv.app

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import android.webkit.*
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.cyclingtv.app.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 主界面 v2 — 多源直播流抓取
 *
 * 功能：
 * - 内嵌 WebView 加载 cycling.today（广告已屏蔽）
 * - 点击「多源抓取」并发请求 6 个源
 * - 各源独立状态展示（成功/失败/空/抓取中）
 * - 发现流后可直接播放或投屏
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    // 源状态
    private val sourceStatuses = StreamExtractor.allSources.map {
        StreamExtractor.SourceStatus(it)
    }.toMutableList()

    // 已发现的流（WebView 拦截 + 后台抓取）
    private val webViewStreams = mutableListOf<StreamInfo>()

    private val allFoundStreams: List<StreamInfo>
        get() {
            val list = webViewStreams.toMutableList()
            sourceStatuses.filter { it.state == StreamExtractor.SourceStatus.State.SUCCESS }.forEach { st ->
                // 这里需要缓存抓取结果
            }
            return list
        }

    // 缓存抓取结果（按源名索引）
    private val scrapeCache = mutableMapOf<String, List<String>>()

    private val adHosts = setOf(
        "doubleclick.net", "googlesyndication.com", "adnxs.com",
        "adsrvr.org", "amazon-adsystem.com", "bidswitch.net",
        "criteo.com", "outbrain.com", "taboola.com", "adform.net",
        "pubmatic.com", "openx.net", "rubiconproject.com",
        "smartadserver.com", "33across.com", "contextweb.com",
        "lijit.com", "sovrn.com", "spotxchange.com", "springserve.com"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        setupWebView()
        setupButtons()

        binding.webView.loadUrl("https://cycling.today/")

        // 启动后自动多源抓取
        binding.webView.postDelayed({ fetchAllSources() }, 1500)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // WebView
    // ═══════════════════════════════════════════════════════════════════════════

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val settings = binding.webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        settings.userAgentString = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36"

        binding.webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest): WebResourceResponse? {
                val host = request.url.host ?: ""
                if (adHosts.any { host.endsWith(it) }) {
                    return WebResourceResponse("text/plain", "utf-8", null)
                }
                val url = request.url.toString()
                if (isStreamUrl(url)) {
                    runOnUiThread {
                        if (webViewStreams.none { it.url == url }) {
                            webViewStreams.add(StreamInfo(url, "WebView拦截"))
                            updateBadge()
                        }
                    }
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                // JS 提取流地址
                view?.evaluateJavascript(JS_EXTRACT) { result ->
                    if (result != null && result != "null" && result != "\"\"") {
                        val clean = result.trim('"').replace("\\n", "\n").replace("\\\"", "\"")
                        clean.split("\n").filter { it.isNotBlank() }.forEach { u ->
                            val trimmed = u.trim()
                            if (webViewStreams.none { it.url == trimmed }) {
                                webViewStreams.add(StreamInfo(trimmed, "JS提取"))
                            }
                        }
                        updateBadge()
                    }
                }
                binding.swipeRefresh.isRefreshing = false
            }
        }

        binding.webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                binding.progressBar.progress = newProgress
                binding.progressBar.visibility =
                    if (newProgress < 100) View.VISIBLE else View.GONE
            }
        }

        binding.swipeRefresh.setOnRefreshListener {
            webViewStreams.clear()
            scrapeCache.clear()
            sourceStatuses.forEach { it.state = StreamExtractor.SourceStatus.State.IDLE; it.streamCount = 0 }
            updateBadge()
            binding.webView.reload()
            fetchAllSources()
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 按钮
    // ═══════════════════════════════════════════════════════════════════════════

    private fun setupButtons() {
        binding.btnFetch.setOnClickListener {
            webViewStreams.clear()
            scrapeCache.clear()
            sourceStatuses.forEach { it.state = StreamExtractor.SourceStatus.State.IDLE; it.streamCount = 0 }
            updateBadge()
            fetchAllSources()
        }
        binding.btnStreams.setOnClickListener { showStreamsDialog() }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 多源并发抓取
    // ═══════════════════════════════════════════════════════════════════════════

    private fun fetchAllSources() {
        Toast.makeText(this, "正在多源抓取直播流…", Toast.LENGTH_SHORT).show()
        binding.progressBar.visibility = View.VISIBLE
        binding.progressBar.isIndeterminate = true

        lifecycleScope.launch(Dispatchers.IO) {
            // 并发抓取所有源（async + awaitAll）
            sourceStatuses.map { status ->
                async {
                    val urls = StreamExtractor.scrapeSource(status.source, status)
                    if (urls.isNotEmpty()) {
                        scrapeCache[status.source.name] = urls
                    }
                    withContext(Dispatchers.Main) { updateBadge() }
                }
            }.awaitAll()

            withContext(Dispatchers.Main) {
                binding.progressBar.isIndeterminate = false
                binding.progressBar.visibility = View.GONE

                // 统计
                val total = sourceStatuses.sumOf { it.streamCount }
                val successCount = sourceStatuses.count { it.state == StreamExtractor.SourceStatus.State.SUCCESS }
                val failCount = sourceStatuses.count { it.state == StreamExtractor.SourceStatus.State.ERROR }

                if (total == 0) {
                    buildSourceStatusDialog().show()
                } else {
                    Toast.makeText(
                        this@MainActivity,
                        "✓ $successCount 个源成功，共 $total 条流（$failCount 个失败）",
                        Toast.LENGTH_LONG
                    ).show()
                    // 自动弹流列表
                    showStreamsDialog()
                }

                updateBadge()
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // UI 更新
    // ═══════════════════════════════════════════════════════════════════════════

    private fun updateBadge() {
        val total = sourceStatuses.sumOf { it.streamCount } + webViewStreams.size
        binding.btnStreams.text = if (total > 0) "📡 流列表 ($total)" else "📡 流列表"

        // 更新源状态指示
        val dotMap = mapOf(
            StreamExtractor.SourceStatus.State.SUCCESS to "\uD83D\uDFE2",  // 🟢
            StreamExtractor.SourceStatus.State.EMPTY   to "\uD83D\uDFE1",  // 🟡
            StreamExtractor.SourceStatus.State.ERROR   to "\uD83D\uDD34",  // 🔴
            StreamExtractor.SourceStatus.State.FETCHING to "\uD83D\uDD35",  // 🔵
            StreamExtractor.SourceStatus.State.IDLE    to "⚪"
        )

        val statusText = sourceStatuses.joinToString("  ") { st ->
            "${dotMap[st.state] ?: "⚪"}${st.source.name}"
        }
        binding.tvSourceStatus.text = statusText
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 流列表对话框
    // ═══════════════════════════════════════════════════════════════════════════

    private fun showStreamsDialog() {
        // 汇总所有流
        val allStreams = mutableListOf<StreamInfo>()

        // WebView 截获
        webViewStreams.forEach { ws ->
            if (allStreams.none { it.url == ws.url }) allStreams.add(ws)
        }

        // 各源抓取
        scrapeCache.forEach { (sourceName, urls) ->
            urls.forEach { url ->
                if (allStreams.none { it.url == url }) {
                    allStreams.add(StreamInfo(url, sourceName))
                }
            }
        }

        if (allStreams.isEmpty()) {
            buildSourceStatusDialog().show()
            return
        }

        // 分组适配器
        val adapter = StreamAdapter(allStreams)

        val listView = ListView(this).apply {
            adapter = adapter
            divider = null
            dividerHeight = 0
            setPadding(0, 8, 0, 8)
        }

        AlertDialog.Builder(this, R.style.CyclingDialog)
            .setTitle("📡 共 ${allStreams.size} 条直播流")
            .setView(listView)
            .setPositiveButton("重新抓取") { _, _ ->
                webViewStreams.clear()
                scrapeCache.clear()
                sourceStatuses.forEach { it.state = StreamExtractor.SourceStatus.State.IDLE; it.streamCount = 0 }
                updateBadge()
                fetchAllSources()
            }
            .setNeutralButton("源状态") { _, _ -> buildSourceStatusDialog().show() }
            .setNegativeButton("关闭", null)
            .create().also { d ->
                listView.setOnItemClickListener { _, _, pos, _ ->
                    d.dismiss()
                    showStreamActions(allStreams[pos])
                }
                d.show()
            }
    }

    private fun buildSourceStatusDialog(): AlertDialog {
        val sb = StringBuilder()
        sourceStatuses.forEach { st ->
            val icon = when (st.state) {
                StreamExtractor.SourceStatus.State.SUCCESS  -> "\uD83D\uDFE2"
                StreamExtractor.SourceStatus.State.EMPTY    -> "\uD83D\uDFE1"
                StreamExtractor.SourceStatus.State.ERROR    -> "\uD83D\uDD34"
                StreamExtractor.SourceStatus.State.FETCHING -> "\uD83D\uDD35"
                StreamExtractor.SourceStatus.State.IDLE     -> "⚪"
            }
            val detail = when (st.state) {
                StreamExtractor.SourceStatus.State.SUCCESS -> "${st.streamCount} 条流"
                StreamExtractor.SourceStatus.State.EMPTY   -> st.errorMsg.ifBlank { "无直播信号" }
                StreamExtractor.SourceStatus.State.ERROR   -> st.errorMsg.ifBlank { "抓取失败" }
                else -> "..."
            }
            sb.append("$icon  ${st.source.name}\n      $detail\n\n")
        }

        return AlertDialog.Builder(this, R.style.CyclingDialog)
            .setTitle("各源状态")
            .setMessage(sb.toString().trim())
            .setPositiveButton("重新抓取") { _, _ ->
                webViewStreams.clear()
                scrapeCache.clear()
                sourceStatuses.forEach { it.state = StreamExtractor.SourceStatus.State.IDLE; it.streamCount = 0 }
                updateBadge()
                fetchAllSources()
            }
            .setNegativeButton("关闭", null)
            .create()
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 流操作
    // ═══════════════════════════════════════════════════════════════════════════

    private fun showStreamActions(stream: StreamInfo) {
        AlertDialog.Builder(this, R.style.CyclingDialog)
            .setTitle("选择操作")
            .setMessage("来源: ${stream.source}\n${truncateUrl(stream.url, 60)}")
            .setItems(arrayOf("▶️  本机播放", "📺  投屏到电视", "📋  复制链接")) { _, idx ->
                when (idx) {
                    0 -> openPlayer(stream.url, false)
                    1 -> openPlayer(stream.url, true)
                    2 -> {
                        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("stream_url", stream.url))
                        Toast.makeText(this, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
                    }
                }
            }.show()
    }

    private fun openPlayer(url: String, castMode: Boolean) {
        startActivity(Intent(this, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_STREAM_URL, url)
            putExtra(PlayerActivity.EXTRA_CAST_MODE, castMode)
        })
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 工具
    // ═══════════════════════════════════════════════════════════════════════════

    private fun isStreamUrl(url: String) =
        url.contains(".m3u8") || url.contains(".mpd") || url.startsWith("rtmp") ||
        url.contains("/hls/") || url.contains("/live/") || url.contains("/stream/")

    private fun truncateUrl(s: String, max: Int): String {
        // 去掉 token 参数再截断
        val clean = s.replace(Regex("[?&](s|e|token|key)=[^&]*"), "?***")
        return if (clean.length <= max) clean else clean.take(max) + "..."
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onBackPressed() {
        if (binding.webView.canGoBack()) binding.webView.goBack() else super.onBackPressed()
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 内部类型
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * 流列表适配器 — 按来源分组显示
     */
    private inner class StreamAdapter(private val items: List<StreamInfo>) : BaseAdapter() {

        override fun getCount() = items.size

        override fun getItem(pos: Int) = items[pos]

        override fun getItemId(pos: Int) = pos.toLong()

        override fun getView(pos: Int, convertView: View?, parent: ViewGroup?): View {
            val view = convertView ?: TextView(this@MainActivity).apply {
                setPadding(48, 18, 48, 18)
                setTextSize(13f)
                setTextColor(Color.parseColor("#E0E0E0"))
                setLineSpacing(4f, 1.2f)
            }
            val item = items[pos]
            val display = "[${item.source}] ${truncateUrl(item.url, 50)}"

            val ss = SpannableString(display)
            // 来源名加粗 + 颜色
            val srcStart = display.indexOf('[')
            val srcEnd = display.indexOf(']') + 1
            if (srcStart >= 0 && srcEnd > srcStart) {
                ss.setSpan(StyleSpan(Typeface.BOLD), srcStart, srcEnd, 0)
                ss.setSpan(ForegroundColorSpan(Color.parseColor("#E94560")), srcStart, srcEnd, 0)
            }
            (view as TextView).text = ss
            return view
        }
    }

    companion object {
        private const val JS_EXTRACT = """
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
