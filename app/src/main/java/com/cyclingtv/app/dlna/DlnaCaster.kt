package com.cyclingtv.app.dlna

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/**
 * DLNA/UPnP 工具类
 * - scanDevices()：SSDP 扫描局域网 DLNA 渲染器
 * - castTo()：通过 AVTransport SOAP 推送视频流
 */
object DlnaCaster {

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    // ─── SSDP 扫描 ───────────────────────────────────────────────────────────

    private const val SSDP_ADDR = "239.255.255.250"
    private const val SSDP_PORT = 1900
    private const val SSDP_TIMEOUT_MS = 3000

    private val SSDP_SEARCH = "M-SEARCH * HTTP/1.1\r\n" +
            "HOST: 239.255.255.250:1900\r\n" +
            "MAN: \"ssdp:discover\"\r\n" +
            "MX: 2\r\n" +
            "ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n"

    data class DlnaDevice(
        val ip: String,
        val friendlyName: String,
        val controlUrl: String,
        val supportedFormats: List<String> = emptyList()
    )

    fun scanDevices(): List<DlnaDevice> {
        val devices = mutableListOf<DlnaDevice>()
        try {
            val socket = MulticastSocket().apply {
                soTimeout = SSDP_TIMEOUT_MS
            }
            val group = InetAddress.getByName(SSDP_ADDR)
            val data = SSDP_SEARCH.toByteArray()
            socket.send(DatagramPacket(data, data.size, group, SSDP_PORT))

            val buf = ByteArray(4096)
            while (true) {
                try {
                    val dp = DatagramPacket(buf, buf.size)
                    socket.receive(dp)
                    val response = String(dp.data, 0, dp.length)
                    val ip = dp.address.hostAddress ?: continue
                    // 提取 LOCATION
                    val loc = Regex("LOCATION:\\s*(\\S+)", RegexOption.IGNORE_CASE)
                        .find(response)?.groupValues?.get(1) ?: continue
                    // 获取设备描述 XML
                    val device = fetchDeviceDescription(loc, ip)
                    if (device != null && devices.none { it.ip == ip }) {
                        devices.add(device)
                    }
                } catch (_: SocketTimeoutException) {
                    break
                }
            }
            socket.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return devices
    }

    private fun fetchDeviceDescription(locationUrl: String, ip: String): DlnaDevice? {
        return try {
            val req = Request.Builder().url(locationUrl).build()
            val xml = client.newCall(req).execute().use { it.body?.string() ?: "" }

            val friendlyName = Regex("<friendlyName>([^<]+)</friendlyName>")
                .find(xml)?.groupValues?.get(1) ?: "电视($ip)"

            // 找 AVTransport service 的 controlURL
            val controlPath = findAvTransportControlUrl(xml)
            if (controlPath == null) return null

            // 正确拼接：用 URL 的 origin (scheme+host+port) 而不是截断路径
            val controlUrl = if (controlPath.startsWith("http")) {
                controlPath
            } else {
                val uri = java.net.URI(locationUrl)
                val origin = "${uri.scheme}://${uri.host}:${uri.port}"
                val path = if (controlPath.startsWith("/")) controlPath else "/$controlPath"
                "$origin$path"
            }

            DlnaDevice(ip, friendlyName, controlUrl)
        } catch (e: Exception) {
            null
        }
    }

    private fun findAvTransportControlUrl(xml: String): String? {
        // 找 AVTransport service block
        val serviceBlockRegex = Regex(
            "<service>[\\s\\S]*?AVTransport[\\s\\S]*?</service>",
            RegexOption.IGNORE_CASE
        )
        val block = serviceBlockRegex.find(xml)?.value ?: return null
        return Regex("<controlURL>([^<]+)</controlURL>", RegexOption.IGNORE_CASE)
            .find(block)?.groupValues?.get(1)?.trim()?.trimStart('/')
    }

    // ─── 按 IP 探测 DLNA ─────────────────────────────────────────────────────

    /** 根据 IP 探测 TV 的 DLNA 描述，返回设备信息 */
    fun discoverByIp(ip: String): DlnaDevice? {
        val ports = listOf(49494, 80, 8080, 5000, 2869, 1025)
        for (port in ports) {
            val loc = "http://$ip:$port/description.xml"
            try {
                val device = fetchDeviceDescription(loc, ip)
                if (device != null) return device
            } catch (_: Exception) {}
        }
        // 最后尝试从 Web 根抓取并搜索 UPnP URL
        try {
            val req = Request.Builder().url("http://$ip/").build()
            val html = client.newCall(req).execute().use { it.body?.string() ?: "" }
            val descUrl = Regex("""<URLBase>([^<]+)</URLBase>""").find(html)?.groupValues?.get(1)
                ?: Regex("""(https?://[^"'\s]+description\.xml)""").find(html)?.groupValues?.get(1)
            if (descUrl != null) {
                return fetchDeviceDescription(descUrl, ip)
            }
        } catch (_: Exception) {}
        return null
    }

    // ─── AVTransport 投屏 ────────────────────────────────────────────────────

    /**
     * 根据 URL 检测 MIME 类型，用于 DLNA protocolInfo
     */
    private fun detectMimeType(url: String): String {
        val lower = url.lowercase()
        return when {
            lower.contains(".m3u8") || lower.contains("m3u8") -> "video/vnd.apple.mpegurl"
            lower.contains(".mpd") || lower.contains("mpd") -> "application/dash+xml"
            lower.contains(".mp4") -> "video/mp4"
            lower.contains(".ts") || lower.contains("live") || lower.contains("stream") -> "video/vnd.dlna.mpeg-tts"
            lower.contains(".mkv") -> "video/x-matroska"
            lower.contains(".webm") -> "video/webm"
            else -> "video/vnd.dlna.mpeg-tts"
        }
    }

    fun castTo(controlUrl: String, videoUrl: String, title: String = "直播"): Pair<Boolean, String> {
        return tryCast(controlUrl, videoUrl, title)
    }

    /**
     * 智能投屏：HLS 流自动 fallback 到本地 TS 代理
     * @return Triple(success, message, proxy) — proxy 非 null 表示代理正在运行，调用方负责在投屏结束后 stop
     */
    fun castHls(controlUrl: String, videoUrl: String, title: String = "直播"): Triple<Boolean, String, HlsProxyServer?> {
        // 方案 A+B：直接投屏
        val (ok, msg) = tryCast(controlUrl, videoUrl, title)
        if (ok) return Triple(true, msg, null)

        // 方案 C：如果不是 HLS 流，直接返回失败
        val mimeType = detectMimeType(videoUrl)
        if (!mimeType.contains("mpegurl") && !mimeType.contains("hls")) {
            return Triple(false, msg, null)
        }

        // 电视不支持 HLS → 启动本地 TS 代理
        val proxy = HlsProxyServer()
        val localUrl = proxy.start(videoUrl)
        if (localUrl == null) {
            return Triple(false, "无法启动本地流代理（请检查 WiFi 连接）", null)
        }

        val (ok2, msg2) = tryCast(controlUrl, localUrl, "$title (TS 代理)")
        if (ok2) {
            return Triple(true, "已通过本地 TS 代理投屏\n代理地址: $localUrl", proxy)
        } else {
            proxy.stop()
            return Triple(false, "电视拒绝了 TS 代理流: $msg2", null)
        }
    }

    /** 核心投屏逻辑（多 MIME 依次尝试） */
    private fun tryCast(controlUrl: String, videoUrl: String, title: String): Pair<Boolean, String> {
        return try {
            val isTsProxy = videoUrl.contains(":9876") || videoUrl.contains(":8765") || videoUrl.contains(":8088")
            val mimeCandidates = if (isTsProxy) {
                listOf("video/vnd.dlna.mpeg-tts", "video/mp2t", "video/mpeg")
            } else {
                listOf(detectMimeType(videoUrl))
            }

            var lastError = ""
            for (mimeType in mimeCandidates) {
                // 方案 A：带完整 DIDL-Lite 元数据
                val setUriSoap = buildSetAVTransportURI(videoUrl, title, mimeType)
                val r1 = soapPost(controlUrl, "SetAVTransportURI", setUriSoap)
                if (r1.first) {
                    // MIME 匹配成功 → Play
                    val playSoap = buildPlay()
                    return soapPost(controlUrl, "Play", playSoap)
                }

                lastError = r1.second

                // 方案 B：不带元数据的裸 URI
                if (r1.second.startsWith("HTTP 500")) {
                    val rawSoap = buildSetAVTransportURIRaw(videoUrl)
                    val r2 = soapPost(controlUrl, "SetAVTransportURI", rawSoap)
                    if (r2.first) {
                        val playSoap = buildPlay()
                        return soapPost(controlUrl, "Play", playSoap)
                    }
                    lastError = r2.second
                }
            }

            // 所有 MIME 都失败
            Pair(false, "电视拒绝了该流格式（尝试: ${mimeCandidates.joinToString(", ")}）")
        } catch (e: Exception) {
            e.printStackTrace()
            Pair(false, e.message ?: "未知错误")
        }
    }

    fun stopCast(controlUrl: String): Boolean {
        return try {
            val stopSoap = buildStop()
            soapPost(controlUrl, "Stop", stopSoap).first
        } catch (e: Exception) {
            false
        }
    }

    private fun soapPost(url: String, action: String, body: String): Pair<Boolean, String> {
        val req = Request.Builder()
            .url(url)
            .addHeader("Content-Type", "text/xml; charset=\"utf-8\"")
            .addHeader("SOAPAction", "\"urn:schemas-upnp-org:service:AVTransport:1#$action\"")
            .post(body.toRequestBody("text/xml".toMediaType()))
            .build()
        val resp = client.newCall(req).execute()
        return if (resp.isSuccessful) {
            Pair(true, "")
        } else {
            Pair(false, "HTTP ${resp.code}: ${resp.message}")
        }
    }

    private fun buildSetAVTransportURI(uri: String, title: String, mimeType: String) = """<?xml version="1.0"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"
    s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
  <s:Body>
    <u:SetAVTransportURI xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
      <InstanceID>0</InstanceID>
      <CurrentURI>${escapeXml(uri)}</CurrentURI>
      <CurrentURIMetaData>&lt;DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/"
          xmlns:dc="http://purl.org/dc/elements/1.1/"
          xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/"&gt;
        &lt;item id="0" parentID="-1" restricted="0"&gt;
          &lt;dc:title&gt;${escapeXml(title)}&lt;/dc:title&gt;
          &lt;res protocolInfo="http-get:*:${escapeXml(mimeType)}:*" size="0" duration="0:00:00"&gt;${escapeXml(uri)}&lt;/res&gt;
          &lt;upnp:class&gt;object.item.videoItem&lt;/upnp:class&gt;
        &lt;/item&gt;
      &lt;/DIDL-Lite&gt;</CurrentURIMetaData>
    </u:SetAVTransportURI>
  </s:Body>
</s:Envelope>"""

    /** 裸 URI 版本（无 DIDL-Lite，部分电视简洁 DLNA 实现只认这种） */
    private fun buildSetAVTransportURIRaw(uri: String) = """<?xml version="1.0"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"
    s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
  <s:Body>
    <u:SetAVTransportURI xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
      <InstanceID>0</InstanceID>
      <CurrentURI>${escapeXml(uri)}</CurrentURI>
      <CurrentURIMetaData></CurrentURIMetaData>
    </u:SetAVTransportURI>
  </s:Body>
</s:Envelope>"""

    private fun buildPlay() = """<?xml version="1.0"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"
    s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
  <s:Body>
    <u:Play xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
      <InstanceID>0</InstanceID>
      <Speed>1</Speed>
    </u:Play>
  </s:Body>
</s:Envelope>"""

    private fun buildStop() = """<?xml version="1.0"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"
    s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
  <s:Body>
    <u:Stop xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
      <InstanceID>0</InstanceID>
    </u:Stop>
  </s:Body>
</s:Envelope>"""

    private fun escapeXml(s: String) = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
}
