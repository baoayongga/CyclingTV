package com.cyclingtv.app

import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.jsoup.Jsoup
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/**
 * 多源直播流提取引擎
 *
 * 支持来源：
 * 1. cycling.today — 主源（聚合站，mindsleep _econfig 解码）
 * 2. cyclingtiz.live — 备用源1
 * 3. YouTube 搜索 — 备用源2（搜索赛事直播）
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
        SourceInfo("cyclingtiz.live",    "https://cyclingtiz.live/"),
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
            when {
                source.isYoutube -> scrapeYoutube()
                source.name == "cycling.today" -> scrapeCyclingToday()
                else -> scrapeGeneric(source.url, source.name)
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

    // ─── cycling.today 专用 ─────────────────────────────────────────────────

    /**
     * cycling.today 专用抓取：
     * cycling.today → iframe → mindsleep.net → _econfig (双层 base64 解码) → HLS URL
     */
    private fun scrapeCyclingToday(): List<String> {
        val urls = mutableListOf<String>()

        try {
            // 1. 先通用抓取 (兜底)
            urls.addAll(scrapeGeneric("https://cycling.today/", "cycling.today"))

            // 2. 获取页面，解析 iframe
            val req = Request.Builder()
                .url("https://cycling.today/")
                .header("User-Agent", uaDesktop)
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .build()

            val body = client.newCall(req).execute().use { it.body?.string() ?: return urls.distinct() }
            val doc = Jsoup.parse(body)

            // 3. 跟随所有 iframe，尝试 _econfig 解码
            val iframeUrls = mutableListOf<String>()
            doc.select("iframe").forEach { el ->
                listOf("src", "data-src").forEach { attr ->
                    val v = el.attr(attr)
                    if (v.isNotBlank() && v.startsWith("http")) iframeUrls.add(v)
                }
            }

            for (iframeUrl in iframeUrls) {
                val decoded = decodeEconfigFromPage(iframeUrl)
                if (decoded != null) {
                    val streamUrl = decoded["stream_url"]
                    if (!streamUrl.isNullOrBlank()) urls.add(streamUrl)
                }
                // 也递归深入一层
                val deepBody = fetchPageBody(iframeUrl)
                if (deepBody != null) {
                    streamPatterns.forEach { pat ->
                        val m = pat.matcher(deepBody)
                        while (m.find()) {
                            val u = m.group().trim()
                            if (isValidStreamUrl(u)) urls.add(u)
                        }
                    }
                }
            }
        } catch (_: Exception) { }

        return urls.filter { isValidStreamUrl(it) }.distinct()
    }

    // ─── mindsleep.net _econfig 解码 ─────────────────────────────────────────

    /**
     * 从页面 HTML 中提取 _econfig 并解码
     * @param pageUrl 页面 URL (如 mindsleep.net/e/xxx)
     */
    private fun decodeEconfigFromPage(pageUrl: String): MutableMap<String, String>? {
        try {
            val body = fetchPageBody(pageUrl) ?: return null
            return decodeEconfigFromHtml(body)
        } catch (_: Exception) { }
        return null
    }

    /**
     * 从 HTML 文本中查找 _econfig 变量并解码
     */
    private fun decodeEconfigFromHtml(html: String): MutableMap<String, String>? {
        // 查找 var _econfig = 或 let _econfig =
        val pat = Pattern.compile(
            """(?:var|let|const)\s+_econfig\s*=\s*["']([^"']+)["']""",
            Pattern.CASE_INSENSITIVE
        )
        val m = pat.matcher(html)
        if (m.find()) {
            return decodeEconfig(m.group(1))
        }
        return null
    }

    /**
     * 解码 _econfig 值: 双层 base64 + 字符洗牌
     *
     * 算法:
     *   1. base64 decode → bytes
     *   2. 分成 4 chunk → 重排 [2,0,3,1]
     *   3. 每 chunk 去掉第 4 个字符
     *   4. 每 chunk base64 decode
     *   5. 拼接 → base64 decode → JSON
     */
    fun decodeEconfig(econfig: String): MutableMap<String, String>? {
        try {
            val decoded1 = Base64.decode(econfig, Base64.DEFAULT)

            val chunkLen = decoded1.size / 4
            val chunks = arrayOf(
                decoded1.copyOfRange(0, chunkLen),
                decoded1.copyOfRange(chunkLen, chunkLen * 2),
                decoded1.copyOfRange(chunkLen * 2, chunkLen * 3),
                decoded1.copyOfRange(chunkLen * 3, decoded1.size)
            )

            // 重排顺序: [2, 0, 3, 1]
            val ordered = arrayOf(chunks[2], chunks[0], chunks[3], chunks[1])

            // 每段去掉第 4 字节 (index 3)，然后 base64 decode
            val parts = ordered.map { chunk ->
                val s = String(chunk, charset("ISO-8859-1"))
                if (s.length >= 4) {
                    val trimmed = s.substring(0, 3) + s.substring(4)
                    Base64.decode(trimmed, Base64.DEFAULT)
                } else {
                    chunk
                }
            }

            // 拼接所有 parts
            val combined = parts.fold(ByteArray(0)) { acc, bytes -> acc + bytes }
            val final = Base64.decode(combined, Base64.DEFAULT)
            val json = JSONObject(String(final, charset("UTF-8")))

            val result = mutableMapOf<String, String>()
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                result[key] = json.optString(key, "")
            }
            return result
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    // ─── HTTP 工具 ─────────────────────────────────────────────────────────

    private fun fetchPageBody(url: String): String? {
        return try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", uaDesktop)
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Referer", "https://www.google.com/")
                .build()
            client.newCall(req).execute().use { it.body?.string() }
        } catch (_: Exception) { null }
    }

    private fun charset(name: String): java.nio.charset.Charset {
        return java.nio.charset.Charset.forName(name)
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
