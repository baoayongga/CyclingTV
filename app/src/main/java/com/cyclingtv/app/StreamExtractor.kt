package com.cyclingtv.app

import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.jsoup.Jsoup
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

    // ═══════════════════════════════════════════════════════════════════════════
    // 源定义
    // ═══════════════════════════════════════════════════════════════════════════

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
        Pattern.compile("""https?://[^"'\\s<>]+\.m3u8(?:[^"'\\s<>]*)?"""),
        Pattern.compile("""https?://[^"'\\s<>]+\.mpd(?:[^"'\\s<>]*)?"""),
        Pattern.compile("""https?://[^"'\\s<>]*(?:live|stream|hls|rtmp|broadcast|playlist)[^"'\\s<>]*\.m3u8[^"'\\s<>]*"""),
        Pattern.compile("""rtmps?://[^"'\\s<>]+"""),
        Pattern.compile("""https?://[^"'\\s<>]*/(?:live|stream|hls|broadcast)/(?!.*\.(?:html|css|js|png|jpg|svg|ico))[^"'\\s<>]{10,}"""),
        Pattern.compile("""https?://[^"'\\s<>]*/live/[^"'\\s<>]+/index\.m3u8"""),
        Pattern.compile("""https?://[^"'\\s<>]*\.akamaized\.net[^"'\\s<>]*\.m3u8"""),
        Pattern.compile("""https?://[^"'\\s<>]*\.cloudfront\.net[^"'\\s<>]*\.m3u8"""),
        Pattern.compile("""https?://[^"'\\s<>]*\.(?:edge|cdn)[^"'\\s<>]*\.m3u8""")
    )

    // ═══════════════════════════════════════════════════════════════════════════
    // 公开 API
    // ═══════════════════════════════════════════════════════════════════════════

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

    // ═══════════════════════════════════════════════════════════════════════════
    // cycling.today — iframe → mindsleep.net → _econfig 解码
    // ═══════════════════════════════════════════════════════════════════════════

    private fun scrapeCyclingToday(): List<String> {
        val urls = mutableListOf<String>()

        val ctBody = fetchPageBody("https://cycling.today/") ?: return urls

        // 策略 A: 提取所有 iframe 并深入解码
        val doc = Jsoup.parse(ctBody)
        val iframes = doc.select("iframe").mapNotNull { el ->
            listOf("src", "data-src").firstNotNullOfOrNull { attr ->
                el.attr(attr).takeIf { it.isNotBlank() && it.startsWith("http") }
            }
        }

        for (iframeUrl in iframes) {
            urls.addAll(extractFromIframeDeep(iframeUrl, 0))
        }

        // 策略 B: 检测 mindsleep.net 链接（可能不在 iframe 中）
        if (urls.isEmpty()) {
            doc.select("a[href*='mindsleep.net']").forEach { a ->
                val href = a.attr("href")
                if (href.startsWith("http")) {
                    urls.addAll(extractFromIframeDeep(href, 0))
                }
            }
            doc.select("a[href*='d0000d.com'], a[href*='mixdrop.is']").forEach { a ->
                val href = a.attr("href")
                if (href.startsWith("http")) {
                    urls.addAll(extractFromIframeDeep(href, 0))
                }
            }
        }

        // 策略 C: fallback 通用正则
        if (urls.isEmpty()) {
            urls.addAll(scrapeGeneric("https://cycling.today/", "cycling.today"))
        }

        return urls.distinct()
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // tiz-cycling-live.io — 专用抓取
    // 策略：主页 → 找赛事链接 → 进入赛事页 → 提取 iframe → 解码
    // ═══════════════════════════════════════════════════════════════════════════

    private fun scrapeTizCycling(): List<String> {
        val urls = mutableListOf<String>()
        val baseUrl = "https://tiz-cycling-live.io/"

        try {
            val body = fetchPageBody(baseUrl) ?: return urls
            val doc = Jsoup.parse(body)

            // 策略 A: 找赛事链接（通常是 <a> 带 href 包含 race/live/stream 等）
            val raceLinks = mutableSetOf<String>()
            doc.select("a[href]").forEach { a ->
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
                streamPatterns.forEach { pat ->
                    val m = pat.matcher(deepBody)
                    while (m.find()) {
                        val url = m.group().trim()
                        if (isValidStreamUrl(url)) urls.add(url)
                    }
                }

                // iframe 深入
                val deepDoc = Jsoup.parse(deepBody)
                deepDoc.select("iframe").forEach { iframe ->
                    listOf("src", "data-src").forEach { attr ->
                        val src = iframe.attr(attr)
                        if (src.isNotBlank() && src.startsWith("http")) {
                            urls.addAll(extractFromIframeDeep(src, 1))
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

    // ═══════════════════════════════════════════════════════════════════════════
    // freestreams-live — 专用抓取
    // ═══════════════════════════════════════════════════════════════════════════

    private fun scrapeFreestreams(): List<String> {
        val urls = mutableListOf<String>()
        val baseUrl = "http://sport.freestreams-live.mp/cycling/"

        try {
            val body = fetchPageBody(baseUrl) ?: return urls
            val doc = Jsoup.parse(body)

            // 找赛事链接
            val eventLinks = mutableSetOf<String>()
            doc.select("a[href]").forEach { a ->
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
                eventDoc.select("iframe").forEach { iframe ->
                    listOf("src", "data-src").forEach { attr ->
                        val src = iframe.attr(attr)
                        if (src.isNotBlank() && src.startsWith("http")) {
                            urls.addAll(extractFromIframeDeep(src, 2, link))
                        }
                    }
                }

                // 正则
                streamPatterns.forEach { pat ->
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

    // ═══════════════════════════════════════════════════════════════════════════
    // steephill.tv — 聚合站抓取
    // ═══════════════════════════════════════════════════════════════════════════

    private fun scrapeSteephill(): List<String> {
        val urls = mutableListOf<String>()

        try {
            // steephill 首页有赛事列表（如 giro, tour de france 等）
            val body = fetchPageBody("https://www.steephill.tv/") ?: return urls
            val doc = Jsoup.parse(body)

            // 找当前赛事链接
            val eventLinks = mutableSetOf<String>()
            doc.select("a[href]").forEach { a ->
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
                streamPatterns.forEach { pat ->
                    val m = pat.matcher(eventBody)
                    while (m.find()) {
                        val url = m.group().trim()
                        if (isValidStreamUrl(url)) urls.add(url)
                    }
                }

                // 外部链接深入
                val eventDoc = Jsoup.parse(eventBody)
                eventDoc.select("a[href]").forEach { a ->
                    val href = a.attr("href")
                    if (href.startsWith("http") && !href.contains("steephill.tv") &&
                        !isAdDomain(href)) {
                        // 对于外部链接，用通用方法浅抓
                        urls.addAll(scrapeUrlShallow(href))
                    }
                }
            }

        } catch (_: Exception) { }

        return urls.distinct().take(20)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // cyclingfans.com — 聚合站抓取
    // ═══════════════════════════════════════════════════════════════════════════

    private fun scrapeCyclingfans(): List<String> {
        val urls = mutableListOf<String>()

        try {
            val body = fetchPageBody("https://www.cyclingfans.com/") ?: return urls
            val doc = Jsoup.parse(body)

            // 外部链接深入
            doc.select("a[href]").forEach { a ->
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

    // ═══════════════════════════════════════════════════════════════════════════
    // YouTube 搜索
    // ═══════════════════════════════════════════════════════════════════════════

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

                val watchRegex = Regex("""/watch\?v=([a-zA-Z0-9_-]{11})""")
                val seen = urls.map { it.substringAfter("v=").take(11) }.toMutableSet()
                watchRegex.findAll(body).forEach { match ->
                    val videoId = match.groupValues[1]
                    if (seen.add(videoId)) {
                        urls.add("https://www.youtube.com/watch?v=$videoId")
                    }
                }

                if (urls.size >= 15) break
            } catch (_: Exception) { }
        }

        return urls.take(15)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 通用抓取
    // ═══════════════════════════════════════════════════════════════════════════

    private fun scrapeGeneric(pageUrl: String, sourceName: String): List<String> {
        val urls = mutableListOf<String>()

        try {
            val body = fetchPageBody(pageUrl) ?: return urls

            // 1. 正则
            streamPatterns.forEach { pat ->
                val matcher = pat.matcher(body)
                while (matcher.find()) {
                    val url = matcher.group().trim()
                    if (isValidStreamUrl(url)) urls.add(url)
                }
            }

            // 2. DOM
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

            // iframe 深入
            doc.select("iframe").forEach { el ->
                listOf("src", "data-src").forEach { attr ->
                    val v = el.attr(attr)
                    if (v.isNotBlank() && v.startsWith("http") && !isAdDomain(v)) {
                        urls.addAll(extractFromIframeDeep(v, 1, pageUrl))
                    }
                }
            }

            // 链接
            doc.select("a[href$=.m3u8], a[href$=.mpd]").forEach { el ->
                el.attr("href").takeIf { it.isNotBlank() && it.startsWith("http") }?.let {
                    urls.add(it)
                }
            }

            // 3. JS 内联
            val jsRegex = Regex("""["'](https?://[^"']*\.m3u8[^"']*)["']""", RegexOption.IGNORE_CASE)
            jsRegex.findAll(body).forEach { match ->
                val url = match.groupValues[1]
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

            streamPatterns.forEach { pat ->
                val m = pat.matcher(body)
                while (m.find()) {
                    val u = m.group().trim()
                    if (isValidStreamUrl(u)) urls.add(u)
                }
            }

            val doc = Jsoup.parse(body)
            doc.select("video source, source, video").forEach { el ->
                listOf("src", "data-src").forEach { attr ->
                    el.attr(attr).takeIf { it.startsWith("http") && isValidStreamUrl(it) }
                        ?.let { urls.add(it) }
                }
            }
            doc.select("a[href$=.m3u8]").forEach { urls.add(it.attr("href")) }
        } catch (_: Exception) { }
        return urls
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // iframe 深度提取（最多 3 层）
    // ═══════════════════════════════════════════════════════════════════════════

    private fun extractFromIframeDeep(iframeUrl: String, depth: Int, referer: String? = null): List<String> {
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
            streamPatterns.forEach { pat ->
                val m = pat.matcher(body)
                while (m.find()) {
                    val url = m.group().trim()
                    if (isValidStreamUrl(url)) urls.add(url)
                }
            }

            // ── DOM 直接提取 ──
            val doc = Jsoup.parse(body)
            doc.select("video source, source").forEach { el ->
                listOf("src", "data-src").forEach { attr ->
                    el.attr(attr).takeIf { it.startsWith("http") && isValidStreamUrl(it) }
                        ?.let { urls.add(it) }
                }
            }

            // ── 更深层 iframe ──
            if (urls.isEmpty()) {
                doc.select("iframe").forEach { el ->
                    listOf("src", "data-src").forEach { attr ->
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

    // ═══════════════════════════════════════════════════════════════════════════
    // _econfig 双层 base64 解码核心
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * 从页面 HTML 及外部 JS 中提取并解码 _econfig
     */
    private fun decodeEconfigFromPage(pageBody: String, pageUrl: String): Map<String, String>? {
        val inlineEconfig = extractEconfigValue(pageBody)
        if (inlineEconfig != null) {
            return decodeEconfig(inlineEconfig)
        }

        // 查找外部 stream.js / player.js
        val scriptRegex = Regex("""<script[^>]*src=["']([^"']*(?:stream|player|embed|main)[^"']*\.js)["'][^>]*>""", RegexOption.IGNORE_CASE)
        val scriptMatch = scriptRegex.find(pageBody)
        if (scriptMatch != null) {
            var scriptUrl = scriptMatch.groupValues[1]
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
            Regex("""_econfig\s*=\s*'([^']+)'"""),
            Regex("""_econfig\s*=\s*"([^"]+)""""),
            Regex("""_econfig\s*=\s*`([^`]+)`""")
        )
        for (pat in patterns) {
            val match = pat.find(text)
            if (match != null) return match.groupValues[1]
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
            val reordered = reorder.map { chunks[it] }

            val trimmed = reordered.map { chunk ->
                val s = String(chunk, Charsets.ISO_8859_1)
                s.substring(0, 3) + s.substring(4)
            }

            val fromChunks = trimmed.map { Base64.decode(it, Base64.DEFAULT) }
            val combined = fromChunks.fold(ByteArray(0)) { acc, bytes -> acc + bytes }
            val final = Base64.decode(combined, Base64.DEFAULT)
            val json = JSONObject(String(final, Charsets.UTF_8))

            mapOf(
                "stream_url" to json.optString("stream_url", ""),
                "stream_url_nop2p" to json.optString("stream_url_nop2p", ""),
                "p2p_tracker" to json.optString("p2p_tracker", ""),
                "autoplay" to json.optString("autoplay", "").ifBlank { "true" },
                "debug" to json.optString("debug", "").ifBlank { "false" }
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // HTTP 工具
    // ═══════════════════════════════════════════════════════════════════════════

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

    // ═══════════════════════════════════════════════════════════════════════════
    // 辅助
    // ═══════════════════════════════════════════════════════════════════════════

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
