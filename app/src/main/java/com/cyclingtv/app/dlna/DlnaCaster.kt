package com.cyclingtv.app.dlna

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.*
import java.util.concurrent.TimeUnit

/**
 * DLNA/UPnP 投屏工具 v5
 *
 * v5 新增：
 * - 自动发现 AVTransport 控制 URL（抓取设备描述 XML）
 * - GetProtocolInfo 查询电视支持的媒体格式
 * - 多控制路径自动尝试
 * - 更详细的错误诊断
 */
object DlnaCaster {

    private const val TAG = "DlnaCaster"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    // ─── SSDP 扫描 ───────────────────────────────────────────────────────────────

    private const val SSDP_ADDR = "239.255.255.250"
    private const val SSDP_PORT = 1900
    private const val SSDP_TIMEOUT_MS = 5000

    private val SSDP_SEARCH = "M-SEARCH * HTTP/1.1\r\n" +
            "HOST: 239.255.255.250:1900\r\n" +
            "MAN: \"ssdp:discover\"\r\n" +
            "MX: 3\r\n" +
            "ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n"

    data class DlnaDevice(
        val ip: String,
        val friendlyName: String,
        val controlUrl: String,
        val supportedFormats: List<String> = emptyList()
    )

    // ─── 设备发现 ───────────────────────────────────────────────────────────────

