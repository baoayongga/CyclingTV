package com.cyclingtv.app

import android.app.Application
import android.app.AlertDialog
import android.content.Intent
import android.os.Build
import java.io.PrintWriter
import java.io.StringWriter

class CyclingApp : Application() {

    override fun onCreate() {
        super.onCreate()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val sw = StringWriter()
            throwable.printStackTrace(PrintWriter(sw))
            val stackTrace = sw.toString().take(2000)

            val intent = Intent(this, CrashActivity::class.java).apply {
                putExtra("error", "${throwable.javaClass.simpleName}: ${throwable.message}")
                putExtra("stack", stackTrace)
                putExtra("device", "Android ${Build.VERSION.SDK_INT} / ${Build.MODEL}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)

            // 延迟退出让用户能看错误
            Thread.sleep(3000)
            android.os.Process.killProcess(android.os.Process.myPid())
            System.exit(10)
        }
    }
}
