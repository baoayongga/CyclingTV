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
        val controlUrl: String
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

    // ─── AVTransport 投屏 ────────────────────────────────────────────────────

    fun castTo(controlUrl: String, videoUrl: String, title: String = "直播"): Boolean {
        return try {
            // Step 1: SetAVTransportURI
            val setUriSoap = buildSetAVTransportURI(videoUrl, title)
            val r1 = soapPost(controlUrl, "SetAVTransportURI", setUriSoap)
            if (!r1) return false

            // Step 2: Play
            val playSoap = buildPlay()
            soapPost(controlUrl, "Play", playSoap)
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun stopCast(controlUrl: String): Boolean {
        return try {
            val stopSoap = buildStop()
            soapPost(controlUrl, "Stop", stopSoap)
        } catch (e: Exception) {
            false
        }
    }

    private fun soapPost(url: String, action: String, body: String): Boolean {
        val req = Request.Builder()
            .url(url)
            .addHeader("Content-Type", "text/xml; charset=\"utf-8\"")
            .addHeader("SOAPAction", "\"urn:schemas-upnp-org:service:AVTransport:1#$action\"")
            .post(body.toRequestBody("text/xml".toMediaType()))
            .build()
        val resp = client.newCall(req).execute()
        return resp.isSuccessful
    }

    private fun buildSetAVTransportURI(uri: String, title: String) = """<?xml version="1.0"?>
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
          &lt;res protocolInfo="http-get:*:video/mpeg:*"&gt;${escapeXml(uri)}&lt;/res&gt;
          &lt;upnp:class&gt;object.item.videoItem&lt;/upnp:class&gt;
        &lt;/item&gt;
      &lt;/DIDL-Lite&gt;</CurrentURIMetaData>
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
