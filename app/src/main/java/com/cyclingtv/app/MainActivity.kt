package com.cyclingtv.app

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.Menu
import android.view.MenuItem
import android.webkit.*
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.cyclingtv.app.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val sourceResults = mutableListOf<StreamExtractor.StreamResult>()
    private val webViewStreams = mutableListOf<StreamInfo>()
    private val manualStreams = mutableListOf<StreamInfo>()

    private val allFoundStreams get(): List<StreamInfo> {
        val all = mutableListOf<StreamInfo>()
        all.addAll(manualStreams)
        all.addAll(webViewStreams)
        sourceResults.forEach { sr -> sr.streamUrls.forEach { all.add(StreamInfo(it, sr.sourceName)) } }
        return all.distinctBy { it.url }
    }

    private val enabledSources = StreamExtractor.allSources.toMutableSet()

    private val adHosts = setOf(
        "doubleclick.net", "googlesyndication.com", "adnxs.com",
        "adsrvr.org", "amazon-adsystem.com", "bidswitch.net",
        "criteo.com", "outbrain.com", "taboola.com", "adform.net",
        "pubmatic.com", "openx.net", "rubiconproject.com",
        "smartadserver.com", "springserve.com"
    )

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = "🚴 Cycling TV"

        setupWebView()
        setupButtons()

        // 默认加载 cyclingstream.com
        binding.webView.loadUrl("https://cyclingstream.com/live-stream-2/")
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        with(binding.webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            userAgentString = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/124.0.0.0 Mobile Safari/537.36"
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
        }

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
                            updateStreamBadge()
                        }
                    }
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                binding.progressBar.visibility = android.view.View.GONE
                binding.swipeRefresh.isRefreshing = false
                // JS 提取页面里的流地址
                view?.evaluateJavascript(JS_EXTRACT) { result ->
                    if (!result.isNullOrBlank() && result != "null" && result != "\"\"") {
                        val clean = result.trim('"').replace("\\n", "\n").replace("\\\"", "\"")
                        clean.split("\n").filter { it.isNotBlank() }.forEach { u ->
                            val trimmed = u.trim()
                            if (webViewStreams.none { it.url == trimmed }) {
                                webViewStreams.add(StreamInfo(trimmed, "JS提取"))
                            }
                        }
                        updateStreamBadge()
                    }
                }
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
            webViewStreams.clear()
            updateStreamBadge()
            binding.webView.reload()
        }
    }

    private fun setupButtons() {
        // 抓取按钮：弹出来源选择器
        binding.btnFetch.setOnClickListener { showSourcePicker() }
        // 流列表按钮
        binding.btnStreams.setOnClickListener { showStreamsDialog() }
        // 手动输入按钮
        binding.btnManual.setOnClickListener { showManualInputDialog() }
    }

    // ─── 手动输入流地址 ────────────────────────────────────────────────────

    private fun showManualInputDialog() {
        val et = EditText(this).apply {
            hint = "粘贴 m3u8 / rtmp 地址"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setPadding(40, 20, 40, 20)
        }
        AlertDialog.Builder(this)
            .setTitle("✏️ 手动输入直播地址")
            .setMessage("从浏览器复制 m3u8 或 rtmp 地址后粘贴到这里")
            .setView(et)
            .setPositiveButton("播放") { _, _ ->
                val url = et.text.toString().trim()
                if (url.isBlank()) {
                    Toast.makeText(this, "地址不能为空", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                manualStreams.removeAll { it.url == url }
                manualStreams.add(0, StreamInfo(url, "手动输入"))
                updateStreamBadge()
                showStreamOptions(StreamInfo(url, "手动输入"))
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ─── 来源选择器 ────────────────────────────────────────────────────────

    private fun showSourcePicker() {
        val sources = StreamExtractor.allSources
        val names = sources.map { "${it.name}\n${it.description}" }.toTypedArray()
        val checked = BooleanArray(sources.size) { sources[it] in enabledSources }
        AlertDialog.Builder(this)
            .setTitle("选择抓取来源")
            .setMultiChoiceItems(names, checked) { _, which, isChecked ->
                if (isChecked) enabledSources.add(sources[which])
                else enabledSources.remove(sources[which])
            }
            .setPositiveButton("开始抓取") { _, _ ->
                if (enabledSources.isEmpty()) {
                    Toast.makeText(this, "请至少选择一个来源", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                sourceResults.clear()
                updateStreamBadge()
                Toast.makeText(this, "正在后台抓取...", Toast.LENGTH_SHORT).show()
                fetchStreamsInBackground()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun fetchStreamsInBackground() {
        binding.progressBar.visibility = android.view.View.VISIBLE
        lifecycleScope.launch(Dispatchers.IO) {
            val tmp = mutableListOf<StreamExtractor.StreamResult>()
            enabledSources.forEach { src ->
                val urls = StreamExtractor.scrapeSource(src)
                if (urls.isNotEmpty()) {
                    tmp.add(StreamExtractor.StreamResult(src.name, urls.distinct()))
                }
            }
            withContext(Dispatchers.Main) {
                binding.progressBar.visibility = android.view.View.GONE
                sourceResults.addAll(tmp)
                val total = allFoundStreams.size
                if (total == 0) {
                    Toast.makeText(this@MainActivity,
                        "未抓到直播流（比赛才有直播，可手动输入地址）", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this@MainActivity, "✓ 发现 $total 条流", Toast.LENGTH_LONG).show()
                }
                updateStreamBadge()
            }
        }
    }

    // ─── 流列表 ────────────────────────────────────────────────────────────

    private fun updateStreamBadge() {
        val count = allFoundStreams.size
        binding.btnStreams.text = if (count > 0) "📡 ($count)" else "📡"
    }

    private fun showStreamsDialog() {
        val streams = allFoundStreams
        if (streams.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("📡 直播流")
                .setMessage("暂无直播流\n\n• 点「抓取」自动从多个网站抓取\n• 点「手动」自己粘贴 m3u8 地址\n• 比赛时段才有实时直播")
                .setPositiveButton("去抓取") { _, _ -> showSourcePicker() }
                .setNeutralButton("手动输入") { _, _ -> showManualInputDialog() }
                .setNegativeButton("关闭", null).show()
            return
        }
        val items = streams.map { "[${it.source}] ${it.url.takeLast(50)}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("📡 共 ${streams.size} 条直播流")
            .setItems(items) { _, pos -> showStreamOptions(streams[pos]) }
            .setPositiveButton("抓取更多") { _, _ -> showSourcePicker() }
            .setNeutralButton("手动输入") { _, _ -> showManualInputDialog() }
            .setNegativeButton("关闭", null).show()
    }

    private fun showStreamOptions(stream: StreamInfo) {
        AlertDialog.Builder(this)
            .setTitle("选择操作")
            .setMessage(stream.url.take(80))
            .setItems(arrayOf("▶️ 本机播放", "📺 投屏到电视", "📋 复制地址")) { _, which ->
                when (which) {
                    0 -> openPlayer(stream.url, false)
                    1 -> openPlayer(stream.url, true)
                    2 -> {
                        val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("url", stream.url))
                        Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show()
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

    // ─── 菜单（网站切换） ──────────────────────────────────────────────────

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, "cyclingstream.com")
        menu.add(0, 2, 1, "tiz-cycling.tv")
        menu.add(0, 3, 2, "cyclingtiz.live")
        menu.add(0, 4, 3, "cycling.today")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val url = when (item.itemId) {
            1 -> "https://cyclingstream.com/live-stream-2/"
            2 -> "https://tiz-cycling.tv/main/"
            3 -> "https://cyclingtiz.live/"
            4 -> "https://cycling.today/"
            else -> return super.onOptionsItemSelected(item)
        }
        webViewStreams.clear()
        updateStreamBadge()
        binding.webView.loadUrl(url)
        return true
    }

    override fun onBackPressed() {
        if (binding.webView.canGoBack()) binding.webView.goBack() else super.onBackPressed()
    }

    private fun isStreamUrl(url: String) =
        url.contains(".m3u8") || url.contains(".mpd") || url.startsWith("rtmp") ||
        url.contains("/hls/")

    companion object {
        private const val JS_EXTRACT = """
            (function(){
                var urls=[];
                document.querySelectorAll('video,source').forEach(function(el){
                    ['src','data-src','data-url','data-stream','data-file'].forEach(function(a){
                        var v=el.getAttribute(a);
                        if(v&&v.trim().length>5) urls.push(v.trim());
                    });
                });
                document.querySelectorAll('[data-file],[data-src],[data-stream],[data-hls]').forEach(function(el){
                    ['data-file','data-src','data-stream','data-hls'].forEach(function(a){
                        var v=el.getAttribute(a);
                        if(v&&(v.indexOf('.m3u8')>=0||v.indexOf('rtmp')===0)) urls.push(v.trim());
                    });
                });
                try{
                    var scripts=document.querySelectorAll('script:not([src])');
                    scripts.forEach(function(s){
                        var m=s.textContent.match(/["'](https?:\/\/[^"']*\.m3u8[^"']*)["']/gi);
                        if(m) m.forEach(function(x){ urls.push(x.replace(/["']/g,'')); });
                    });
                }catch(e){}
                return urls.join('\n');
            })();
        """
    }
}

data class StreamInfo(val url: String, val source: String)
