package com.jespersv.fat32app

import android.app.ActivityManager
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Process
import android.system.Os
import android.system.OsConstants
import com.jespersv.fat32app.core.Diag
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/** Builds the text file that the Share button sends, and facts about the phone for the log. */
object DiagShare {

    fun shareDir(context: Context): File = File(context.cacheDir, "share")

    /** Lines describing the phone and app. Logged at start and at the top of every shared file. */
    fun deviceInfo(context: Context): List<String> {
        val pkg = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
        val am = context.getSystemService(ActivityManager::class.java)
        val pageSize = runCatching { Os.sysconf(OsConstants._SC_PAGESIZE) }.getOrDefault(-1L)
        return listOf(
            "App ${pkg?.versionName} (${pkg?.longVersionCode})",
            "Phone ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})",
            "Android ${Build.VERSION.RELEASE}, API ${Build.VERSION.SDK_INT}, build ${Build.DISPLAY}",
            "ABIs ${Build.SUPPORTED_ABIS.joinToString()}, page size $pageSize bytes",
            "Heap limit ${Runtime.getRuntime().maxMemory() / (1024 * 1024)} MB, " +
                "large memory class ${am.largeMemoryClass} MB",
            "USB helper libraries: ${nativeLibraryStatus()}",
        )
    }

    /** libaums ships two small native libraries; it works without them but cannot reset a stuck drive. */
    private fun nativeLibraryStatus(): String = listOf("usb-lib", "errno-lib").joinToString { name ->
        val result = runCatching { System.loadLibrary(name) }
        "$name " + if (result.isSuccess) "loaded" else "FAILED (${result.exceptionOrNull()?.message})"
    }

    /** Writes a fresh snapshot and returns the share intent. Call off the main thread. */
    fun buildIntent(context: Context): Intent {
        val dir = shareDir(context)
        dir.mkdirs()
        dir.listFiles()?.forEach { it.delete() }
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss"))
        val file = File(dir, "usbcopy-log-$stamp.txt")
        file.bufferedWriter().use { out ->
            out.appendLine("USB Copy diagnostics, $stamp")
            deviceInfo(context).forEach { out.appendLine(it) }
            out.appendLine()
            out.appendLine("===== App log =====")
            for (log in Diag.files()) log.bufferedReader().use { it.copyTo(out) }
            out.appendLine()
            out.appendLine("===== System log of this app (includes the USB library) =====")
            out.append(ownLogcat())
        }
        val uri = Uri.parse("content://${DiagProvider.AUTHORITY}/${file.name}")
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, "USB Copy diagnostics $stamp")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(file.name, uri)
        return Intent.createChooser(send, "Share diagnostics")
    }

    /** An app may read its own Logcat lines. That is where libaums' messages end up. */
    private fun ownLogcat(): String = try {
        val process = ProcessBuilder(
            "logcat", "-d", "-v", "threadtime", "-t", "4000", "--pid=${Process.myPid()}",
        ).redirectErrorStream(true).start()
        val text = process.inputStream.bufferedReader().readText()
        process.waitFor(5, TimeUnit.SECONDS)
        text
    } catch (e: Exception) {
        "(could not read the system log: $e)\n"
    }
}
