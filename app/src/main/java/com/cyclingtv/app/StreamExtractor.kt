package com.cyclingtv.app

import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/**
 * 直播流提取引擎
 *
 * 源优先级：
 * 1. cyclingstream.com   — 嵌入播放器，有 m3u8
 * 2. tiz-cycling.tv      — 有直播/回放
 * 3. 手动粘贴            — 用户自己输入 m3u8/rtmp 地址
 */
object StreamExtractor {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val uaDesktop = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    // 匹配流地址的正则
    private val streamPatterns = listOf(
        Pattern.compile("""https?://[^\s"'<>]+\.m3u8(?:[^\s"'<>]*)?"""),
        Pattern.compile("""https?://[^\s"'<>]+\.mpd(?:[^\s"'<>]*)?"""),
        Pattern.compile("""rtmps?://[^\s"'<>]+"""),
        Pattern.compile("""https?://[^\s"'<>]*\.akamaized\.net[^\s"'<>]*"""),
        Pattern.compile("""https?://[^\s"'<>]*\.cloudfront\.net[^\s"'<>]*\.m3u8"""),
        Pattern.compile("""https?://[^\s"'<>]*/hls/[^\s"'<>]+""")
    )

    // ─── 数据类 ─────────────────────────────────────────────────────────────

    data class SourceInfo(
        val name: String,
        val url: String,
        val description: String = ""
    )

    val allSources = listOf(
        SourceInfo(
            "cyclingstream.com",
            "https://cyclingstream.com/live-stream-2/",
            "专业自行车赛直播站"
        ),
        SourceInfo(
            "tiz-cycling.tv",
            "https://tiz-cycling.tv/main/",
            "经典赛事直播+回放"
        ),
        SourceInfo(
            "cyclingtiz.live",
            "https://cyclingtiz.live/",
            "Tiz 志愿者直播站"
        )
    )

    // ─── 多源抓取 ─────────────────────────────────────────────────────────

    fun fetchAllStreams(onProgress: (String) -> Unit = {}): List<StreamResult> {
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

    fun scrapeSource(source: SourceInfo): List<String> {
        return try {
            scrapeGeneric(source.url)
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ─── HTTP 抓取 ────────────────────────────────────────────────────────

    private fun scrapeGeneric(pageUrl: String): List<String> {
        val urls = mutableSetOf<String>()

        try {
            val body = fetchPage(pageUrl) ?: return emptyList()

            // 正则匹配所有流地址
            streamPatterns.forEach { pat ->
                val m = pat.matcher(body)
                while (m.find()) {
                    val url = m.group().trim().trimEnd(',', ';', '"', '\'')
                    if (isValidStream(url)) urls.add(url)
                }
            }

            // JS 字符串里的 m3u8
            val jsRegex = Regex("""["'](https?://[^"']*\.m3u8[^"']*)["']""", RegexOption.IGNORE_CASE)
            jsRegex.findAll(body).forEach { urls.add(it.groupValues[1]) }

            // 追踪 iframe 一级
            val iframeRegex = Regex("""<iframe[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            iframeRegex.findAll(body).forEach { m ->
                val iframeSrc = m.groupValues[1]
                if (iframeSrc.startsWith("http") && !isAdDomain(iframeSrc)) {
                    try {
                        val iframeBody = fetchPage(iframeSrc) ?: return@forEach
                        streamPatterns.forEach { pat ->
                            val pm = pat.matcher(iframeBody)
                            while (pm.find()) {
                                val u = pm.group().trim().trimEnd(',', ';', '"', '\'')
                                if (isValidStream(u)) urls.add(u)
                            }
                        }
                        val jsR = Regex("""["'](https?://[^"']*\.m3u8[^"']*)["']""", RegexOption.IGNORE_CASE)
                        jsR.findAll(iframeBody).forEach { urls.add(it.groupValues[1]) }
                    } catch (_: Exception) {}
                }
            }

        } catch (_: Exception) {}

        return urls.filter { isValidStream(it) }.take(20)
    }

    private fun fetchPage(url: String): String? {
        return try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", uaDesktop)
                .header("Accept", "text/html,application/xhtml+xml,*/*;q=0.9")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Referer", "https://www.google.com/")
                .build()
            client.newCall(req).execute().use { it.body?.string() }
        } catch (_: Exception) { null }
    }

    // ─── 辅助 ─────────────────────────────────────────────────────────────

    private fun isValidStream(url: String): Boolean {
        if (url.length < 10) return false
        val lower = url.lowercase()
        val hasStreamIndicator = lower.contains(".m3u8") || lower.contains(".mpd") ||
                lower.startsWith("rtmp") || lower.contains("/hls/")
        val notStaticFile = !lower.endsWith(".html") && !lower.endsWith(".css") &&
                !lower.endsWith(".js") && !lower.endsWith(".png") &&
                !lower.endsWith(".jpg") && !lower.endsWith(".ico") &&
                !lower.endsWith(".svg") && !lower.endsWith(".gif")
        return hasStreamIndicator && notStaticFile
    }

    private fun isAdDomain(url: String): Boolean {
        val adHosts = setOf("doubleclick.net", "googlesyndication.com", "adnxs.com",
            "criteo.com", "taboola.com", "outbrain.com", "pubmatic.com")
        val host = try { java.net.URI(url).host ?: "" } catch (_: Exception) { "" }
        return adHosts.any { host.endsWith(it) }
    }

    // ─── 结果类型 ─────────────────────────────────────────────────────────

    data class StreamResult(
        val sourceName: String,
        val streamUrls: List<String>
    )
}
