package com.jespersv.fat32app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.util.Log
import com.jespersv.fat32app.core.Diag
import java.io.File

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Diag.init(File(filesDir, "diag")) { level, text ->
            val priority = when (level) {
                Diag.Level.VERBOSE -> Log.VERBOSE
                Diag.Level.INFO -> Log.INFO
                Diag.Level.WARN -> Log.WARN
                Diag.Level.ERROR -> Log.ERROR
            }
            Log.println(priority, LOG_TAG, text)
        }
        Diag.verbose = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(PREF_VERBOSE, false)

        // Write crashes to the log before Android ends the process.
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { Diag.e("CRASH in thread ${thread.name}", error) }
            previous?.uncaughtException(thread, error)
        }

        Diag.i("===== App started =====")
        DiagShare.deviceInfo(this).forEach { Diag.i(it) }

        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL_PROGRESS, "Copy progress", NotificationManager.IMPORTANCE_LOW)
        )
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL_DONE, "Copy finished", NotificationManager.IMPORTANCE_DEFAULT)
        )
        Session.init(this)
    }

    companion object {
        const val CHANNEL_PROGRESS = "copy_progress"
        const val CHANNEL_DONE = "copy_done"
        const val LOG_TAG = "USBCopy"
        const val PREFS = "settings"
        const val PREF_VERBOSE = "verbose_log"
    }
}
