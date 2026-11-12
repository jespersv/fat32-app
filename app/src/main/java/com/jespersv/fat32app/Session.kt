package com.jespersv.fat32app

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.storage.StorageManager
import com.jespersv.fat32app.core.Copier
import com.jespersv.fat32app.core.Diag
import com.jespersv.fat32app.core.Volume
import com.jespersv.fat32app.saf.SafVolume
import com.jespersv.fat32app.saf.UriSource
import com.jespersv.fat32app.usb.FatVolume
import com.jespersv.fat32app.usb.UsbDrives
import me.jahnen.libaums.core.UsbMassStorageDevice
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** One running copy. Progress fields are written by the worker thread and read by the UI. */
class CopyJob(
    val volume: Volume,
    val direct: Boolean,
    val sources: List<UriSource>,
    val destDir: List<String>,
    val onCollision: Copier.OnCollision,
    val verify: Boolean,
) {
    val cancelled = AtomicBoolean(false)
    @Volatile var started = false
    @Volatile var index = 0
    @Volatile var name = ""
    @Volatile var fileSize = -1L
    @Volatile var fileBytes = 0L
    @Volatile var verifying = false

    /** Overall progress from 0 to 1. Writing and verifying each count as half of a file. */
    fun fraction(): Float {
        val inFile = if (fileSize > 0) (fileBytes.toFloat() / fileSize).coerceIn(0f, 1f) else 0f
        val file = if (!verify) inFile else if (verifying) 0.5f + inFile / 2 else inFile / 2
        return ((index + file) / sources.size.coerceAtLeast(1)).coerceIn(0f, 1f)
    }
}

/**
 * Everything the app knows at the moment: which drive is in use, which files
 * are queued, and the running copy. It lives for the whole process so that a
 * copy survives the screen being rotated or the activity being closed.
 *
 * All fields are read and changed on the main thread. Every call that touches
 * the drive runs on [io], a single worker thread, because neither the USB
 * library nor the FAT code may be used from two threads at once.
 */
object Session {

    enum class Drive { NONE, DETECTING, ANDROID_MOUNTED, NEED_PERMISSION, OPENING, READY, ERROR }

    enum class Outcome { ALL_COPIED, SOME_PROBLEMS, DRIVE_LOST }

    const val ACTION_USB_PERMISSION = "com.jespersv.fat32app.USB_PERMISSION"
    private const val DETECT_DELAY_MS = 2500L
    private const val BUSY_RETRY_MS = 1000L
    private const val PREF_FILES = "files"
    private const val PREF_VERIFY = "verify"
    private const val PREF_COLLISION = "on_collision"

    val io: ExecutorService = Executors.newSingleThreadExecutor { Thread(it, "drive-io") }
    val main = Handler(Looper.getMainLooper())

    var drive = Drive.NONE
        private set
    var driveMessage = ""
        private set
    var volume: Volume? = null
        private set

    /** True when the app talks to the USB device itself, false for a folder Android mounted. */
    var direct = false
        private set
    var freeBytes: Long? = null
        private set
    var destDir: List<String> = emptyList()
    private val fileList = ArrayList<UriSource>()

    /** The files to copy. Kept across copies and app restarts; change it only through the functions below. */
    val sources: List<UriSource> get() = fileList

    /** Set when saved files could not be opened again after a restart. Shown under the file list. */
    var missingFiles: String? = null
        private set

    var verify = true
        set(value) { field = value; saveSettings() }
    var onCollision = Copier.OnCollision.SKIP
        set(value) { field = value; saveSettings() }
    var copy: CopyJob? = null
        private set
    var outcome: Outcome? = null
        private set
    val log = ArrayList<String>()

    /** Whether the Diagnostics section on the screen is expanded. */
    var diagOpen = false

    private lateinit var app: Context
    private lateinit var prefs: SharedPreferences
    private lateinit var usbManager: UsbManager
    private var device: UsbMassStorageDevice? = null
    private var askedPermissionFor: String? = null
    private var forceDirect = false
    private var opening = false
    private var detectUntil = 0L
    private val listeners = LinkedHashSet<() -> Unit>()

