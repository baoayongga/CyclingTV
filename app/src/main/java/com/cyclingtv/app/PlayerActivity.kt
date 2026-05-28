package com.cyclingtv.app

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.cast.CastPlayer
import androidx.media3.cast.SessionAvailabilityListener
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.cyclingtv.app.databinding.ActivityPlayerBinding
import com.cyclingtv.app.dlna.DlnaCaster
import com.google.android.gms.cast.framework.CastButtonFactory
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastState
import com.google.android.gms.cast.framework.CastStateListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 播放器界面：
 * - ExoPlayer 本机播放（支持 HLS/DASH/MP4）
 * - Google Cast 投屏（Chromecast / Google TV）
 * - DLNA 投屏（小米/海信/三星等普通智能电视）
 */
class PlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlayerBinding
    private var exoPlayer: ExoPlayer? = null
    private var castPlayer: CastPlayer? = null
    private var castContext: CastContext? = null
    private var streamUrl: String = ""
    private var castMode: Boolean = false

    private val castStateListener = CastStateListener { state ->
        if (state == CastState.CONNECTED) {
            switchToCastPlayer()
        } else if (state == CastState.NOT_CONNECTED || state == CastState.NO_DEVICES_AVAILABLE) {
            switchToExoPlayer()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        streamUrl = intent.getStringExtra(EXTRA_STREAM_URL) ?: ""
        castMode = intent.getBooleanExtra(EXTRA_CAST_MODE, false)

        if (streamUrl.isBlank()) {
            Toast.makeText(this, "流地址为空", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // 初始化 Cast
        try {
            castContext = CastContext.getSharedInstance(this)
        } catch (_: Throwable) {}


        initExoPlayer()
        initCastButton()

        if (castMode) {
            // 用户选择了投屏模式：先展示选择弹窗
            showCastOptions()
        }
    }

    private fun initExoPlayer() {
        exoPlayer = ExoPlayer.Builder(this).build().also { player ->
            binding.playerView.player = player
            val mediaItem = MediaItem.fromUri(streamUrl)
            player.setMediaItem(mediaItem)
            player.prepare()
            player.playWhenReady = true
        }
    }

    private fun initCastButton() {
        try {
            CastButtonFactory.setUpMediaRouteButton(
                applicationContext,
                binding.btnCast
            )
        } catch (_: Throwable) {
            binding.btnCast.visibility = View.GONE
        }
        binding.btnCast.setOnClickListener {
            showCastOptions()
        }
    }


    private fun showCastOptions() {
        val options = arrayOf(
            "📡 Google Cast（Chromecast / Google TV）",
            "📺 DLNA 投屏（小米 / 海信 / 三星 / 索尼电视）",
            "✏️ 手动输入电视 IP（DLNA）"
        )
        AlertDialog.Builder(this)
            .setTitle("选择投屏方式")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> startGoogleCast()
                    1 -> scanAndCastDlna()
                    2 -> inputIpAndCast()
                }
            }
            .show()
    }

    // ─── Google Cast ──────────────────────────────────────────────────────────

    private fun startGoogleCast() {
        val ctx = castContext
        if (ctx == null) {
            Toast.makeText(this, "Cast 初始化失败，请检查 Google Play 服务", Toast.LENGTH_SHORT).show()
            return
        }
        if (ctx.castState == CastState.CONNECTED) {
            switchToCastPlayer()
        } else {
            // 弹出设备选择器
            binding.btnCast.performClick()
            Toast.makeText(this, "请在弹出的列表中选择您的电视", Toast.LENGTH_LONG).show()
            ctx.addCastStateListener(castStateListener)
        }
    }

    private fun switchToCastPlayer() {
        val ctx = castContext ?: return
        if (castPlayer == null) {
            castPlayer = CastPlayer(ctx).apply {
                setSessionAvailabilityListener(object : SessionAvailabilityListener {
                    override fun onCastSessionAvailable() {
                        switchToCastPlayer()
                    }
                    override fun onCastSessionUnavailable() {
                        switchToExoPlayer()
                    }
                })
            }
        }
        castPlayer?.let { cp ->
            binding.playerView.player = cp
            exoPlayer?.stop()
            val mediaItem = MediaItem.fromUri(streamUrl)
            cp.setMediaItem(mediaItem)
            cp.prepare()
            cp.playWhenReady = true
            Toast.makeText(this, "🎉 已投屏到电视！", Toast.LENGTH_SHORT).show()
        }
    }

    private fun switchToExoPlayer() {
        binding.playerView.player = exoPlayer
        Toast.makeText(this, "已切换到本机播放", Toast.LENGTH_SHORT).show()
    }

    // ─── DLNA 投屏 ───────────────────────────────────────────────────────────

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
                        .setMessage("请确认：\n1. 手机和电视连接同一 WiFi\n2. 电视已开启 DLNA 功能\n\n也可手动输入电视 IP 投屏。")
                        .setPositiveButton("手动输入 IP") { _, _ -> inputIpAndCast() }
                        .setNegativeButton("取消", null)
                        .show()
                } else {
                    val names = devices.map { "${it.friendlyName}  (${it.ip})" }.toTypedArray()
                    AlertDialog.Builder(this@PlayerActivity)
                        .setTitle("📺 选择电视")
                        .setItems(names) { _, idx ->
                            castToDlnaDevice(devices[idx].controlUrl, devices[idx].ip)
                        }
                        .setNegativeButton("取消", null)
                        .show()
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
            .setMessage("在电视「设置→网络→网络状态」可查看 IP")
            .setView(editText)
            .setPositiveButton("投屏") { _, _ ->
                val ip = editText.text.toString().trim()
                if (ip.isNotBlank()) {
                    // 尝试常见端口
                    val controlUrl = "http://$ip:7676/dmr/control/AVTransport1"
                    castToDlnaDevice(controlUrl, ip)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun castToDlnaDevice(controlUrl: String, ip: String) {
        Toast.makeText(this, "正在向 $ip 投屏...", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch(Dispatchers.IO) {
            val success = DlnaCaster.castTo(controlUrl, streamUrl, "Cycling Today 直播")
            withContext(Dispatchers.Main) {
                if (success) {
                    Toast.makeText(this@PlayerActivity, "🎉 投屏成功！请在电视上查看", Toast.LENGTH_LONG).show()
                    // 停止本机播放，省电
                    exoPlayer?.pause()
                } else {
                    AlertDialog.Builder(this@PlayerActivity)
                        .setTitle("投屏失败")
                        .setMessage("无法连接到 $ip\n\n请检查：\n• 电视是否开启 DLNA\n• IP 地址是否正确\n• 防火墙是否阻止")
                        .setPositiveButton("重试") { _, _ -> inputIpAndCast() }
                        .setNegativeButton("取消", null)
                        .show()
                }
            }
        }
    }

    // ─── 生命周期 ────────────────────────────────────────────────────────────

    override fun onPause() {
        super.onPause()
        exoPlayer?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        exoPlayer?.release()
        castPlayer?.release()
        castContext?.removeCastStateListener(castStateListener)
    }

    companion object {
        const val EXTRA_STREAM_URL = "stream_url"
        const val EXTRA_CAST_MODE = "cast_mode"
    }
}
