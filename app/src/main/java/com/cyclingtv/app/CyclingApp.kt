package com.cyclingtv.app

import android.app.Application
import android.content.Intent
import android.os.Build
import java.io.PrintWriter
import java.io.StringWriter

class CyclingApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Thread.setDefaultUncaughtExceptionHandler { _, throwable ->
            val sw = StringWriter()
            throwable.printStackTrace(PrintWriter(sw))
            val intent = Intent(this, CrashActivity::class.java).apply {
                putExtra("error", "${throwable.javaClass.simpleName}: ${throwable.message}")
                putExtra("stack", sw.toString().take(2000))
                putExtra("device", "Android ${Build.VERSION.SDK_INT} / ${Build.MODEL}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            Thread.sleep(5000)
            android.os.Process.killProcess(android.os.Process.myPid())
        }
    }
}
