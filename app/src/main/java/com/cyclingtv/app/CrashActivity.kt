package com.cyclingtv.app

import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class CrashActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val error = intent.getStringExtra("error") ?: "未知"
        val stack = intent.getStringExtra("stack") ?: ""
        val device = intent.getStringExtra("device") ?: ""
        val tv = TextView(this).apply {
            text = "崩溃: $error\n设备: $device\n\n$stack"
            textSize = 13f; setPadding(40, 40, 40, 40)
            movementMethod = ScrollingMovementMethod()
            setTextIsSelectable(true)
        }
        setContentView(ScrollView(this).apply { addView(tv) })
    }
}
