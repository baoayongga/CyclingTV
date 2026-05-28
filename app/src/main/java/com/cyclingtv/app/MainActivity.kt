package com.cyclingtv.app

import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.webkit.*
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.cyclingtv.app.databinding.ActivityMainBinding
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 主界面：WebView 浏览 cycling.today + 底部自动显示直播源
 * 点击源名称 → 手机播放 / 投屏到电视
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    // source name → list of stream URLs
    private val sourceStreams = mutableMapOf<String, List<String>>()

    // 自动抓取的源（不含 YouTube）
    private val autoSources = listOf(
        StreamExtractor.SourceInfo("Cycling Today", "https://cycling.today/"),
        StreamExtractor.SourceInfo("CyclingTIZ", "https://cyclingtiz.live/")
    )

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
        binding.webView.loadUrl("https://cycling.today/")
        autoFetch()
    }

    // ─── WebView ────────────────────────────────────────────────────────────

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val settings = binding.webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        settings.userAgentString =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36"

        binding.webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?, request: WebResourceRequest
            ): WebResourceResponse? {
                val host = request.url.host ?: ""
                if (adHosts.any { host.endsWith(it) }) {
                    return WebResourceResponse("text/plain", "utf-8", null)
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
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
            binding.webView.reload()
            autoFetch()
        }
    }

    // ─── 自动抓取 ──────────────────────────────────────────────────────────

    private fun autoFetch() {
        binding.sourcePanel.visibility = View.VISIBLE
        binding.sourceTitle.text = "正在搜索直播源..."
        binding.sourceLoading.visibility = View.VISIBLE
        binding.sourceCards.removeAllViews()

        lifecycleScope.launch(Dispatchers.IO) {
            sourceStreams.clear()
            autoSources.forEach { src ->
                val urls = StreamExtractor.scrapeSource(src)
                if (urls.isNotEmpty()) {
                    sourceStreams[src.name] = urls.distinct()
                }
            }
            withContext(Dispatchers.Main) {
                updateSourceCards()
            }
        }
    }

    // ─── 底部源卡片 ────────────────────────────────────────────────────────

    private fun updateSourceCards() {
        binding.sourceLoading.visibility = View.GONE
        binding.sourceCards.removeAllViews()

        if (sourceStreams.isEmpty()) {
            binding.sourceTitle.text = "📡 暂未发现直播源（下拉刷新重试）"
            return
        }

        binding.sourceTitle.text = "📡 发现 ${sourceStreams.size} 个源，点击播放 →"

        sourceStreams.forEach { (name, _) ->
            val chip = MaterialButton(this).apply {
                text = name
                textSize = 14f
                setTextColor(Color.WHITE)
                backgroundTintList = ColorStateList.valueOf(Color.parseColor("#E94560"))
                cornerRadius = 40
                isAllCaps = false
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    dp(44)
                ).apply {
                    marginEnd = dp(12)
                }
                setPadding(dp(20), 0, dp(20), 0)
                setOnClickListener { showPlayOptions(name) }
            }
            binding.sourceCards.addView(chip)
        }
    }

    // ─── 播放选项 ──────────────────────────────────────────────────────────

    private fun showPlayOptions(sourceName: String) {
        val urls = sourceStreams[sourceName] ?: return
        AlertDialog.Builder(this)
            .setTitle(sourceName)
            .setItems(arrayOf("📱 手机播放", "📺 投屏到电视")) { _, which ->
                when (which) {
                    0 -> openPlayer(urls.first(), false)
                    1 -> openPlayer(urls.first(), true)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun openPlayer(url: String, castMode: Boolean) {
        startActivity(Intent(this, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_STREAM_URL, url)
            putExtra(PlayerActivity.EXTRA_CAST_MODE, castMode)
        })
    }

    // ─── 工具栏菜单 ────────────────────────────────────────────────────────

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, MENU_REFRESH, 0, "🔄 刷新")
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == MENU_REFRESH) {
            binding.webView.reload()
            autoFetch()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onBackPressed() {
        if (binding.webView.canGoBack()) binding.webView.goBack() else super.onBackPressed()
    }

    // ─── 工具 ──────────────────────────────────────────────────────────────

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val MENU_REFRESH = 1
    }
}
