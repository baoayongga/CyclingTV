package com.cyclingtv.app

import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/**
 * 多源直播流提取引擎
 *
 * 支持来源：
 * 1. cycling.today — 主源（聚合站）
 * 2. tiz-cycling-live.io — 备用源1
 * 3. sport.freestreams-live.mp — 备用源2
 * 4. YouTube 搜索 — 备用源3（搜索赛事直播）
 */
object StreamExtractor {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val uaMobile = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    private val uaDesktop = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120.0.0.0 Safari/537.36"

    // 8 个正则匹配流 URL
    private val streamPatterns = listOf(
        Pattern.compile("""https?://[^"'\\s<>]+\.m3u8(?:[^"'\\s<>]*)?"""),
        Pattern.compile("""https?://[^"'\\s<>]+\.mpd(?:[^"'\\s<>]*)?"""),
        Pattern.compile("""https?://[^"'\\s<>]*(?:live|stream|hls|rtmp|broadcast|playlist)[^"'\\s<>]*\.m3u8[^"'\\s<>]*"""),
        Pattern.compile("""rtmps?://[^"'\\s<>]+"""),
        Pattern.compile("""https?://[^"'\\s<>]*[/](?:live|stream|hls|broadcast)[/](?!.*\.(?:html|css|js|png|jpg|svg|ico))[^"'\\s<>]{10,}"""),
        Pattern.compile("""https?://[^"'\\s<>]*/live/[^"'\\s<>]+/index\.m3u8"""),
        Pattern.compile("""https?://[^"'\\s<>]*\.akamaized\.net[^"'\\s<>]*\.m3u8"""),
        Pattern.compile("""https?://[^"'\\s<>]*\.cloudfront\.net[^"'\\s<>]*\.m3u8""")
    )

    // ─── 数据类 ───────────────────────────────────────────────────────────────

    data class SourceInfo(
        val name: String,       // 显示名称
        val url: String,        // 页面 URL
        val isYoutube: Boolean = false
    )

    // 所有支持的源
    val allSources = listOf(
        SourceInfo("cycling.today",      "https://cycling.today/"),
        SourceInfo("tiz-cycling-live.io","https://tiz-cycling-live.io/"),
        SourceInfo("freestreams-live",   "http://sport.freestreams-live.mp/cycling/"),
        SourceInfo("YouTube 搜索",       "https://www.youtube.com/results?search_query=cycling+live+stream+today&sp=CAM%253D", isYoutube = true)
    )

    // ─── 多源并发抓取 ─────────────────────────────────────────────────────────

    /**
     * 并发抓取所有源，返回 (来源名, 流URL列表) 的列表
     */
    fun fetchAllStreams(
        onProgress: (String) -> Unit = {}
    ): List<StreamResult> {
        val results = mutableListOf<StreamResult>()

        allSources.forEach { src ->
            onProgress("正在抓取 ${src.name}...")
            val urls = scrapeSource(src)
            if (urls.isNotEmpty()) {
                results.add(StreamResult(src.name, urls.distinct()))
            }
        }

        return results
    }