    fun init(context: Context) {
        app = context.applicationContext
        usbManager = app.getSystemService(Context.USB_SERVICE) as UsbManager
        prefs = app.getSharedPreferences(App.PREFS, Context.MODE_PRIVATE)
        restoreSettings()

        val usb = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(ACTION_USB_PERMISSION)
        }
        app.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                when (intent.action) {
                    UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                        Diag.i("USB attached: ${device?.let { UsbDrives.describe(it, withNames = false) }}")
                        onAttached()
                    }
                    UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                        Diag.i("USB detached: ${device?.deviceName}")
                        onDetached(device)
                    }
                    ACTION_USB_PERMISSION -> {
                        val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                        Diag.i("USB permission for ${device?.deviceName}: ${if (granted) "granted" else "denied"}")
                        if (!granted) driveMessage = "USB access was not allowed."
                        scan()
                    }
                }
            }
        }, usb, Context.RECEIVER_NOT_EXPORTED)

        // Android mounting or unmounting a drive changes which route makes sense.
        val media = IntentFilter().apply {
            addAction(Intent.ACTION_MEDIA_MOUNTED)
            addAction(Intent.ACTION_MEDIA_UNMOUNTED)
            addAction(Intent.ACTION_MEDIA_EJECT)
            addAction(Intent.ACTION_MEDIA_REMOVED)
            addAction(Intent.ACTION_MEDIA_BAD_REMOVAL)
            addDataScheme("file")
        }
        app.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                Diag.i("Android storage event: ${intent.action?.substringAfterLast('.')} ${intent.data}")
                scan()
            }
        }, media, Context.RECEIVER_NOT_EXPORTED)

        // A drive that is already plugged in gets the same short wait as a new one.
        if (UsbDrives.find(app).isNotEmpty()) onAttached()
    }

    fun addListener(listener: () -> Unit) { listeners += listener }
    fun removeListener(listener: () -> Unit) { listeners -= listener }
    fun changed() { listeners.toList().forEach { it() } }

    /** A drive was just plugged in. Android needs a moment to decide whether it mounts it. */
    fun onAttached() {
        if (copy != null || volume != null) return
        detectUntil = SystemClock.uptimeMillis() + DETECT_DELAY_MS
        setDrive(Drive.DETECTING, "")
        main.postDelayed({ if (drive == Drive.DETECTING) scan() }, DETECT_DELAY_MS + 50)
    }

    /** Looks at what is plugged in and moves to the matching state. Safe to call at any time. */
    fun scan() {
        if (copy != null || opening) return
        if (drive == Drive.DETECTING && SystemClock.uptimeMillis() < detectUntil) return
        val found = UsbDrives.find(app)
        val current = device
        if (current != null) {
            if (found.any { it.usbDevice.deviceName == current.usbDevice.deviceName }) return
            releaseDirect()
        }
        val saf = volume as? SafVolume
        if (saf != null) {
            io.execute {
                val alive = runCatching { saf.list(emptyList()) }.isSuccess
                main.post {
                    if (!alive && volume === saf) {
                        volume = null
                        setDrive(Drive.NONE, "")
                        scan()
                    }
                }
            }
            return
        }
        if (found.isEmpty()) {
            askedPermissionFor = null
            forceDirect = false
            setDrive(Drive.NONE, "")
            return
        }
        val candidate = found[0]
        if (!forceDirect) {
            when (androidUse()) {
                AndroidUse.MOUNTED -> {
                    setDrive(Drive.ANDROID_MOUNTED, "")
                    return
                }
                AndroidUse.BUSY -> {
                    // Android is checking or ejecting the drive. Look again shortly.
                    detectUntil = SystemClock.uptimeMillis() + BUSY_RETRY_MS
                    setDrive(Drive.DETECTING, "")
                    main.postDelayed({ if (drive == Drive.DETECTING) scan() }, BUSY_RETRY_MS + 50)
                    return
                }
                AndroidUse.NONE -> {}
            }
        }
        if (!usbManager.hasPermission(candidate.usbDevice)) {
            val name = candidate.usbDevice.deviceName
            if (askedPermissionFor != name) {
                askedPermissionFor = name
                driveMessage = ""
                requestPermission(candidate.usbDevice)
            }
            setDrive(Drive.NEED_PERMISSION, driveMessage)
            return
        }
        open(candidate)
    }

    /** Asks again after the user dismissed or denied the USB permission dialog. */
    fun askPermissionAgain() {
        askedPermissionFor = null
        scan()
    }

    /** The user chose to use the drive directly although Android has it mounted. */
    fun takeOverDirectly() {
        Diag.i("User chose to take the drive over from Android")
        forceDirect = true
        scan()
    }

    /** The user picked a folder with Android's own picker. */
    fun useFolder(tree: Uri) {
        if (copy != null) return
        Diag.i("Folder picked with Android: $tree")
        io.execute {
            val picked = SafVolume(app, tree)
            main.post {
                releaseDirect()
                volume = picked
                direct = false
                freeBytes = null
                destDir = emptyList()
                outcome = null
                setDrive(Drive.READY, "")
            }
        }
    }

    fun startCopy(): CopyJob? {
        val target = volume ?: return null
        if (copy != null || sources.isEmpty() || drive != Drive.READY) return null
        val job = CopyJob(target, direct, ArrayList(sources), destDir, onCollision, verify)
        Diag.i(
            "Copy started: ${job.sources.size} files, ${formatSize(job.sources.sumOf { it.size.coerceAtLeast(0) })}, " +
                "to ${if (direct) "/" + destDir.joinToString("/") else target.title} on ${target.fsName}, " +
                "on name clash $onCollision, verify $verify"
        )
        copy = job
        outcome = null
        changed()
        return job
    }

    /** Called on the main thread by the copy service when the worker is done. */
    fun finishCopy(job: CopyJob, results: List<Copier.Result>, free: Long?) {
        var problems = false
        for ((i, result) in results.withIndex()) {
            val name = job.sources[i].name
            log += when (result) {
                is Copier.Result.Copied -> {
                    val renamed = if (result.finalName != name) " as ${result.finalName}" else ""
                    val checked = if (result.verified) ", verified" else ""
                    "OK    $name$renamed (${formatSize(result.bytes)}$checked)"
                }
                is Copier.Result.Skipped -> "SKIP  $name: ${result.reason}"
                is Copier.Result.Failed -> { problems = true; "FAIL  $name: ${result.reason}" }
            }
            if (result is Copier.Result.Skipped && result.reason == "Cancelled") problems = true
        }
        Diag.i(
            "Copy finished: ${results.count { it is Copier.Result.Copied }} copied, " +
                "${results.count { it is Copier.Result.Skipped }} skipped, " +
                "${results.count { it is Copier.Result.Failed }} failed"
        )
        while (log.size > 300) log.removeAt(0)
        copy = null
        val lost = volume !== job.volume
        outcome = when {
            lost -> Outcome.DRIVE_LOST
            problems -> Outcome.SOME_PROBLEMS
            else -> Outcome.ALL_COPIED
        }
        if (!lost) freeBytes = free
        changed()
        scan()
    }

    fun addSources(added: List<UriSource>) {
        var count = 0
        for (source in added) {
            if (fileList.none { it.uri == source.uri }) {
                fileList += source
                count++
            }
        }
        Diag.i("Added $count files to the list (${fileList.size} in total)")
        missingFiles = null
        outcome = null
        saveSettings()
        changed()
    }

    fun removeSource(source: UriSource) {
        fileList.remove(source)
        releaseReadPermission(source.uri)
        Diag.i("Removed '${source.name}' from the list")
        saveSettings()
        changed()
    }

    fun clearSources() {
        val old = ArrayList(fileList)
        fileList.clear()
        old.forEach { releaseReadPermission(it.uri) }
        missingFiles = null
        Diag.i("File list cleared")
        saveSettings()
        changed()
    }

    /** Files picked in Android's picker can be read again after a restart if the app keeps their permission. */
    private fun releaseReadPermission(uri: Uri) {
        runCatching { app.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }

    /** False until the saved list has been read back, so an early save cannot overwrite it. */
    private var filesLoaded = false

    private fun saveSettings() {
        if (!::prefs.isInitialized) return
        val edit = prefs.edit()
            .putBoolean(PREF_VERIFY, verify)
            .putString(PREF_COLLISION, onCollision.name)
        if (filesLoaded) edit.putString(PREF_FILES, fileList.joinToString("\n") { it.uri.toString() })
        edit.apply()
    }

    /** Loads the options at once and the saved file list in the background (it has to ask each file's app). */
    private fun restoreSettings() {
        verify = prefs.getBoolean(PREF_VERIFY, true)
        onCollision = runCatching { Copier.OnCollision.valueOf(prefs.getString(PREF_COLLISION, null)!!) }
            .getOrDefault(Copier.OnCollision.SKIP)
        val saved = prefs.getString(PREF_FILES, null).orEmpty().lines().filter { it.isNotBlank() }.map { Uri.parse(it) }
        if (saved.isEmpty()) {
            filesLoaded = true
            return
        }
        val resolver = app.contentResolver
        Thread {
            val found = ArrayList<UriSource>()
            val lost = ArrayList<String>()
            for (uri in saved) {
                val readable = runCatching { resolver.openFileDescriptor(uri, "r")?.use { true } ?: false }
                if (readable.getOrDefault(false)) {
                    found += UriSource.from(resolver, uri)
                } else {
                    lost += uri.lastPathSegment?.substringAfterLast('/') ?: uri.toString()
                    Diag.w("Saved file can no longer be read: $uri", readable.exceptionOrNull())
                }
            }
            main.post {
                // Files added while this was loading stay; the saved ones go first.
                fileList.addAll(0, found.filter { f -> fileList.none { it.uri == f.uri } })
                filesLoaded = true
                saveSettings()
                if (lost.isNotEmpty()) {
                    missingFiles = "${lost.size} saved file(s) could not be opened again and were removed " +
                        "from the list. Files shared from another app are only available until the app restarts; " +
                        "add them with Add files to keep them."
                }
                Diag.i("Restored ${found.size} saved files, ${lost.size} no longer available")
                changed()
            }
        }.start()
    }

    fun clearOutcome() {
        outcome = null
    }

    private fun onDetached(gone: UsbDevice?) {
        val current = device
        if (current != null && (gone == null || gone.deviceName == current.usbDevice.deviceName)) {
            releaseDirect()
            askedPermissionFor = null
            forceDirect = false
            setDrive(Drive.NONE, "")
        }
        if (copy == null) {
            detectUntil = 0
            scan()
        }
    }

    private fun open(candidate: UsbMassStorageDevice) {
        opening = true
        setDrive(Drive.OPENING, "")
        io.execute {
            val result = runCatching {
                val opened = UsbDrives.open(candidate)
                opened to runCatching { opened.volume.freeBytes() }.getOrNull()
            }
            main.post {
                opening = false
                result.onSuccess { (opened, free) ->
                    device = opened.device
                    volume = opened.volume
                    direct = true
                    freeBytes = free
                    destDir = emptyList()
                    outcome = null
                    setDrive(Drive.READY, opened.note.orEmpty())
                }.onFailure { error ->
                    if (UsbDrives.find(app).isEmpty()) {
                        setDrive(Drive.NONE, "")
                    } else {
                        setDrive(Drive.ERROR, error.message ?: error.javaClass.simpleName)
                    }
                }
            }
        }
    }

    /** Forgets the directly opened drive. Closing waits its turn behind any running work. */
    private fun releaseDirect() {
        val gone = device ?: return
        Diag.i("Releasing the USB drive")
        val old = volume
        device = null
        if (direct) {
            volume = null
            freeBytes = null
        }
        io.execute {
            runCatching { old?.close() }
            runCatching { gone.close() }
        }
    }

    private fun requestPermission(usbDevice: UsbDevice) {
        Diag.i("Asking for USB permission: ${UsbDrives.describe(usbDevice, withNames = false)}")
        val intent = Intent(ACTION_USB_PERMISSION).setPackage(app.packageName)
        // Mutable because the system adds the device and the answer to the intent.
        val pending = PendingIntent.getBroadcast(app, 0, intent, PendingIntent.FLAG_MUTABLE)
        usbManager.requestPermission(usbDevice, pending)
    }

    private enum class AndroidUse { NONE, BUSY, MOUNTED }

    /**
     * Whether Android itself is using a removable drive. The app must not grab the
     * USB device underneath a mounted file system or a running disk check.
     */
    private fun androidUse(): AndroidUse {
        val storage = app.getSystemService(Context.STORAGE_SERVICE) as StorageManager
        val removable = storage.storageVolumes.filter { it.isRemovable }
        val summary = removable.joinToString("; ") { "'${it.getDescription(app)}' ${it.state} uuid=${it.uuid}" }
        if (summary != lastVolumes) {
            lastVolumes = summary
            Diag.i("Android removable storage: ${summary.ifEmpty { "none" }}")
        }
        val states = removable.map { it.state }
        return when {
            states.any { it == Environment.MEDIA_MOUNTED || it == Environment.MEDIA_MOUNTED_READ_ONLY } ->
                AndroidUse.MOUNTED
            states.any { it == Environment.MEDIA_CHECKING || it == Environment.MEDIA_EJECTING } ->
                AndroidUse.BUSY
            else -> AndroidUse.NONE
        }
    }

    private var lastVolumes: String? = null

    private fun setDrive(state: Drive, message: String) {
        if (state != drive || message != driveMessage) {
            Diag.i("Drive state: $state${if (message.isEmpty()) "" else " ($message)"}")
        }
        drive = state
        driveMessage = message
        changed()
    }

    /**
     * "Inspect drive": logs everything the app can learn about connected drives
     * without writing to them. Runs behind any other drive work.
     */
    fun inspect(done: () -> Unit) {
        Diag.i("===== Inspect drive =====")
        DiagShare.deviceInfo(app).forEach { Diag.i(it) }
        Diag.i("App state: drive $drive, route ${if (direct) "direct" else "Android"}, volume ${volume?.title}")
        val devices = usbManager.deviceList.values
        Diag.i("USB devices: ${devices.size}")
        for (d in devices) {
            val allowed = usbManager.hasPermission(d)
            Diag.i("  ${UsbDrives.describe(d, withNames = allowed)}, permission ${if (allowed) "yes" else "no"}")
        }
        val storage = app.getSystemService(Context.STORAGE_SERVICE) as StorageManager
        for (v in storage.storageVolumes) {
            val dir = v.directory
            val space = dir?.let { runCatching { " free ${formatSize(it.usableSpace)} of ${formatSize(it.totalSpace)}" }.getOrNull() }
            Diag.i(
                "  Storage '${v.getDescription(app)}': ${v.state}, removable ${v.isRemovable}, primary ${v.isPrimary}, " +
                    "uuid ${v.uuid}, path $dir${space.orEmpty()}"
            )
        }
        val target = volume
        io.execute {
            if (target != null) {
                runCatching {
                    target.describe().forEach { Diag.i("  $it") }
                    val entries = target.list(emptyList())
                    Diag.i("  Root folder: ${entries.size} entries")
                    for (e in entries.take(40)) {
                        Diag.i("    ${if (e.isDir) "[dir] " else ""}${e.name}${if (e.isDir) "" else " (${e.size} bytes)"}")
                    }
                    if (target is FatVolume) {
                        target.takeStats()
                        val (bytes, nanos) = target.readSpeedTest(16L * 1024 * 1024)
                        Diag.i("  Read speed: ${Diag.rate(bytes, nanos)}")
                        Diag.i("  ${target.takeStats()}")
                    }
                }.onFailure { Diag.e("Inspecting the volume failed", it) }
            } else {
                Diag.i("  No volume is open, so there is no file system to inspect.")
            }
            Diag.i("===== Inspect done =====")
            main.post(done)
        }
    }

    fun formatSize(bytes: Long): String = when {
        bytes < 0 -> "unknown size"
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024))
        else -> String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024))
    }
}
