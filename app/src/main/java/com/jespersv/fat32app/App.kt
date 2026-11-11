package com.jespersv.fat32app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class App : Application() {
    override fun onCreate() {
        super.onCreate()
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
    }
}
