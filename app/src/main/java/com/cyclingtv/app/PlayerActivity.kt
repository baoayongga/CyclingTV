package com.cyclingtv.app

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import com.cyclingtv.app.databinding.ActivityPlayerBinding
import com.cyclingtv.app.dlna.DlnaCaster
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 播放器界面：ExoPlayer 本机播放 + DLNA 投屏
 */
class PlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlayerBinding
    private var exoPlayer: ExoPlayer? = null
    private var streamUrl: String = ""
    private var castMode: Boolean = false

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

        initExoPlayer()
        initCastButton()

        if (castMode) showCastOptions()
    }

    private fun initExoPlayer() {
        exoPlayer = ExoPlayer.Builder(this).build().also { player ->
            binding.playerView.player = player
            player.setMediaItem(MediaItem.fromUri(streamUrl))
            player.prepare()
            player.playWhenReady = true
        }
    }

    private fun initCastButton() {
        binding.btnCast.setOnClickListener { showCastOptions() }
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
                            castToDlnaDevice(devices[idx].controlUrl, devices[idx].ip)
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
            .setMessage("在电视「设置→网络→网络状态」可查看 IP")
            .setView(editText)
            .setPositiveButton("投屏") { _, _ ->
                val ip = editText.text.toString().trim()
                if (ip.isNotBlank()) castToDlnaDevice("http://$ip:7676/dmr/control/AVTransport1", ip)
            }.setNegativeButton("取消", null).show()
    }

    private fun castToDlnaDevice(controlUrl: String, ip: String) {
        Toast.makeText(this, "正在向 $ip 投屏...", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch(Dispatchers.IO) {
            val success = DlnaCaster.castTo(controlUrl, streamUrl, "Cycling Today 直播")
            withContext(Dispatchers.Main) {
                if (success) {
                    Toast.makeText(this@PlayerActivity, "🎉 投屏成功！请在电视上查看", Toast.LENGTH_LONG).show()
                    exoPlayer?.pause()
                } else {
                    AlertDialog.Builder(this@PlayerActivity)
                        .setTitle("投屏失败")
                        .setMessage("无法连接到 $ip\n\n请检查：\n• 电视是否开启 DLNA\n• IP 地址是否正确")
                        .setPositiveButton("重试") { _, _ -> inputIpAndCast() }
                        .setNegativeButton("取消", null).show()
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
