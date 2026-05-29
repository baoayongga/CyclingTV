package com.cyclingtv.app

import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import com.cyclingtv.app.databinding.ActivityPlayerBinding
import com.cyclingtv.app.dlna.DlnaCaster
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 播放器界面：ExoPlayer 本机播放 + DLNA 投屏
 * 默认全屏沉浸，支持 拉伸/适配 切换
 */
class PlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlayerBinding
    private var exoPlayer: ExoPlayer? = null
    private var streamUrl: String = ""
    private var castMode: Boolean = false
    private var isZoomMode: Boolean = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 沉浸式全屏：隐藏状态栏+导航栏
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.hide(WindowInsetsCompat.Type.systemBars())
        insetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        streamUrl = intent.getStringExtra(EXTRA_STREAM_URL) ?: ""
        castMode = intent.getBooleanExtra(EXTRA_CAST_MODE, false)

        if (streamUrl.isBlank()) {
            Toast.makeText(this, "流地址为空", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        initExoPlayer()
        initButtons()
        setupFullscreenToggle()

        if (castMode) showCastOptions()
    }

    private fun initExoPlayer() {
        // 大缓冲区：解决直播流 25 秒卡顿
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs   */ 30_000,   // 最少缓冲 30 秒
                /* maxBufferMs   */ 120_000,  // 最多缓冲 120 秒
                /* playbackMs    */ 5_000,    // 起播需要 5 秒缓冲
                /* rebufferMs    */ 10_000    // 卡顿恢复需要 10 秒
            )
            .build()

        exoPlayer = ExoPlayer.Builder(this)
            .setLoadControl(loadControl)
            .build().also { player ->
            binding.playerView.player = player
            player.setMediaItem(MediaItem.fromUri(streamUrl))
            player.prepare()
            player.playWhenReady = true

            player.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_READY) {
                        binding.progressDlna.visibility = View.GONE
                    }
                }
            })
        }
    }

    private fun initButtons() {
        binding.btnCast.setOnClickListener { showCastOptions() }
        binding.btnResize.setOnClickListener {
            isZoomMode = !isZoomMode
            binding.playerView.resizeMode = if (isZoomMode) {
                AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            } else {
                AspectRatioFrameLayout.RESIZE_MODE_FIT
            }
            binding.btnResize.text = if (isZoomMode) "⛶" else "⊡"
            Toast.makeText(this, if (isZoomMode) "画面拉伸填充" else "画面完整适配", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 点击屏幕 → 切换系统栏可见性，实现真正的全屏切换
     */
    private fun setupFullscreenToggle() {
        binding.playerView.setOnClickListener {
            val insetsController = WindowCompat.getInsetsController(window, window.decorView)
            val systemBars = WindowInsetsCompat.Type.systemBars()
            if (insetsController.isAppearanceLightStatusBars) {
                // 当前是浅色状态栏（非全屏）→ 切到全屏
                insetsController.hide(systemBars)
            } else {
                // 当前全屏 → 显示系统栏
                insetsController.show(systemBars)
            }
        }
    }

    private fun showCastOptions() {
        AlertDialog.Builder(this)
            .setTitle("选择投屏方式")
            .setItems(arrayOf("📺 DLNA 扫描投屏", "✏️ 手动输入电视 IP")) { _, which ->
                when (which) {
                    0 -> scanAndCastDlna()
                    1 -> inputIpAndCast()
                }
            }.show()
    }

    private fun scanAndCastDlna() {
        binding.progressDlna.visibility = View.VISIBLE
        Toast.makeText(this, "正在扫描局域网 DLNA 设备...", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch(Dispatchers.IO) {
            val devices = DlnaCaster.scanDevices()
            withContext(Dispatchers.Main) {
                binding.progressDlna.visibility = View.GONE
                if (devices.isEmpty()) {
                    AlertDialog.Builder(this@PlayerActivity)
                        .setTitle("未发现 DLNA 设备")
                        .setMessage("请确认：\n1. 手机和电视连接同一 WiFi\n2. 电视已开启 DLNA 功能\n\n也可手动输入电视 IP。")
                        .setPositiveButton("手动输入 IP") { _, _ -> inputIpAndCast() }
                        .setNegativeButton("取消", null).show()
                } else {
                    AlertDialog.Builder(this@PlayerActivity)
                        .setTitle("📺 选择电视")
                        .setItems(devices.map { "${it.friendlyName}  (${it.ip})" }.toTypedArray()) { _, idx ->
                            val d = devices[idx]
                            castToDlnaDevice(d.controlUrl, d.ip, d.supportedFormats)
                        }.setNegativeButton("取消", null).show()
                }
            }
        }
    }

    private fun inputIpAndCast() {
        val editText = android.widget.EditText(this).apply {
            hint = "例：192.168.1.100"
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }
        AlertDialog.Builder(this)
            .setTitle("输入电视 IP 地址")
            .setMessage("在电视「设置→网络→网络状态」可查看 IP\n\n输入后会自动探测 DLNA 控制地址")
            .setView(editText)
            .setPositiveButton("探测并投屏") { _, _ ->
                val ip = editText.text.toString().trim()
                if (ip.isNotBlank()) discoverAndCast(ip)
            }.setNegativeButton("取消", null).show()
    }

    private fun discoverAndCast(ip: String) {
        Toast.makeText(this, "正在探测 $ip 的 DLNA 服务...", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch(Dispatchers.IO) {
            val device = DlnaCaster.discoverByIp(ip)
            withContext(Dispatchers.Main) {
                if (device != null) {
                    val hls = device.supportedFormats.any {
                        it.contains("mpegurl", ignoreCase = true) || it.contains("hls", ignoreCase = true)
                    }
                    val info = if (device.supportedFormats.isNotEmpty()) {
                        "支持格式: " + device.supportedFormats.take(3).joinToString(", ") + "..."
                    } else "格式未知"
                    if (hls) {
                        Toast.makeText(this@PlayerActivity, "${device.friendlyName}\n$info", Toast.LENGTH_LONG).show()
                    } else {
                        AlertDialog.Builder(this@PlayerActivity)
                            .setTitle("警告: 电视可能不支持 HLS")
                            .setMessage("${device.friendlyName}\n$info\n\n未检测到 HLS/m3u8 支持，" +
                                "投屏可能失败。是否仍要尝试？")
                            .setPositiveButton("仍然尝试") { _, _ ->
                                castToDlnaDevice(device.controlUrl, ip, device.supportedFormats)
                            }
                            .setNegativeButton("取消", null).show()
                        return@withContext
                    }
                    castToDlnaDevice(device.controlUrl, ip, device.supportedFormats)
                } else {
                    AlertDialog.Builder(this@PlayerActivity)
                        .setTitle("未探测到 DLNA 服务")
                        .setMessage("$ip 上未找到 DLNA/UPnP 服务。\n\n请确认：\n1. 电视和手机连接同一 WiFi\n2. 电视已开启 DLNA 功能\n3. IP 地址正确")
                        .setPositiveButton("自动扫描") { _, _ -> scanAndCastDlna() }
                        .setNegativeButton("取消", null).show()
                }
            }
        }
    }

    private fun castToDlnaDevice(controlUrl: String, ip: String, formats: List<String> = emptyList()) {
        Toast.makeText(this, "正在向 $ip 投屏...", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch(Dispatchers.IO) {
            val (success, errorMsg) = DlnaCaster.castTo(controlUrl, streamUrl, "Cycling Today 直播")
            withContext(Dispatchers.Main) {
                if (success) {
                    Toast.makeText(this@PlayerActivity, "投屏成功！请在电视上查看", Toast.LENGTH_LONG).show()
                    exoPlayer?.pause()
                } else {
                    val detail = if (errorMsg.isNotBlank()) errorMsg else "未知错误"
                    val formatsInfo = if (formats.isNotEmpty()) {
                        "\n\n电视支持格式:\n" + formats.joinToString("\n") { "  / $it" }
                    } else ""
                    AlertDialog.Builder(this@PlayerActivity)
                        .setTitle("投屏失败")
                        .setMessage(
                            "目标: $ip\n\n" +
                            "错误: $detail$formatsInfo\n\n" +
                            "电视 DLNA 通常不支持 HLS/m3u8 直播流。\n" +
                            "建议通过电视浏览器直接打开本页面。"
                        )
                        .setPositiveButton("重试") { _, _ -> castToDlnaDevice(controlUrl, ip, formats) }
                        .setNegativeButton("本机播放") { _, _ -> }
                        .setNeutralButton("取消", null).show()
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        exoPlayer?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        exoPlayer?.release()
    }

    companion object {
        const val EXTRA_STREAM_URL = "stream_url"
        const val EXTRA_CAST_MODE = "cast_mode"
    }
}
