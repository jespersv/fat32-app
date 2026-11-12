package com.jespersv.fat32app

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import com.jespersv.fat32app.core.Copier
import com.jespersv.fat32app.core.Diag

/**
 * Runs the copy as a foreground service, so it keeps going when the screen
 * turns off or another app is opened. The work itself happens on
 * [Session.io]; this class only keeps the process alive and shows progress.
 */
class CopyService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(
            NOTE_PROGRESS, progressNote("Starting", 0f),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        val job = Session.copy
        if (job == null) {
            stop()
        } else if (!job.started) {
            job.started = true
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "fat32app:copy")
                .apply { acquire(MAX_RUN_MS) }
            Session.io.execute { run(job) }
        }
        return START_NOT_STICKY
    }

    /** Android limits how long a data-sync service may run in one day. Stop cleanly if it is reached. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Diag.w("Android's time limit for background copying was reached; cancelling")
        Session.copy?.cancelled?.set(true)
    }

    private fun run(job: CopyJob) {
        val notifications = getSystemService(NotificationManager::class.java)
        val listener = object : Copier.Listener {
            private var lastUi = 0L
            private var lastNote = 0L
            private var fileStart = 0L
            private var verifyStart = 0L
            private var written = 0L

            override fun onFileStart(index: Int, name: String, size: Long) {
                fileStart = System.nanoTime()
                verifyStart = 0L
                written = 0L
                job.volume.takeStats()
                Diag.i("File ${index + 1}/${job.sources.size}: '$name', ${Session.formatSize(size)}")
                job.index = index
                job.name = name
                job.fileSize = size
                job.fileBytes = 0
                job.verifying = false
                publish(force = true)
            }

            override fun onProgress(index: Int, fileBytes: Long, totalBytes: Long, verifying: Boolean) {
                if (verifying && verifyStart == 0L) verifyStart = System.nanoTime()
                if (!verifying) written = fileBytes
                job.fileBytes = fileBytes
                job.verifying = verifying
                publish(force = false)
            }

            override fun onFileDone(index: Int, name: String, result: Copier.Result) {
                val now = System.nanoTime()
                val writeEnd = if (verifyStart != 0L) verifyStart else now
                val timing = buildString {
                    if (written > 0) append("write ${Diag.rate(written, writeEnd - fileStart)}")
                    if (verifyStart != 0L) append("; verify ${Diag.rate(written, now - verifyStart)}")
                }
                val line = "  -> $result${if (timing.isEmpty()) "" else "; $timing"}"
                if (result is Copier.Result.Failed) Diag.w(line) else Diag.i(line)
                job.volume.takeStats()?.let { Diag.i("  $it") }
            }

            private fun publish(force: Boolean) {
                val now = SystemClock.uptimeMillis()
                if (force || now - lastUi >= 100) {
                    lastUi = now
                    Session.main.post { Session.changed() }
                }
                if (now - lastNote >= 1000) {
                    lastNote = now
                    val text = "${job.index + 1} of ${job.sources.size}: ${job.name}"
                    notifications.notify(NOTE_PROGRESS, progressNote(text, job.fraction()))
                }
            }
        }

        val results: List<Copier.Result> = try {
            Copier(job.volume, COPY_BUFFER) { message, error -> Diag.e(message, error) }.copyAll(
                job.sources, job.destDir, job.onCollision, job.verify, listener,
            ) { job.cancelled.get() }
        } catch (t: Throwable) {
            Diag.e("Copy stopped by an internal error", t)
            // The copier reports ordinary failures per file. This catches the rest (out of
            // memory, a missing native library) so the screen never stays on "Copying".
            job.sources.map { Copier.Result.Failed("Stopped by an internal error: $t") }
        }

        // Everything is flushed at this point. A short pause gives the drive's own
        // controller time to finish before the app says it is safe to unplug.
        if (job.direct && results.any { it is Copier.Result.Copied }) Thread.sleep(SETTLE_MS)
        val free = runCatching { job.volume.freeBytes() }.getOrNull()

        Session.main.post {
            Session.finishCopy(job, results, free)
            notifications.notify(NOTE_DONE, doneNote(results))
            stop()
        }
    }

    private fun stop() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
    )

    private fun progressNote(text: String, fraction: Float): Notification =
        Notification.Builder(this, App.CHANNEL_PROGRESS)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Copying to USB drive, do not unplug")
            .setContentText(text)
            .setProgress(1000, (fraction * 1000).toInt(), false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp())
            .build()

    private fun doneNote(results: List<Copier.Result>): Notification {
        val copied = results.count { it is Copier.Result.Copied }
        val title = when (Session.outcome) {
            Session.Outcome.ALL_COPIED ->
                if (Session.direct) "Copy finished, safe to unplug" else "Copy finished, eject the drive in Android"
            Session.Outcome.DRIVE_LOST -> "Drive was removed during the copy"
            else -> "Copy finished with problems"
        }
        return Notification.Builder(this, App.CHANNEL_DONE)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle(title)
            .setContentText("$copied of ${results.size} files copied")
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .build()
    }

    companion object {
        private const val NOTE_PROGRESS = 1
        private const val NOTE_DONE = 2
        private const val COPY_BUFFER = 4 shl 20
        private const val SETTLE_MS = 1500L
        private const val MAX_RUN_MS = 6 * 60 * 60 * 1000L
    }
}
