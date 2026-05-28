package com.cyclingtv.app

import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.jsoup.Jsoup
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/**
 * 多源直播流提取引擎 v2
 *
 * 支持来源（各有专用抓取器）：
 * 1. cycling.today       — iframe → mindsleep.net → _econfig 双层base64解码
 * 2. tiz-cycling-live.io — iframe 嵌套 + 正则 + DOM 多策略抓取
 * 3. freestreams-live    — 赛事列表页 → 详情页 → 提取播放源
 * 4. steephill.tv        — 聚合页：解析赛事列表 → 跟随外部链接抓取
 * 5. cyclingfans.com     — 类似 steephill 的聚合站
 * 6. YouTube 搜索         — 搜索自行车赛事直播视频
 *
 * 通用 fallback：适用于任何未知站点
 */
object StreamExtractor {

    // ════════════════════════════════════════════════════════════════════════
    // 源定义
    // ════════════════════════════════════════════════════════════════════════

    enum class SourceType {
        CYCLING_TODAY,
        TIZ_CYCLING,
        FREESTREAMS_LIVE,
        STEEPHILL,
        CYCLINGFANS,
        YOUTUBE,
        GENERIC   // fallback
    }

    data class SourceDef(
        val type: SourceType,
        val name: String,
        val url: String,
        val desc: String = ""
    )

    val allSources = listOf(
        SourceDef(SourceType.CYCLING_TODAY,    "cycling.today",       "https://cycling.today/",            "主源 · 含 mindsleep.net 解码"),
        SourceDef(SourceType.TIZ_CYCLING,      "tiz-cycling-live.io", "https://tiz-cycling-live.io/",      "备用 · 多 iframe 嵌套"),
        SourceDef(SourceType.FREESTREAMS_LIVE, "freestreams-live",    "http://sport.freestreams-live.mp/cycling/", "备用 · 赛事列表"),
        SourceDef(SourceType.STEEPHILL,        "steephill.tv",        "https://www.steephill.tv/",         "聚合 · 收录多个源链接"),
        SourceDef(SourceType.CYCLINGFANS,      "cyclingfans.com",     "https://www.cyclingfans.com/",      "聚合 · 收录多个源链接"),
        SourceDef(SourceType.YOUTUBE,          "YouTube 搜索",        "https://www.youtube.com/results?search_query=cycling+live+stream+giro+tour&sp=CAM%253D", "搜索 · 赛事直播")
    )

    // 源状态
    data class SourceStatus(
        val source: SourceDef,
        var state: State = State.IDLE,
        var streamCount: Int = 0,
        var errorMsg: String = ""
    ) {
        enum class State { IDLE, FETCHING, SUCCESS, EMPTY, ERROR }
    }

    // ─── HTTP 客户端 ───────────────────────────────────────────────────────────

    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val clientLongTimeout = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    // ─── User-Agent 轮换池 ──────────────────────────────────────────────────────

    private val userAgents = listOf(
        "Mozilla/5.0 (Linux; Android 13; SM-S9080) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"
    )
    private var uaIndex = 0

    private fun nextUA(): String = userAgents[uaIndex.also { uaIndex = (uaIndex + 1) % userAgents.size }]

    // ─── 正则模式池（用于通用匹配）───────────────────────────────────────────────

    private val streamPatterns = listOf(
        Pattern.compile("https?://[^\\s\"'<>]+\\.m3u8(?:[^\\s\"'<>]*)?", Pattern.CASE_INSENSITIVE),
        Pattern.compile("https?://[^\\s\"'<>]+\\.mpd(?:[^\\s\"'<>]*)?", Pattern.CASE_INSENSITIVE),
        Pattern.compile("https?://[^\\s\"'<>]*(?:live|stream|hls|rtmp|broadcast|playlist)[^\\s\"'<>]*\\.m3u8[^\\s\"'<>]*", Pattern.CASE_INSENSITIVE),
        Pattern.compile("rtmps?://[^\\s\"'<>]+", Pattern.CASE_INSENSITIVE),
        Pattern.compile("https?://[^\\s\"'<>]*/(?:live|stream|hls|broadcast)/(?!.*\\.(?:html|css|js|png|jpg|svg|ico))[^\\s\"'<>]{10,}", Pattern.CASE_INSENSITIVE),
        Pattern.compile("https?://[^\\s\"'<>]*/live/[^\\s\"'<>]+/index\\.m3u8", Pattern.CASE_INSENSITIVE),
        Pattern.compile("https?://[^\\s\"'<>]*\\.akamaized\\.net[^\\s\"'<>]*\\.m3u8", Pattern.CASE_INSENSITIVE),
        Pattern.compile("https?://[^\\s\"'<>]*\\.cloudfront\\.net[^\\s\"'<>]*\\.m3u8", Pattern.CASE_INSENSITIVE),
        Pattern.compile("https?://[^\\s\"'<>]*\\.(?:edge|cdn)[^\\s\"'<>]*\\.m3u8", Pattern.CASE_INSENSITIVE)
    )