    fun scanDevices(): List<DlnaDevice> {
        val devices = mutableListOf<DlnaDevice>()
        try {
            val socket = MulticastSocket().apply { soTimeout = SSDP_TIMEOUT_MS }
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

                    val locMatch = Regex("LOCATION:\\s*(\\S+)", RegexOption.IGNORE_CASE)
                        .find(response)
                    val loc = locMatch?.groupValues?.get(1) ?: continue

                    val device = fetchDeviceInfo(loc, ip)
                    if (device != null && devices.none { it.ip == ip }) {
                        devices.add(device)
                        Log.d(TAG, "发现: ${device.friendlyName} @ ${device.ip}")
                    }
                } catch (_: SocketTimeoutException) { break }
            }
            socket.close()
        } catch (e: Exception) {
            Log.e(TAG, "SSDP 扫描异常", e)
        }
        return devices
    }

    /**
     * 通过 IP 自动发现控制 URL。
     * 尝试常见端口和路径获取设备描述 XML，从中提取 AVTransport 控制地址。
     */
    fun discoverByIp(ip: String): DlnaDevice? {
        // 常见 UPnP 描述 XML 位置
        val candidates = listOf(
            "http://$ip:7676/description.xml",
            "http://$ip:7676/dmr/description.xml",
            "http://$ip:49494/description.xml",
            "http://$ip:5000/description.xml",
            "http://$ip:38520/description.xml",
            "http://$ip:8080/description.xml",
            "http://$ip:80/description.xml",
            // 小米电视
            "http://$ip:6095/description.xml",
            // 海信电视
            "http://$ip:52323/description.xml",
            // 创维/酷开
            "http://$ip:8060/description.xml"
        )

        for (candidateUrl in candidates) {
            try {
                val req = Request.Builder().url(candidateUrl)
                    .header("User-Agent", "Android/13 UPnP/1.1")
                    .build()
                val resp = client.newCall(req).execute()
                if (resp.isSuccessful) {
                    val xml = resp.body?.string() ?: continue
                    val device = parseDeviceInfo(xml, candidateUrl, ip)
                    if (device != null) {
                        Log.d(TAG, "发现 $ip → ${device.controlUrl}")
                        return device
                    }
                }
            } catch (_: Exception) { }
        }

        // 兜底：尝试常用默认路径
        val defaultPaths = listOf(
            "http://$ip:7676/dmr/control/AVTransport1",
            "http://$ip:49494/AVTransport/control",
            "http://$ip:5000/AVTransport/control"
        )
        for (path in defaultPaths) {
            if (testControlUrl(path)) {
                return DlnaDevice(ip, "电视 ($ip)", path)
            }
        }

        return null
    }

    private fun fetchDeviceInfo(locationUrl: String, ip: String): DlnaDevice? {
        return try {
            val req = Request.Builder().url(locationUrl)
                .header("User-Agent", "Android/13 UPnP/1.1")
                .build()
            val xml = client.newCall(req).execute().use { it.body?.string() ?: return null }
            parseDeviceInfo(xml, locationUrl, ip)
        } catch (e: Exception) {
            Log.w(TAG, "获取 $ip 设备信息失败: ${e.message}")
            null
        }
    }

    private fun parseDeviceInfo(xml: String, baseUrl: String, ip: String): DlnaDevice? {
        val nameMatch = Regex("<friendlyName>([^<]+)</friendlyName>", RegexOption.IGNORE_CASE)
            .find(xml)
        val friendlyName = nameMatch?.groupValues?.get(1) ?: "电视 ($ip)"

        // 提取 AVTransport 服务
        val avTransportBlock = Regex(
            "<service>[\\s\\S]*?AVTransport[\\s\\S]*?</service>",
            RegexOption.IGNORE_CASE
        ).find(xml)?.value

        if (avTransportBlock == null) {
            Log.w(TAG, "$ip 未找到 AVTransport 服务")
            return null
        }

        val controlPath = Regex("<controlURL>([^<]+)</controlURL>", RegexOption.IGNORE_CASE)
            .find(avTransportBlock)?.groupValues?.get(1)?.trim() ?: return null

        val controlUrl = resolveUrl(baseUrl, controlPath)

        // 检查支持的格式
        val formats = mutableListOf<String>()
        val connBlock = Regex(
            "<service>[\\s\\S]*?ConnectionManager[\\s\\S]*?</service>",
            RegexOption.IGNORE_CASE
        ).find(xml)?.value

        if (connBlock != null) {
            val connControl = Regex("<controlURL>([^<]+)</controlURL>", RegexOption.IGNORE_CASE)
                .find(connBlock)?.groupValues?.get(1)?.trim()
            if (connControl != null) {
                val connUrl = resolveUrl(baseUrl, connControl)
                formats.addAll(getSupportedFormats(connUrl))
            }
        }

        return DlnaDevice(ip, friendlyName, controlUrl, formats)
    }

    private fun resolveUrl(baseUrl: String, path: String): String {
        return try {
            val uri = URI(baseUrl)
            URI(uri.scheme, uri.userInfo, uri.host, uri.port,
                if (path.startsWith("/")) path else "/$path",
                null, null
            ).toString()
        } catch (e: Exception) {
            if (path.startsWith("http")) path
            else baseUrl.trimEnd('/') + "/" + path.trimStart('/')
        }
    }

    private fun testControlUrl(url: String): Boolean {
        return try {
            val body = buildGetVolume()
            val req = Request.Builder()
                .url(url)
                .addHeader("Content-Type", "text/xml; charset=\"utf-8\"")
                .addHeader("SOAPAction", "\"urn:schemas-upnp-org:service:RenderingControl:1#GetVolume\"")
                .post(body.toRequestBody("text/xml".toMediaType()))
                .build()
            val resp = client.newCall(req).execute()
            resp.isSuccessful || resp.body?.string()?.contains("GetVolumeResponse") == true
        } catch (_: Exception) { false }
    }

    /**
     * 查询电视支持的媒体格式
     */
    fun getSupportedFormats(connManagerUrl: String): List<String> {
        val formats = mutableListOf<String>()
        try {
            val body = """<?xml version="1.0"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"
    s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
  <s:Body>
    <u:GetProtocolInfo xmlns:u="urn:schemas-upnp-org:service:ConnectionManager:1"/>
  </s:Body>
</s:Envelope>"""
            val req = Request.Builder()
                .url(connManagerUrl)
                .addHeader("Content-Type", "text/xml; charset=\"utf-8\"")
                .addHeader("SOAPAction", "\"urn:schemas-upnp-org:service:ConnectionManager:1#GetProtocolInfo\"")
                .post(body.toRequestBody("text/xml".toMediaType()))
                .build()
            val resp = client.newCall(req).execute()
            val xml = resp.body?.string() ?: return formats

            // 提取 Sink 协议信息
            val sink = Regex("<Sink>(.*?)</Sink>", RegexOption.DOT_MATCHES_ALL).find(xml)
            if (sink != null) {
                val protocols = sink.groupValues[1].split(",")
                for (p in protocols) {
                    val trimmed = p.trim()
                    if (trimmed.isNotBlank()) formats.add(trimmed)
                }
            }
        } catch (_: Exception) { }
        return formats
    }

    // ─── AVTransport 投屏（带降级重试）────────────────────────────────────────────

    fun castTo(controlUrl: String, videoUrl: String, title: String = "直播"): Pair<Boolean, String> {
        val errors = mutableListOf<String>()

        // 策略 1：完整 metadata + 自动 protocolInfo
        val (ok1, err1) = tryCast(controlUrl, videoUrl, title, useMetadata = true)
        if (ok1) return Pair(true, "")
        errors.add("策略1(完整metadata): $err1")

        // 策略 2：空 metadata
        val (ok2, err2) = tryCast(controlUrl, videoUrl, title, useMetadata = false)
        if (ok2) return Pair(true, "")
        errors.add("策略2(空metadata): $err2")

        // 策略 3：空 metadata + video/mpeg
        val (ok3, err3) = tryCastWithProtocol(controlUrl, videoUrl, title,
            useMetadata = false, protocolInfo = "http-get:*:video/mpeg:*")
        if (ok3) return Pair(true, "")
        errors.add("策略3(video/mpeg): $err3")

        return Pair(false, errors.joinToString("\n"))
    }

    private fun tryCast(
        controlUrl: String, videoUrl: String, title: String, useMetadata: Boolean
    ): Pair<Boolean, String> {
        val protocolInfo = when {
            videoUrl.contains(".m3u8", ignoreCase = true) ->
                "http-get:*:application/vnd.apple.mpegurl:*"
            videoUrl.contains(".mpd", ignoreCase = true) ->
                "http-get:*:application/dash+xml:*"
            videoUrl.contains(".mp4", ignoreCase = true) ->
                "http-get:*:video/mp4:*"
            videoUrl.contains(".ts", ignoreCase = true) ->
                "http-get:*:video/mp2t:*"
            else -> "http-get:*:video/mpeg:*"
        }
        return tryCastWithProtocol(controlUrl, videoUrl, title, useMetadata, protocolInfo)
    }

    private fun tryCastWithProtocol(
        controlUrl: String, videoUrl: String, title: String,
        useMetadata: Boolean, protocolInfo: String
    ): Pair<Boolean, String> {
        return try {
            val setUriBody = buildSetUri(videoUrl, title, useMetadata, protocolInfo)
            val (ok1, err1) = soapCall(controlUrl, "SetAVTransportURI", setUriBody)
            if (!ok1) return Pair(false, err1)

            val (ok2, err2) = soapCall(controlUrl, "Play", buildPlay())
            if (!ok2) return Pair(false, "Play 失败: $err2")

            Pair(true, "")
        } catch (e: Exception) {
            Log.e(TAG, "投屏异常", e)
            Pair(false, e.message ?: "未知错误")
        }
    }

    fun stopCast(controlUrl: String): Boolean {
        return try {
            val (ok, _) = soapCall(controlUrl, "Stop", buildStop())
            ok
        } catch (_: Exception) { false }
    }

    // ─── SOAP 调用 ─────────────────────────────────────────────────────────────

    private fun soapCall(url: String, action: String, body: String): Pair<Boolean, String> {
        val req = Request.Builder()
            .url(url)
            .addHeader("Content-Type", "text/xml; charset=\"utf-8\"")
            .addHeader("SOAPAction", "\"urn:schemas-upnp-org:service:AVTransport:1#$action\"")
            .post(body.toRequestBody("text/xml".toMediaType()))
            .build()

        val resp = client.newCall(req).execute()
        val respBody = resp.body?.string() ?: ""

        // 检测 SOAP Fault
        val faultCode = Regex("<faultcode>([^<]+)</faultcode>").find(respBody)
        val faultStr = Regex("<faultstring>([^<]+)</faultstring>").find(respBody)
        val errCode = Regex("<errorCode>(\\d+)</errorCode>").find(respBody)
        val errDesc = Regex("<errorDescription>([^<]+)</errorDescription>").find(respBody)

        if (faultCode != null) {
            val code = faultCode.groupValues[1]
            val str = faultStr?.groupValues?.get(1) ?: "无详情"
            val upnpErr = errCode?.groupValues?.get(1)
            val upnpDesc = errDesc?.groupValues?.get(1)

            val detail = buildString {
                append("UPnP $code: $str")
                if (upnpErr != null) append(" | 错误码: $upnpErr")
                if (upnpDesc != null) append(" ($upnpDesc)")
                when (upnpErr) {
                    "402" -> append("\n  → 电视不支持此媒体格式")
                    "701" -> append("\n  → 转换不可用")
                    "714" -> append("\n  → 不支持的 MIME 类型")
                    "801" -> append("\n  → 无法连接媒体源")
                }
            }
            return Pair(false, detail)
        }

        if (!resp.isSuccessful) {
            return Pair(false, "HTTP ${resp.code}: ${resp.message}")
        }

        return Pair(true, respBody)
    }

    // ─── SOAP 模板 ───────────────────────────────────────────────────────────

    private fun buildSetUri(uri: String, title: String, useMetadata: Boolean, protocolInfo: String): String {
        val metaDataXml = if (useMetadata) esc(buildMetaXml(title, uri, protocolInfo)) else ""
        return """<?xml version="1.0"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"
    s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
  <s:Body>
    <u:SetAVTransportURI xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
      <InstanceID>0</InstanceID>
      <CurrentURI>${esc(uri)}</CurrentURI>
      <CurrentURIMetaData>$metaDataXml</CurrentURIMetaData>
    </u:SetAVTransportURI>
  </s:Body>
</s:Envelope>"""
    }

    private fun buildMetaXml(title: String, uri: String, protocolInfo: String) =
        """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/"
          xmlns:dc="http://purl.org/dc/elements/1.1/"
          xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/">
        <item id="0" parentID="-1" restricted="0">
          <dc:title>${esc(title)}</dc:title>
          <res protocolInfo="$protocolInfo">${esc(uri)}</res>
          <upnp:class>object.item.videoItem</upnp:class>
        </item>
      </DIDL-Lite>"""

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

    private fun buildGetVolume() = """<?xml version="1.0"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"
    s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
  <s:Body>
    <u:GetVolume xmlns:u="urn:schemas-upnp-org:service:RenderingControl:1">
      <InstanceID>0</InstanceID>
      <Channel>Master</Channel>
    </u:GetVolume>
  </s:Body>
</s:Envelope>"""

    private fun esc(s: String) = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
}
