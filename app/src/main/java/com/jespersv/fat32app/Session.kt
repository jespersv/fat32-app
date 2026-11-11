package com.jespersv.fat32app

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.storage.StorageManager
import com.jespersv.fat32app.core.Copier
import com.jespersv.fat32app.core.Volume
import com.jespersv.fat32app.saf.SafVolume
import com.jespersv.fat32app.saf.UriSource
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
    val sources = ArrayList<UriSource>()
    var verify = true
    var onCollision = Copier.OnCollision.SKIP
    var copy: CopyJob? = null
        private set
    var outcome: Outcome? = null
        private set
    val log = ArrayList<String>()

    private lateinit var app: Context
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

        val usb = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(ACTION_USB_PERMISSION)
        }
        app.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    UsbManager.ACTION_USB_DEVICE_ATTACHED -> onAttached()
                    UsbManager.ACTION_USB_DEVICE_DETACHED ->
                        onDetached(intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java))
                    ACTION_USB_PERMISSION -> {
                        if (!intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                            driveMessage = "USB access was not allowed."
                        }
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
            override fun onReceive(context: Context, intent: Intent) = scan()
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
        forceDirect = true
        scan()
    }

    /** The user picked a folder with Android's own picker. */
    fun useFolder(tree: Uri) {
        if (copy != null) return
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
            if (result is Copier.Result.Copied) sources.remove(job.sources[i])
            if (result is Copier.Result.Skipped && result.reason == "Cancelled") problems = true
        }
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
        val states = storage.storageVolumes.filter { it.isRemovable }.map { it.state }
        return when {
            states.any { it == Environment.MEDIA_MOUNTED || it == Environment.MEDIA_MOUNTED_READ_ONLY } ->
                AndroidUse.MOUNTED
            states.any { it == Environment.MEDIA_CHECKING || it == Environment.MEDIA_EJECTING } ->
                AndroidUse.BUSY
            else -> AndroidUse.NONE
        }
    }

    private fun setDrive(state: Drive, message: String) {
        drive = state
        driveMessage = message
        changed()
    }

    fun formatSize(bytes: Long): String = when {
        bytes < 0 -> "unknown size"
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024))
        else -> String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024))
    }
}