    // ════════════════════════════════════════════════════════════════════════
    // 公开 API
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 抓取单个源，返回流地址列表 + 状态
     */
    fun scrapeSource(source: SourceDef, status: SourceStatus): List<String> {
        status.state = SourceStatus.State.FETCHING
        return try {
            val urls = when (source.type) {
                SourceType.CYCLING_TODAY    -> scrapeCyclingToday()
                SourceType.TIZ_CYCLING      -> scrapeTizCycling()
                SourceType.FREESTREAMS_LIVE -> scrapeFreestreams()
                SourceType.STEEPHILL        -> scrapeSteephill()
                SourceType.CYCLINGFANS      -> scrapeCyclingfans()
                SourceType.YOUTUBE          -> scrapeYoutube()
                SourceType.GENERIC          -> scrapeGeneric(source.url, source.name)
            }
            if (urls.isNotEmpty()) {
                status.state = SourceStatus.State.SUCCESS
                status.streamCount = urls.size
            } else {
                status.state = SourceStatus.State.EMPTY
                status.errorMsg = "未检测到直播流（可能当前无比赛）"
            }
            urls.distinct()
        } catch (e: Exception) {
            status.state = SourceStatus.State.ERROR
            status.errorMsg = e.message ?: "未知错误"
            e.printStackTrace()
            emptyList()
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // cycling.today — iframe → mindsleep.net → _econfig 解码
    // ════════════════════════════════════════════════════════════════════════

    private fun scrapeCyclingToday(): List<String> {
        val urls = mutableListOf<String>()

        val ctBody = fetchPageBody("https://cycling.today/") ?: return urls

        // 策略 A: 提取所有 iframe 并深入解码
        val doc = Jsoup.parse(ctBody)
        val iframes = mutableListOf<String>()
        for (el in doc.select("iframe")) {
            var found: String? = null
            for (attr in listOf("src", "data-src")) {
                val v = el.attr(attr)
                if (v.isNotBlank() && v.startsWith("http")) {
                    found = v
                    break
                }
            }
            if (found != null) {
                iframes.add(found)
            }
        }

        for (iframeUrl in iframes) {
            urls.addAll(extractFromIframeDeep(iframeUrl, 0, null))
        }

        // 策略 B: 检测 mindsleep.net 链接（可能不在 iframe 中）
        if (urls.isEmpty()) {
            for (a in doc.select("a[href]")) {
                val href = a.attr("href")
                if (href.contains("mindsleep.net") || href.contains("d0000d.com") || href.contains("mixdrop.is")) {
                    if (href.startsWith("http")) {
                        urls.addAll(extractFromIframeDeep(href, 0, null))
                    }
                }
            }
        }

        // 策略 C: fallback 通用正则
        if (urls.isEmpty()) {
            urls.addAll(scrapeGeneric("https://cycling.today/", "cycling.today"))
        }

        return urls.distinct()
    }

    // ════════════════════════════════════════════════════════════════════════
    // tiz-cycling-live.io — 专用抓取
    // ════════════════════════════════════════════════════════════════════════

    private fun scrapeTizCycling(): List<String> {
        val urls = mutableListOf<String>()
        val baseUrl = "https://tiz-cycling-live.io/"

        try {
            val body = fetchPageBody(baseUrl) ?: return urls
            val doc = Jsoup.parse(body)

            // 策略 A: 找赛事链接
            val raceLinks = mutableSetOf<String>()
            for (a in doc.select("a[href]")) {
                val href = a.attr("href").lowercase()
                val text = a.text().lowercase()
                if ((href.contains("race") || href.contains("live") || href.contains("stream") ||
                     href.contains("giro") || href.contains("tour") || href.contains("vuelta") ||
                     text.contains("live") || text.contains("stream") || text.contains("watch")) &&
                    !href.contains("javascript") && !href.contains("mailto")) {
                    var fullUrl = a.attr("abs:href")
                    if (fullUrl.isBlank() || !fullUrl.startsWith("http")) {
                        fullUrl = resolveUrl(baseUrl, a.attr("href"))
                    }
                    if (fullUrl.startsWith("http") && fullUrl != baseUrl) {
                        raceLinks.add(fullUrl)
                    }
                }
            }

            // 对每个赛事链接深入抓取
            for (link in raceLinks.take(5)) {
                val deepBody = fetchPageBody(link) ?: continue

                // 正则 + DOM
                for (pat in streamPatterns) {
                    val m = pat.matcher(deepBody)
                    while (m.find()) {
                        val url = m.group().trim()
                        if (isValidStreamUrl(url)) urls.add(url)
                    }
                }

                // iframe 深入
                val deepDoc = Jsoup.parse(deepBody)
                for (iframe in deepDoc.select("iframe")) {
                    for (attr in listOf("src", "data-src")) {
                        val src = iframe.attr(attr)
                        if (src.isNotBlank() && src.startsWith("http")) {
                            urls.addAll(extractFromIframeDeep(src, 1, link))
                        }
                    }
                }

                // 尝试 _econfig 解码
                val econfig = decodeEconfigFromPage(deepBody, link)
                econfig?.get("stream_url")?.takeIf { it.isNotBlank() }?.let { urls.add(it) }
                econfig?.get("stream_url_nop2p")?.takeIf { it.isNotBlank() }?.let { urls.add(it) }

                if (urls.isNotEmpty()) break
            }

            // 策略 B: 直接在首页用通用方法
            if (urls.isEmpty()) {
                urls.addAll(scrapeGeneric(baseUrl, "tiz-cycling-live.io"))
            }

        } catch (_: Exception) { }

        return urls.distinct()
    }

    // ════════════════════════════════════════════════════════════════════════
    // freestreams-live — 专用抓取
    // ════════════════════════════════════════════════════════════════════════

    private fun scrapeFreestreams(): List<String> {
        val urls = mutableListOf<String>()
        val baseUrl = "http://sport.freestreams-live.mp/cycling/"

        try {
            val body = fetchPageBody(baseUrl) ?: return urls
            val doc = Jsoup.parse(body)

            // 找赛事链接
            val eventLinks = mutableSetOf<String>()
            for (a in doc.select("a[href]")) {
                val href = a.attr("href")
                val text = a.text().lowercase()
                if ((text.contains("giro") || text.contains("tour") || text.contains("cycling") ||
                     text.contains("race") || text.contains("live")) &&
                    !href.contains("javascript:")) {
                    var fullUrl = a.attr("abs:href")
                    if (!fullUrl.startsWith("http")) fullUrl = resolveUrl(baseUrl, href)
                    if (fullUrl.startsWith("http")) eventLinks.add(fullUrl)
                }
            }

            // 深入每个赛事链接
            for (link in eventLinks.take(3)) {
                val eventBody = fetchPageBody(link) ?: continue
                val eventDoc = Jsoup.parse(eventBody)

                // iframe 深入
                for (iframe in eventDoc.select("iframe")) {
                    for (attr in listOf("src", "data-src")) {
                        val src = iframe.attr(attr)
                        if (src.isNotBlank() && src.startsWith("http")) {
                            urls.addAll(extractFromIframeDeep(src, 2, link))
                        }
                    }
                }

                // 正则
                for (pat in streamPatterns) {
                    val m = pat.matcher(eventBody)
                    while (m.find()) {
                        val url = m.group().trim()
                        if (isValidStreamUrl(url)) urls.add(url)
                    }
                }

                if (urls.isNotEmpty()) break
            }

            // fallback
            if (urls.isEmpty()) {
                urls.addAll(scrapeGeneric(baseUrl, "freestreams-live"))
            }

        } catch (_: Exception) { }

        return urls.distinct()
    }

    // ════════════════════════════════════════════════════════════════════════
    // steephill.tv — 聚合站抓取
    // ════════════════════════════════════════════════════════════════════════

    private fun scrapeSteephill(): List<String> {
        val urls = mutableListOf<String>()

        try {
            val body = fetchPageBody("https://www.steephill.tv/") ?: return urls
            val doc = Jsoup.parse(body)

            // 找当前赛事链接
            val eventLinks = mutableSetOf<String>()
            for (a in doc.select("a[href]")) {
                val href = a.attr("href").lowercase()
                val text = a.text().lowercase()
                if ((href.contains("giro") || href.contains("tour-de-france") ||
                     href.contains("vuelta") || href.contains("classics")) &&
                    text.isNotBlank() && text.length > 3) {
                    val fullUrl = a.attr("abs:href")
                    if (fullUrl.startsWith("http") && !eventLinks.contains(fullUrl)) {
                        eventLinks.add(fullUrl)
                    }
                }
            }

            // 对每个赛事页面提取流
            for (link in eventLinks.take(3)) {
                val eventBody = fetchPageBody(link) ?: continue

                // 正则
                for (pat in streamPatterns) {
                    val m = pat.matcher(eventBody)
                    while (m.find()) {
                        val url = m.group().trim()
                        if (isValidStreamUrl(url)) urls.add(url)
                    }
                }

                // 外部链接深入
                val eventDoc = Jsoup.parse(eventBody)
                for (a in eventDoc.select("a[href]")) {
                    val href = a.attr("href")
                    if (href.startsWith("http") && !href.contains("steephill.tv") &&
                        !isAdDomain(href)) {
                        urls.addAll(scrapeUrlShallow(href))
                    }
                }
            }

        } catch (_: Exception) { }

        return urls.distinct().take(20)
    }

    // ════════════════════════════════════════════════════════════════════════
    // cyclingfans.com — 聚合站抓取
    // ════════════════════════════════════════════════════════════════════════

    private fun scrapeCyclingfans(): List<String> {
        val urls = mutableListOf<String>()

        try {
            val body = fetchPageBody("https://www.cyclingfans.com/") ?: return urls
            val doc = Jsoup.parse(body)

            // 外部链接深入
            for (a in doc.select("a[href]")) {
                val href = a.attr("href")
                val text = a.text().lowercase()
                if (href.startsWith("http") && !href.contains("cyclingfans.com") &&
                    !isAdDomain(href) &&
                    (text.contains("live") || text.contains("stream") || text.contains("watch") ||
                     text.contains("video"))) {
                    urls.addAll(scrapeUrlShallow(href))
                }
            }

            // 通用
            if (urls.isEmpty()) {
                urls.addAll(scrapeGeneric("https://www.cyclingfans.com/", "cyclingfans.com"))
            }

        } catch (_: Exception) { }

        return urls.distinct().take(20)
    }

    // ════════════════════════════════════════════════════════════════════════
    // YouTube 搜索
    // ════════════════════════════════════════════════════════════════════════

    private fun scrapeYoutube(): List<String> {
        val urls = mutableListOf<String>()
        val searchQueries = listOf(
            "https://www.youtube.com/results?search_query=giro+ditalia+2026+live+cycling&sp=CAM%253D",
            "https://www.youtube.com/results?search_query=cycling+live+stream+today&sp=CAM%253D",
            "https://www.youtube.com/results?search_query=tour+de+france+live+stream&sp=CAM%253D"
        )

        for (searchUrl in searchQueries) {
            try {
                val req = Request.Builder()
                    .url(searchUrl)
                    .header("User-Agent", nextUA())
                    .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                    .build()

                val body = client.newCall(req).execute().use { it.body?.string() ?: continue }

                // 用 Pattern 而不是 Kotlin Regex，避免 RegexOption 问题
                val watchPattern = Pattern.compile("/watch\\?v=([a-zA-Z0-9_-]{11})")
                val seen = mutableSetOf<String>()
                val m = watchPattern.matcher(body)
                while (m.find()) {
                    val videoId = m.group(1)
                    if (seen.add(videoId)) {
                        urls.add("https://www.youtube.com/watch?v=$videoId")
                    }
                }

                if (urls.size >= 15) break
            } catch (_: Exception) { }
        }

        return urls.take(15)
    }

    // ════════════════════════════════════════════════════════════════════════
    // 通用抓取
    // ════════════════════════════════════════════════════════════════════════

    private fun scrapeGeneric(pageUrl: String, sourceName: String): List<String> {
        val urls = mutableListOf<String>()

        try {
            val body = fetchPageBody(pageUrl) ?: return urls

            // 1. 正则
            for (pat in streamPatterns) {
                val matcher = pat.matcher(body)
                while (matcher.find()) {
                    val url = matcher.group().trim()
                    if (isValidStreamUrl(url)) urls.add(url)
                }
            }

            // 2. DOM
            val doc = Jsoup.parse(body)

            // video / source 标签
            for (el in doc.select("video, video source, source")) {
                for (attr in listOf("src", "data-src", "data-url", "data-stream")) {
                    val v = el.attr(attr)
                    if (v.isNotBlank()) {
                        if (v.startsWith("http") && isValidStreamUrl(v)) urls.add(v)
                        else if (v.startsWith("//")) {
                            val full = "https:$v"
                            if (isValidStreamUrl(full)) urls.add(full)
                        }
                    }
                }
            }

            // iframe 深入
            for (el in doc.select("iframe")) {
                for (attr in listOf("src", "data-src")) {
                    val v = el.attr(attr)
                    if (v.isNotBlank() && v.startsWith("http") && !isAdDomain(v)) {
                        urls.addAll(extractFromIframeDeep(v, 1, pageUrl))
                    }
                }
            }

            // 链接
            for (el in doc.select("a[href$=.m3u8], a[href$=.mpd]")) {
                val href = el.attr("href")
                if (href.isNotBlank() && href.startsWith("http")) {
                    urls.add(href)
                }
            }

            // 3. JS 内联 —— 用 Pattern 避免 RegexOption
            val jsPattern = Pattern.compile("[\"'](https?://[^\"']*\\.m3u8[^\"']*)[\"']", Pattern.CASE_INSENSITIVE)
            val jsM = jsPattern.matcher(body)
            while (jsM.find()) {
                val url = jsM.group(1)
                if (isValidStreamUrl(url)) urls.add(url)
            }

            // 4. _econfig 解码
            if (urls.isEmpty()) {
                val econfig = decodeEconfigFromPage(body, pageUrl)
                econfig?.get("stream_url")?.takeIf { it.isNotBlank() }?.let { urls.add(it) }
                econfig?.get("stream_url_nop2p")?.takeIf { it.isNotBlank() }?.let { urls.add(it) }
            }

        } catch (_: Exception) { }

        return urls.filter { isValidStreamUrl(it) }
    }

    /**
     * 浅层抓取一个 URL（仅正则 + DOM，不深入 iframe）
     */
    private fun scrapeUrlShallow(url: String): List<String> {
        val urls = mutableListOf<String>()
        try {
            val body = fetchPageBody(url) ?: return urls

            for (pat in streamPatterns) {
                val m = pat.matcher(body)
                while (m.find()) {
                    val u = m.group().trim()
                    if (isValidStreamUrl(u)) urls.add(u)
                }
            }

            val doc = Jsoup.parse(body)
            for (el in doc.select("video source, source, video")) {
                for (attr in listOf("src", "data-src")) {
                    val v = el.attr(attr)
                    if (v.startsWith("http") && isValidStreamUrl(v)) {
                        urls.add(v)
                    }
                }
            }
            for (el in doc.select("a[href$=.m3u8]")) {
                val href = el.attr("href")
                if (href.isNotBlank()) urls.add(href)
            }
        } catch (_: Exception) { }
        return urls
    }

    // ════════════════════════════════════════════════════════════════════════
    // iframe 深度提取（最多 3 层）
    // ════════════════════════════════════════════════════════════════════════

    private fun extractFromIframeDeep(iframeUrl: String, depth: Int, referer: String?): List<String> {
        if (depth > 3 || isAdDomain(iframeUrl)) return emptyList()
        val urls = mutableListOf<String>()

        try {
            val reqBuilder = Request.Builder()
                .url(iframeUrl)
                .header("User-Agent", nextUA())
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")

            if (referer != null) reqBuilder.header("Referer", referer)

            val body = clientLongTimeout.newCall(reqBuilder.build()).execute().use {
                it.body?.string() ?: return urls
            }

            // ── mindsleep.net 专用解码 ──
            val host = try { java.net.URI(iframeUrl).host ?: "" } catch (_: Exception) { "" }
            if (host.contains("mindsleep.net")) {
                val decoded = decodeEconfigFromPage(body, iframeUrl)
                if (decoded != null) {
                    decoded["stream_url"]?.takeIf { it.isNotBlank() }?.let { urls.add(it) }
                    decoded["stream_url_nop2p"]?.takeIf { it.isNotBlank() }?.let { urls.add(it) }
                    if (urls.isNotEmpty()) return urls
                }
            }

            // ── 通用 _econfig 解码 ──
            val genericEconfig = decodeEconfigFromPage(body, iframeUrl)
            if (genericEconfig != null) {
                genericEconfig["stream_url"]?.takeIf { it.isNotBlank() }?.let { urls.add(it) }
                genericEconfig["stream_url_nop2p"]?.takeIf { it.isNotBlank() }?.let { urls.add(it) }
                if (urls.isNotEmpty()) return urls
            }

            // ── 正则匹配 ──
            for (pat in streamPatterns) {
                val m = pat.matcher(body)
                while (m.find()) {
                    val url = m.group().trim()
                    if (isValidStreamUrl(url)) urls.add(url)
                }
            }

            // ── DOM 直接提取 ──
            val doc = Jsoup.parse(body)
            for (el in doc.select("video source, source")) {
                for (attr in listOf("src", "data-src")) {
                    val v = el.attr(attr)
                    if (v.startsWith("http") && isValidStreamUrl(v)) {
                        urls.add(v)
                    }
                }
            }

            // ── 更深层 iframe ──
            if (urls.isEmpty()) {
                for (el in doc.select("iframe")) {
                    for (attr in listOf("src", "data-src")) {
                        val nestedUrl = el.attr(attr)
                        if (nestedUrl.isNotBlank() && nestedUrl.startsWith("http")) {
                            urls.addAll(extractFromIframeDeep(nestedUrl, depth + 1, iframeUrl))
                        }
                    }
                }
            }

        } catch (_: Exception) { }

        return urls
    }

    // ════════════════════════════════════════════════════════════════════════
    // _econfig 双层 base64 解码核心
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 从页面 HTML 及外部 JS 中提取并解码 _econfig
     */
    private fun decodeEconfigFromPage(pageBody: String, pageUrl: String): Map<String, String>? {
        val inlineEconfig = extractEconfigValue(pageBody)
        if (inlineEconfig != null) {
            return decodeEconfig(inlineEconfig)
        }

        // 查找外部 stream.js / player.js
        val scriptPattern = Pattern.compile("<script[^>]*src=[\"']([^\"']*(?:stream|player|embed|main)[^\"']*\\.js)[\"'][^>]*>", Pattern.CASE_INSENSITIVE)
        val scriptMatch = scriptPattern.matcher(pageBody)
        if (scriptMatch.find()) {
            var scriptUrl = scriptMatch.group(1)
            if (!scriptUrl.startsWith("http")) {
                scriptUrl = resolveUrl(pageUrl, scriptUrl)
            }
            val jsBody = fetchPageBody(scriptUrl)
            if (jsBody != null) {
                val econfigValue = extractEconfigValue(jsBody)
                if (econfigValue != null) {
                    return decodeEconfig(econfigValue)
                }
            }
        }

        return null
    }

    private fun extractEconfigValue(text: String): String? {
        val patterns = listOf(
            Pattern.compile("_econfig\\s*=\\s*'([^']+)'"),
            Pattern.compile("_econfig\\s*=\\s*\"([^\"]+)\""),
            Pattern.compile("_econfig\\s*=\\s*`([^`]+)`")
        )
        for (pat in patterns) {
            val m = pat.matcher(text)
            if (m.find()) return m.group(1)
        }
        return null
    }

    /**
     * 双层 base64 解码 _econfig → 提取 stream_url
     *
     * JS 编码流程：  JSON → base64 → 拆4块 → 每块加干扰字符 → 重排[2,0,3,1] → 拼接 → base64
     * 本方法反向解码：base64 → 拆4块 → 重排 → 每块去干扰 → base64 → 拼接 → base64 → JSON
     */
    fun decodeEconfig(econfig: String): Map<String, String>? {
        return try {
            val decoded1 = Base64.decode(econfig, Base64.DEFAULT)
            val chunkLen = decoded1.size / 4

            val chunks = Array(4) { i ->
                decoded1.copyOfRange(i * chunkLen, (i + 1) * chunkLen)
            }

            val reorder = intArrayOf(2, 0, 3, 1)
            val reordered = mutableListOf<ByteArray>()
            for (idx in reorder) {
                reordered.add(chunks[idx])
            }

            val trimmed = mutableListOf<String>()
            for (chunk in reordered) {
                val s = String(chunk, StandardCharsets.ISO_8859_1)
                // 去掉每块第4个字符（JS里 push(chars[3]) 是干扰字符）
                val trimmedStr = s.substring(0, 3) + s.substring(4)
                trimmed.add(trimmedStr)
            }

            val fromChunks = mutableListOf<ByteArray>()
            for (t in trimmed) {
                fromChunks.add(Base64.decode(t, Base64.DEFAULT))
            }

            // 拼接所有块
            var totalLen = 0
            for (b in fromChunks) totalLen += b.size
            val combined = ByteArray(totalLen)
            var offset = 0
            for (b in fromChunks) {
                System.arraycopy(b, 0, combined, offset, b.size)
                offset += b.size
            }

            val finalBytes = Base64.decode(combined, Base64.DEFAULT)
            val json = JSONObject(String(finalBytes, StandardCharsets.UTF_8))

            mapOf(
                "stream_url" to json.optString("stream_url", ""),
                "stream_url_nop2p" to json.optString("stream_url_nop2p", ""),
                "p2p_tracker" to json.optString("p2p_tracker", ""),
                "autoplay" to json.optString("autoplay", "true"),
                "debug" to json.optString("debug", "false")
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // HTTP 工具
    // ════════════════════════════════════════════════════════════════════════

    private fun fetchPageBody(url: String): String? {
        return try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", nextUA())
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Referer", "https://www.google.com/")
                .build()

            client.newCall(req).execute().use { it.body?.string() }
        } catch (_: Exception) {
            null
        }
    }

    private fun resolveUrl(baseUrl: String, relativeUrl: String): String {
        return try {
            val base = java.net.URI(baseUrl)
            base.resolve(relativeUrl).toString()
        } catch (_: Exception) {
            relativeUrl
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // 辅助
    // ════════════════════════════════════════════════════════════════════════

    private fun isValidStreamUrl(url: String): Boolean {
        val lower = url.lowercase()
        return (lower.contains(".m3u8") || lower.contains(".mpd") ||
                lower.startsWith("rtmp") || lower.startsWith("rtmps") ||
                lower.contains("/hls/") || lower.contains("/live/") ||
                lower.contains("/stream/") || lower.contains("/broadcast/")) &&
                !lower.endsWith(".html") && !lower.endsWith(".css") &&
                !lower.endsWith(".js") && !lower.endsWith(".svg") &&
                !lower.endsWith(".ico") && !lower.endsWith(".png") &&
                !lower.endsWith(".jpg") && !lower.endsWith(".gif") &&
                !lower.endsWith(".woff") && !lower.endsWith(".woff2") &&
                !lower.endsWith(".ttf")
    }

    private fun isAdDomain(url: String): Boolean {
        val adHosts = setOf(
            "doubleclick.net", "googlesyndication.com", "adnxs.com",
            "adsrvr.org", "amazon-adsystem.com", "criteo.com",
            "outbrain.com", "taboola.com", "adform.net", "pubmatic.com",
            "openx.net", "rubiconproject.com", "springserve.com",
            "bidswitch.net", "smartadserver.com"
        )
        val host = try {
            java.net.URI(url).host ?: ""
        } catch (_: Exception) { "" }
        return adHosts.any { host.endsWith(it) } || host.isBlank()
    }

    // ─── 结果类型 ─────────────────────────────────────────────────────────────

    data class StreamResult(
        val sourceName: String,
        val streamUrls: List<String>
    )
}
