package com.cyclingtv.app.dlna

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.OutputStream
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 本地 HLS → TS 串流代理
 *
 * 场景：电视 DLNA 芯片不支持 m3u8/HLS，只认 video/mp2t (TS) 格式。
 * 本代理在手机上解析 m3u8 播放列表，把 TS 片段逐一下载并用 HTTP 串流推给电视。
 *
 * 用法：
 *   val proxy = HlsProxyServer()
 *   val localUrl = proxy.start(hlsUrl)    // → "http://192.168.1.5:9876"
 *   DlnaCaster.castTo(tvUrl, localUrl, "环意 Stage 19")
 *   // ... 用户停止投屏时 ...
 *   proxy.stop()
 */
class HlsProxyServer {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private var serverSocket: ServerSocket? = null
    private var running = false
    private var port: Int = -1

    /** 已推送过的 TS 片段 URL，避免直播流重复推送 */
    private val seenSegments = ConcurrentHashMap.newKeySet<String>()

    // ─── 公开 API ─────────────────────────────────────────────────────────────

    /**
     * 启动代理服务器
     * @param hlsUrl 远程 m3u8 播放列表地址
     * @return 返回本地 TS 流地址，如 "http://192.168.1.5:8765"；失败返回 null
     */
    fun start(hlsUrl: String): String? {
        stop()
        return try {
            serverSocket = ServerSocket(0)
            port = serverSocket!!.localPort
            running = true
            seenSegments.clear()

            val ip = getLocalIpAddress()
            if (ip == "127.0.0.1") {
                stop()
                return null
            }

            Thread({
                while (running) {
                    try {
                        val socket = serverSocket!!.accept()
                        // 每个 TV 连接开一个线程处理
                        Thread({ handleClient(socket, hlsUrl) }, "hls-proxy-client").start()
                    } catch (_: Exception) {
                        if (running) Thread.sleep(200) // 短暂休眠后重试
                        else break
                    }
                }
            }, "hls-proxy-accept").start()

            "http://$ip:$port"
        } catch (e: Exception) {
            e.printStackTrace()
            stop()
            null
        }
    }

    /** 停止代理，关闭所有连接 */
    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        port = -1
        seenSegments.clear()
    }

    // ─── 客户端处理 ───────────────────────────────────────────────────────────

    private fun handleClient(socket: Socket, hlsUrl: String) {
        try {
            val reader = socket.getInputStream().bufferedReader()
            val requestLine = reader.readLine() ?: return
            val isHead = requestLine.uppercase().startsWith("HEAD")
            val isGet = requestLine.uppercase().startsWith("GET")

            if (!isHead && !isGet) {
                send405(socket)
                return
            }

            // 消耗完请求头
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
            }

            val out = socket.getOutputStream()

            if (isHead) {
                val header = "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: video/vnd.dlna.mpeg-tts\r\n" +
                        "Accept-Ranges: bytes\r\n" +
                        "Connection: close\r\n\r\n"
                out.write(header.toByteArray())
                out.close()
                return
            }

            // GET：解析 m3u8 → 获取 TS 片段列表
            val (baseUrl, segments) = fetchM3u8Segments(hlsUrl)
            val firstSegments = segments.take(5)

            // HTTP 响应头 — Vidda/Hisense 认 video/vnd.dlna.mpeg-tts
            val header = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: video/vnd.dlna.mpeg-tts\r\n" +
                    "Transfer-Encoding: chunked\r\n" +
                    "Accept-Ranges: bytes\r\n" +
                    "Connection: close\r\n" +
                    "Cache-Control: no-cache\r\n\r\n"
            out.write(header.toByteArray())

            // 先用预取的片段快速填充
            for (segUrl in firstSegments) {
                if (!running) break
                val data = downloadSegment(segUrl) ?: continue
                seenSegments.add(segUrl)
                writeChunked(out, data)
            }

            // 持续拉取新片段
            var lastRefresh = System.currentTimeMillis()
            while (running) {
                val elapsed = System.currentTimeMillis() - lastRefresh
                if (elapsed < 4000) {
                    Thread.sleep(4000 - elapsed)
                }
                lastRefresh = System.currentTimeMillis()

                val (_, newSegments) = fetchM3u8Segments(hlsUrl)
                var wrote = false
                for (segUrl in newSegments) {
                    if (!running) break
                    if (seenSegments.contains(segUrl)) continue
                    val data = downloadSegment(segUrl) ?: continue
                    seenSegments.add(segUrl)
                    writeChunked(out, data)
                    wrote = true
                }
                out.flush()

                if (!wrote && newSegments.isEmpty()) {
                    Thread.sleep(3000)
                    break
                }
            }
            writeChunked(out, byteArrayOf()) // 结束 chunk
            out.close()
        } catch (_: Exception) {
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun writeChunked(out: OutputStream, data: ByteArray) {
        val hexLen = data.size.toString(16)
        out.write("$hexLen\r\n".toByteArray())
        if (data.isNotEmpty()) out.write(data)
        out.write("\r\n".toByteArray())
    }

    private fun send405(socket: Socket) {
        try {
            val resp = "HTTP/1.1 405 Method Not Allowed\r\nConnection: close\r\n\r\n"
            socket.getOutputStream().write(resp.toByteArray())
        } catch (_: Exception) {}
    }

    // ─── m3u8 解析 ────────────────────────────────────────────────────────────

    /**
     * 获取 m3u8 播放列表，返回 (baseUrl, segmentUrls)。
     * baseUrl 用于解析相对路径片段地址。
     */
    private fun fetchM3u8Segments(m3u8Url: String): Pair<String, List<String>> {
        return try {
            val resp = client.newCall(Request.Builder().url(m3u8Url).build()).execute()
            val body = resp.body?.string() ?: return Pair(m3u8Url, emptyList())
            val lines = body.lines().map { it.trim() }
            val segments = mutableListOf<String>()

            // baseUrl：去掉最后一个 / 后面的部分
            val base = m3u8Url.substringBeforeLast("/") + "/"

            for (line in lines) {
                if (line.isEmpty() || line.startsWith("#")) continue
                // TS 片段 URL
                val segUrl = if (line.startsWith("http")) line else base + line
                segments.add(segUrl)
            }

            Pair(base, segments)
        } catch (e: Exception) {
            Pair(m3u8Url, emptyList())
        }
    }

    /** 下载单个 TS 片段 */
    private fun downloadSegment(url: String): ByteArray? {
        return try {
            client.newCall(Request.Builder().url(url).build())
                .execute().use { it.body?.bytes() }
        } catch (_: Exception) {
            null
        }
    }

    // ─── 网络工具 ─────────────────────────────────────────────────────────────

    companion object {
        /** 获取本机局域网 IPv4 地址 */
        fun getLocalIpAddress(): String {
            return try {
                val interfaces = NetworkInterface.getNetworkInterfaces()
                while (interfaces.hasMoreElements()) {
                    val iface = interfaces.nextElement()
                    if (iface.isLoopback || !iface.isUp) continue
                    val addresses = iface.inetAddresses
                    while (addresses.hasMoreElements()) {
                        val addr = addresses.nextElement()
                        if (addr.isLoopbackAddress) continue
                        if (addr.hostAddress?.contains(":") == true) continue // 跳过 IPv6
                        return addr.hostAddress ?: continue
                    }
                }
                "127.0.0.1"
            } catch (_: Exception) {
                "127.0.0.1"
            }
        }
    }
}
