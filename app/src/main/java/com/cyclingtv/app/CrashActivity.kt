package com.cyclingtv.app

import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class CrashActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val error = intent.getStringExtra("error") ?: "未知错误"
        val stack = intent.getStringExtra("stack") ?: "无堆栈信息"
        val device = intent.getStringExtra("device") ?: ""

        val tv = TextView(this).apply {
            text = """💥 应用崩溃

错误类型: $error

设备: $device

堆栈跟踪:
$stack
            """.trimIndent()
            textSize = 13f
            setPadding(32, 32, 32, 32)
            movementMethod = ScrollingMovementMethod()
            setTextIsSelectable(true)
        }

        setContentView(ScrollView(this).apply { addView(tv) })
    }
}