    /**
     * 抓取单个源
     */
    fun scrapeSource(source: SourceInfo): List<String> {
        return try {
            if (source.isYoutube) {
                scrapeYoutube()
            } else {
                scrapeGeneric(source.url, source.name)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    // ─── 通用 HTTP + Jsoup 爬取 ──────────────────────────────────────────────

    private fun scrapeGeneric(pageUrl: String, sourceName: String): List<String> {
        val urls = mutableListOf<String>()

        try {
            val req = Request.Builder()
                .url(pageUrl)
                .header("User-Agent", uaDesktop)
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Referer", "https://www.google.com/")
                .build()

            val body = client.newCall(req).execute().use { it.body?.string() ?: return urls }

            // 1. 全文正则匹配
            streamPatterns.forEach { pat ->
                val matcher = pat.matcher(body)
                while (matcher.find()) {
                    val url = matcher.group().trim()
                    if (isValidStreamUrl(url)) urls.add(url)
                }
            }

            // 2. Jsoup 解析 DOM
            val doc = Jsoup.parse(body)

            // video / source 标签
            doc.select("video, video source, source").forEach { el ->
                listOf("src", "data-src", "data-url", "data-stream").forEach { attr ->
                    el.attr(attr).takeIf { it.isNotBlank() }?.let { v ->
                        if (v.startsWith("http") && isValidStreamUrl(v)) urls.add(v)
                        else if (v.startsWith("//")) {
                            val full = "https:$v"
                            if (isValidStreamUrl(full)) urls.add(full)
                        }
                    }
                }
            }

            // iframe
            doc.select("iframe").forEach { el ->
                listOf("src", "data-src", "data-url").forEach { attr ->
                    val v = el.attr(attr)
                    if (v.isNotBlank() && v.startsWith("http") && !isAdDomain(v)) {
                        urls.add(v)  // 记录 iframe 地址供后续解析
                    }
                }
            }

            // 嵌入链接
            doc.select("a[href$=.m3u8], a[href$=.mpd]").forEach { el ->
                el.attr("href").takeIf { it.isNotBlank() && it.startsWith("http") }?.let {
                    urls.add(it)
                }
            }

            // 3. JavaScript 内联对象中提取（有些站在 JS 里藏 m3u8）
            val jsBlockRegex = Regex(
                """["'](https?://[^"']*\.m3u8[^"']*)["']""",
                RegexOption.IGNORE_CASE
            )
            jsBlockRegex.findAll(body).forEach { match ->
                val url = match.groupValues[1]
                if (isValidStreamUrl(url)) urls.add(url)
            }

        } catch (_: Exception) { }

        return urls.filter { isValidStreamUrl(it) }
    }

    // ─── YouTube 搜索 ────────────────────────────────────────────────────────

    /**
     * 抓取 YouTube 搜索结果中的视频链接
     * 使用 web scraping 方式（无需 API key）
     */
    private fun scrapeYoutube(): List<String> {
        val urls = mutableListOf<String>()

        try {
            // 搜索 query
            val searchUrl = "https://www.youtube.com/results?search_query=giro+ditalia+live+cycling+today+2026&sp=CAM%253D"
            val req = Request.Builder()
                .url(searchUrl)
                .header("User-Agent", uaDesktop)
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .build()

            val body = client.newCall(req).execute().use { it.body?.string() ?: return urls }

            // 提取 /watch?v=xxx 链接
            val watchRegex = Regex("""/watch\?v=([a-zA-Z0-9_-]{11})""")
            val seen = mutableSetOf<String>()
            watchRegex.findAll(body).forEach { match ->
                val videoId = match.groupValues[1]
                if (seen.add(videoId)) {
                    urls.add("https://www.youtube.com/watch?v=$videoId")
                }
            }

        } catch (_: Exception) { }

        return urls.take(10) // 最多返回 10 个
    }

    // ─── 辅助方法 ─────────────────────────────────────────────────────────────

    private fun isValidStreamUrl(url: String): Boolean {
        val lower = url.lowercase()
        return (lower.contains(".m3u8") || lower.contains(".mpd") ||
                lower.startsWith("rtmp") || lower.startsWith("rtmps") ||
                lower.contains("/hls/") || lower.contains("/live/") ||
                lower.contains("/stream/") || lower.contains("/broadcast/")) &&
                !lower.endsWith(".html") && !lower.endsWith(".css") &&
                !lower.endsWith(".js") && !lower.endsWith(".svg") &&
                !lower.endsWith(".ico") && !lower.endsWith(".png") &&
                !lower.endsWith(".jpg") && !lower.endsWith(".gif")
    }

    private fun isAdDomain(iframeUrl: String): Boolean {
        val adHosts = setOf(
            "doubleclick.net", "googlesyndication.com", "adnxs.com",
            "adsrvr.org", "amazon-adsystem.com", "criteo.com",
            "outbrain.com", "taboola.com", "adform.net", "pubmatic.com",
            "openx.net", "rubiconproject.com", "springserve.com"
        )
        val host = try {
            java.net.URI(iframeUrl).host ?: ""
        } catch (_: Exception) { "" }
        return adHosts.any { host.endsWith(it) } || host.isBlank()
    }

    // ─── 公开结果类型 ────────────────────────────────────────────────────────

    data class StreamResult(
        val sourceName: String,     // 来源名称
        val streamUrls: List<String>  // 该源发现的流地址列表
    )
}
