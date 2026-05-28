package com.cyclingtv.app.dlna

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.SocketTimeoutException
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * DLNA/UPnP 投屏工具 v4
 *
 * v4 新增：
 * - 降级重试：先试完整 metadata → 失败则试空 metadata
 * - 多 protocolInfo 回退：apple.mpegurl → video/mpeg
 * - 超时调整为 15s，某些电视处理慢
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
        val controlUrl: String
    )

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
                        Log.d(TAG, "发现设备: ${device.friendlyName} @ ${device.ip}")
                    }
                } catch (_: SocketTimeoutException) {
                    break
                }
            }
            socket.close()
        } catch (e: Exception) {
            Log.e(TAG, "SSDP 扫描异常", e)
        }
        return devices
    }

    private fun fetchDeviceInfo(locationUrl: String, ip: String): DlnaDevice? {
        return try {
            val req = Request.Builder().url(locationUrl).build()
            val xml = client.newCall(req).execute().use { it.body?.string() ?: return null }

            val nameMatch = Regex("<friendlyName>([^<]+)</friendlyName>").find(xml)
            val friendlyName = nameMatch?.groupValues?.get(1) ?: "电视 ($ip)"

            val controlPath = extractAvTransportUrl(xml) ?: return null
            val controlUrl = resolveUrl(locationUrl, controlPath)

            DlnaDevice(ip, friendlyName, controlUrl)
        } catch (e: Exception) {
            Log.w(TAG, "获取 $ip 设备信息失败: ${e.message}")
            null
        }
    }

    private fun extractAvTransportUrl(xml: String): String? {
        val block = Regex(
            "<service>[\\s\\S]*?AVTransport[\\s\\S]*?</service>",
            RegexOption.IGNORE_CASE
        ).find(xml)?.value ?: return null

        return Regex("<controlURL>([^<]+)</controlURL>", RegexOption.IGNORE_CASE)
            .find(block)?.groupValues?.get(1)?.trim()
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

    // ─── AVTransport 投屏（带降级重试）────────────────────────────────────────────

    /**
     * 向指定 DLNA 设备推送视频流。
     * 若初次失败，自动尝试降级策略（空 metadata、不同 protocolInfo）。
     *
     * @return Pair<Boolean, String> — (是否成功, 错误描述)
     */
    fun castTo(controlUrl: String, videoUrl: String, title: String = "直播"): Pair<Boolean, String> {
        // 策略 1：完整 metadata + 自动 protocolInfo
        val (ok1, err1) = tryCast(controlUrl, videoUrl, title, useMetadata = true)
        if (ok1) return Pair(true, "")

        Log.w(TAG, "策略1 失败 ($err1)，尝试降级策略2（空 metadata）")

        // 策略 2：空 metadata（某些电视对 DIDL-Lite XML 敏感）
        val (ok2, err2) = tryCast(controlUrl, videoUrl, title, useMetadata = false)
        if (ok2) return Pair(true, "")

        Log.w(TAG, "策略2 失败 ($err2)，尝试降级策略3（video/mpeg）")

        // 策略 3：空 metadata + 强制 video/mpeg
        val (ok3, err3) = tryCastWithProtocol(controlUrl, videoUrl, title,
            useMetadata = false, protocolInfo = "http-get:*:video/mpeg:*")
        if (ok3) return Pair(true, "")

        // 全部失败，返回综合错误
        return Pair(false, "SetAVTransportURI 失败: $err1\n\n" +
                "降级策略也失败。若电视型号较老，可能不支持 HLS 流。" +
                "\n建议：检查电视能否直接访问该流地址。")
    }

    /**
     * 单次投屏尝试
     */
    private fun tryCast(
        controlUrl: String,
        videoUrl: String,
        title: String,
        useMetadata: Boolean
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
            else ->
                "http-get:*:video/mpeg:*"
        }
        return tryCastWithProtocol(controlUrl, videoUrl, title, useMetadata, protocolInfo)
    }

    /**
     * 使用指定 protocolInfo 投屏
     */
    private fun tryCastWithProtocol(
        controlUrl: String,
        videoUrl: String,
        title: String,
        useMetadata: Boolean,
        protocolInfo: String
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
        try {
            val (ok, _) = soapCall(controlUrl, "Stop", buildStop())
            return ok
        } catch (e: Exception) {
            return false
        }
    }

    /**
     * 发送 SOAP 请求，返回 Pair(成功, 错误描述)
     */
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
                append("UPnP 错误 $code: $str")
                if (upnpErr != null) append("\n错误码: $upnpErr")
                if (upnpDesc != null) append(" ($upnpDesc)")
                // 解析常见 UPnP 错误码
                when (upnpErr) {
                    "402" -> append("\n→ 该电视不支持此媒体格式，请尝试其他流")
                    "701" -> append("\n→ 转换不可用")
                    "714" -> append("\n→ 该电视不支持此媒体格式（ILLEGAL_MIME_TYPE）")
                }
            }

            Log.e(TAG, "SOAP Fault ($action): $detail")
            return Pair(false, detail)
        }

        if (!resp.isSuccessful) {
            return Pair(false, "HTTP ${resp.code}")
        }

        return Pair(true, respBody)
    }

    // ─── SOAP 模板 ───────────────────────────────────────────────────────────────

    private fun buildSetUri(
        uri: String,
        title: String,
        useMetadata: Boolean,
        protocolInfo: String
    ): String {
        val metaDataXml = if (useMetadata) {
            esc(buildMetaXml(title, uri, protocolInfo))
        } else {
            // 空 metadata — 某些老电视需要
            ""
        }

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

    private fun esc(s: String) = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
}
