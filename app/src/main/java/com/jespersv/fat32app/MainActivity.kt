package com.jespersv.fat32app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.atomic.AtomicBoolean
import com.jespersv.fat32app.core.Copier
import com.jespersv.fat32app.core.Diag
import com.jespersv.fat32app.saf.UriSource

/**
 * The single screen of the app. It only shows what [Session] holds and passes
 * taps on to it, so rotating the phone or leaving the app loses nothing.
 */
class MainActivity : Activity() {

    private lateinit var statusCard: LinearLayout
    private lateinit var statusTitle: TextView
    private lateinit var statusDetail: TextView
    private lateinit var pickFolderButton: Button
    private lateinit var directButton: Button
    private lateinit var retryButton: Button
    private lateinit var storageButton: Button
    private lateinit var destinationLabel: TextView
    private lateinit var destinationButton: Button
    private lateinit var filesLabel: TextView
    private lateinit var addButton: Button
    private lateinit var clearButton: Button
    private lateinit var verifyBox: CheckBox
    private lateinit var collisionSpinner: Spinner
    private lateinit var copyButton: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var progressLabel: TextView
    private lateinit var cancelButton: Button
    private lateinit var logLabel: TextView
    private lateinit var diagToggle: Button
    private lateinit var diagBox: LinearLayout
    private lateinit var diagText: TextView
    private lateinit var inspectButton: Button

    private val onChange: () -> Unit = { render() }

    /** New log lines arrive from any thread, often. Redraw the log view at most four times a second. */
    private val diagPending = AtomicBoolean(false)
    private val onLogLine: () -> Unit = {
        if (diagPending.compareAndSet(false, true)) {
            Session.main.postDelayed({
                diagPending.set(false)
                renderDiag()
            }, 250)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildLayout())
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        Session.addListener(onChange)
        Diag.addListener(onLogLine)
        Session.scan()
        render()
        renderDiag()
    }

    override fun onStop() {
        Session.removeListener(onChange)
        Diag.removeListener(onLogLine)
        super.onStop()
    }

    /** Files shared into the app from another app are added to the list. */
    private fun handleIntent(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_SEND ->
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)?.let { addFiles(listOf(it)) }
            Intent.ACTION_SEND_MULTIPLE ->
                intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)?.let { addFiles(it) }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (resultCode != RESULT_OK || data == null) return
        when (requestCode) {
            REQUEST_FILES -> {
                val uris = ArrayList<Uri>()
                val clip = data.clipData
                if (clip != null) {
                    for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let { uris += it }
                } else {
                    data.data?.let { uris += it }
                }
                addFiles(uris)
            }
            REQUEST_TREE -> data.data?.let { tree ->
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                runCatching { contentResolver.takePersistableUriPermission(tree, flags) }
                Session.useFolder(tree)
            }
        }
    }

    private fun addFiles(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val resolver = applicationContext.contentResolver
        Thread {
            val added = uris.map { UriSource.from(resolver, it) }
            Session.main.post {
                if (Session.copy != null) {
                    toast("Wait for the running copy to finish before adding files.")
                    return@post
                }
                for (source in added) {
                    if (Session.sources.none { it.uri == source.uri }) Session.sources += source
                }
                Session.clearOutcome()
                Session.changed()
            }
        }.start()
    }

    private fun pickFiles() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        launch(intent, REQUEST_FILES)
    }

    private fun pickFolderWithAndroid() = launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQUEST_TREE)

    private fun launch(intent: Intent, request: Int) {
        try {
            startActivityForResult(intent, request)
        } catch (e: Exception) {
            toast("No app on this phone can show that picker.")
        }
    }

    private fun chooseDestination() {
        val volume = Session.volume ?: return
        if (Session.direct) {
            FolderPicker(this, volume, Session.destDir) { picked ->
                Session.destDir = picked
                Session.changed()
            }.show()
        } else {
            pickFolderWithAndroid()
        }
    }

    private fun confirmDirect() {
        AlertDialog.Builder(this)
            .setTitle("Use the drive directly?")
            .setMessage(
                "Android has this drive mounted. Taking it over disconnects it from Android " +
                    "without ejecting, as if it had been pulled out. Do this only if nothing " +
                    "else is using the drive.\n\nThe usual choice is to cancel and use " +
                    "\"Pick folder with Android\"."
            )
            .setPositiveButton("Use directly") { _, _ -> Session.takeOverDirectly() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun startCopy() {
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            // Only affects whether progress shows in the notification shade; the copy runs either way.
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        }
        if (Session.startCopy() != null) {
            startForegroundService(Intent(this, CopyService::class.java))
        }
    }

    private fun render() {
        val drive = Session.drive
        val job = Session.copy
        val copying = job != null
        val ready = drive == Session.Drive.READY && Session.volume != null

        val (title, detail, color) = status(drive, job)
        statusTitle.text = title
        statusDetail.text = detail
        statusDetail.visibility = if (detail.isEmpty()) View.GONE else View.VISIBLE
        (statusCard.background as GradientDrawable).setColor(color)

        pickFolderButton.show(!copying && drive != Session.Drive.OPENING)
        directButton.show(drive == Session.Drive.ANDROID_MOUNTED)
        retryButton.show(
            drive == Session.Drive.NONE || drive == Session.Drive.ERROR || drive == Session.Drive.NEED_PERMISSION
        )
        retryButton.text = if (drive == Session.Drive.NEED_PERMISSION) "Ask for USB access again" else "Look for a drive again"
        storageButton.show(ready && !Session.direct)

        destinationLabel.show(ready)
        destinationButton.show(ready && !copying)
        if (ready) {
            destinationLabel.text = if (Session.direct) {
                "Copy into:  /" + Session.destDir.joinToString("/")
            } else {
                "Copy into:  " + Session.volume?.title
            }
        }

        val sources = Session.sources
        filesLabel.text = if (sources.isEmpty()) {
            "No files chosen yet."
        } else {
            val shown = sources.take(MAX_LISTED).joinToString("\n") { "${it.name}  (${Session.formatSize(it.size)})" }
            val more = if (sources.size > MAX_LISTED) "\nand ${sources.size - MAX_LISTED} more" else ""
            shown + more
        }
        addButton.isEnabled = !copying
        clearButton.show(sources.isNotEmpty() && !copying)
        verifyBox.isEnabled = !copying
        collisionSpinner.isEnabled = !copying

        val total = sources.sumOf { it.size.coerceAtLeast(0) }
        copyButton.text = when {
            sources.isEmpty() -> "Copy to USB drive"
            sources.size == 1 -> "Copy 1 file (${Session.formatSize(total)})"
            else -> "Copy ${sources.size} files (${Session.formatSize(total)})"
        }
        copyButton.show(!copying)
        copyButton.isEnabled = ready && sources.isNotEmpty()

        progressBar.show(copying)
        progressLabel.show(copying)
        cancelButton.show(copying)
        if (job != null) {
            progressBar.progress = (job.fraction() * 1000).toInt()
            val phase = if (job.verifying) "checking" else "writing"
            val size = if (job.fileSize >= 0) " of ${Session.formatSize(job.fileSize)}" else ""
            progressLabel.text = "File ${job.index + 1} of ${job.sources.size}, $phase\n${job.name}\n" +
                "${Session.formatSize(job.fileBytes)}$size"
            cancelButton.isEnabled = !job.cancelled.get()
            cancelButton.text = if (job.cancelled.get()) "Cancelling" else "Cancel"
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        inspectButton.isEnabled = !copying

        logLabel.text = Session.log.asReversed().joinToString("\n")
        logLabel.show(Session.log.isNotEmpty())
    }

    private fun renderDiag() {
        diagBox.show(Session.diagOpen)
        diagToggle.text = if (Session.diagOpen) "Diagnostics  \u25BE" else "Diagnostics  \u25B8"
        if (Session.diagOpen) diagText.text = Diag.recent(DIAG_LINES).asReversed().joinToString("\n")
    }

    private fun shareLog() {
        toast("Preparing the log")
        Thread {
            val result = runCatching { DiagShare.buildIntent(applicationContext) }
            Session.main.post {
                result.onSuccess { intent ->
                    runCatching { startActivity(intent) }.onFailure { toast("No app to share with: ${it.message}") }
                }.onFailure {
                    Diag.e("Building the shared log failed", it)
                    toast("Could not build the log: ${it.message}")
                }
            }
        }.start()
    }

    private fun copyLog() {
        val text = Diag.recent().joinToString("\n")
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("USB Copy log", text))
        toast("Copied the last ${Diag.recent().size} lines")
    }

    private fun confirmClearLog() {
        AlertDialog.Builder(this)
            .setMessage("Delete the diagnostics log?")
            .setPositiveButton("Delete") { _, _ ->
                Diag.clear()
                Diag.i("Log cleared")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Headline, explanation and colour for the status card. */
    private fun status(drive: Session.Drive, job: CopyJob?): Triple<String, String, Int> {
        val volume = Session.volume
        if (job != null) {
            return Triple("Copying. Do not unplug.", "The app will say when it is safe.", RED)
        }
        if (Session.outcome == Session.Outcome.DRIVE_LOST) {
            return Triple(
                "The drive was removed during the copy",
                "Files that were not finished are missing or have a name ending in .part. See the list below.",
                RED,
            )
        }
        return when (drive) {
            Session.Drive.NONE -> Triple(
                "No USB drive",
                "Plug in a FAT16 or FAT32 drive. If Android shows the drive in its Files app, " +
                    "use \"Pick folder with Android\".",
                GREY,
            )
            Session.Drive.DETECTING -> Triple("Drive plugged in", "Checking whether Android mounts it.", GREY)
            Session.Drive.ANDROID_MOUNTED -> Triple(
                "Android has mounted this drive",
                "Choose a folder on it with \"Pick folder with Android\". That is the safe route " +
                    "while Android is using the drive.",
                BLUE,
            )
            Session.Drive.NEED_PERMISSION -> Triple(
                "Waiting for USB access",
                Session.driveMessage.ifEmpty { "Allow access in the dialog Android shows." },
                GREY,
            )
            Session.Drive.OPENING -> Triple(
                "Opening the drive",
                "Reading the file system. Large FAT32 drives take a few seconds.",
                GREY,
            )
            Session.Drive.ERROR -> Triple("This drive cannot be used", Session.driveMessage, RED)
            Session.Drive.READY -> {
                val name = volume?.title.orEmpty()
                if (Session.direct) {
                    val free = Session.freeBytes?.let { ", ${Session.formatSize(it)} free" }.orEmpty()
                    val finished = when (Session.outcome) {
                        Session.Outcome.ALL_COPIED -> "Copy finished. "
                        Session.Outcome.SOME_PROBLEMS -> "Copy finished with problems, see the list below. "
                        else -> ""
                    }
                    val note = if (Session.driveMessage.isEmpty()) "" else "\n" + Session.driveMessage
                    Triple(
                        "Safe to unplug",
                        "$finished$name (${volume?.fsName}$free). Everything is written to the drive.$note",
                        if (Session.outcome == Session.Outcome.SOME_PROBLEMS) AMBER else GREEN,
                    )
                } else {
                    val finished = when (Session.outcome) {
                        Session.Outcome.ALL_COPIED -> "Copy finished. "
                        Session.Outcome.SOME_PROBLEMS -> "Copy finished with problems, see the list below. "
                        else -> ""
                    }
                    Triple(
                        "Eject in Android before unplugging",
                        "${finished}Folder \"$name\" is on a drive Android has mounted. Use Eject in the " +
                            "USB drive notification or in Storage settings, then unplug.",
                        if (Session.outcome == Session.Outcome.SOME_PROBLEMS) AMBER else BLUE,
                    )
                }
            }
        }
    }

    private fun buildLayout(): View {
        val pad = dp(16)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        column.addView(TextView(this).apply {
            text = "USB Copy"
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
        }, rowParams(bottom = 12))

        statusTitle = TextView(this).apply {
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        }
        statusDetail = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding(0, dp(6), 0, 0)
        }
        statusCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(GREY) }
            addView(statusTitle)
            addView(statusDetail)
        }
        column.addView(statusCard, rowParams(bottom = 8))

        pickFolderButton = button("Pick folder with Android") { pickFolderWithAndroid() }
        directButton = button("Use drive directly") { confirmDirect() }
        retryButton = button("Look for a drive again") {
            if (Session.drive == Session.Drive.NEED_PERMISSION) Session.askPermissionAgain() else Session.scan()
        }
        storageButton = button("Open Storage settings") {
            runCatching { startActivity(Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)) }
                .onFailure { toast("Open Settings, then Storage, to eject the drive.") }
        }
        column.addView(pickFolderButton, rowParams())
        column.addView(directButton, rowParams())
        column.addView(retryButton, rowParams())
        column.addView(storageButton, rowParams())

        destinationLabel = TextView(this).apply { textSize = 16f }
        destinationButton = button("Change folder") { chooseDestination() }
        column.addView(destinationLabel, rowParams(top = 16))
        column.addView(destinationButton, rowParams())

        column.addView(heading("Files"), rowParams(top = 20))
        filesLabel = TextView(this).apply { textSize = 14f }
        column.addView(filesLabel, rowParams(bottom = 4))
        addButton = button("Add files") { pickFiles() }
        clearButton = button("Clear list") {
            Session.sources.clear()
            Session.changed()
        }
        column.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(addButton, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(clearButton, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }, rowParams())

        column.addView(heading("Options"), rowParams(top = 20))
        verifyBox = CheckBox(this).apply {
            text = "Read each file back and compare after writing (takes about twice as long)"
            isChecked = Session.verify
            setOnCheckedChangeListener { _, checked -> Session.verify = checked }
        }
        column.addView(verifyBox, rowParams())
        column.addView(TextView(this).apply { text = "If a file with the same name is already on the drive:" },
            rowParams(top = 8))
        collisionSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf("Skip it", "Replace it", "Keep both (add a number)"),
            )
            setSelection(Session.onCollision.ordinal)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    Session.onCollision = Copier.OnCollision.values()[position]
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }
        column.addView(collisionSpinner, rowParams())

        copyButton = button("Copy to USB drive") { startCopy() }.apply { textSize = 18f }
        column.addView(copyButton, rowParams(top = 20))

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000 }
        progressLabel = TextView(this).apply { textSize = 14f }
        cancelButton = button("Cancel") {
            Session.copy?.cancelled?.set(true)
            render()
        }
        column.addView(progressBar, rowParams(top = 20))
        column.addView(progressLabel, rowParams())
        column.addView(cancelButton, rowParams())

        logLabel = TextView(this).apply {
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        column.addView(logLabel, rowParams(top = 20))

        diagToggle = button("Diagnostics") {
            Session.diagOpen = !Session.diagOpen
            renderDiag()
        }
        column.addView(diagToggle, rowParams(top = 24))
        diagBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        diagBox.addView(TextView(this).apply {
            textSize = 13f
            text = "A record of what the app did, for finding problems. It stays on the phone " +
                "unless you share it. Newest lines first."
        }, rowParams(bottom = 4))
        inspectButton = button("Inspect drive (read only)") {
            inspectButton.isEnabled = false
            Session.inspect {
                render()
                toast("Inspection written to the log")
            }
        }
        diagBox.addView(inspectButton, rowParams())
        diagBox.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(button("Share log") { shareLog() }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(button("Copy") { copyLog() }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(button("Clear") { confirmClearLog() }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }, rowParams())
        diagBox.addView(CheckBox(this).apply {
            text = "Verbose: log every USB transfer (many lines; use only while chasing a problem)"
            isChecked = Diag.verbose
            setOnCheckedChangeListener { _, checked ->
                Diag.verbose = checked
                getSharedPreferences(App.PREFS, MODE_PRIVATE).edit().putBoolean(App.PREF_VERBOSE, checked).apply()
                Diag.i("Verbose logging ${if (checked) "on" else "off"}")
            }
        }, rowParams())
        diagText = TextView(this).apply {
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        diagBox.addView(diagText, rowParams(top = 8))
        column.addView(diagBox, rowParams())

        return ScrollView(this).apply {
            isFillViewport = true
            addView(column, MATCH_PARENT, WRAP_CONTENT)
            // The app draws edge to edge; keep the content clear of the system bars.
            setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        }
    }

    private fun heading(text: String) = TextView(this).apply {
        this.text = text
        textSize = 16f
        typeface = Typeface.DEFAULT_BOLD
    }

    private fun button(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        gravity = Gravity.CENTER
        setOnClickListener { onClick() }
    }

    private fun rowParams(top: Int = 0, bottom: Int = 0) =
        LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            topMargin = dp(top)
            bottomMargin = dp(bottom)
        }

    private fun View.show(visible: Boolean) {
        visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    companion object {
        private const val REQUEST_FILES = 1
        private const val REQUEST_TREE = 2
        private const val REQUEST_NOTIFICATIONS = 3
        private const val MAX_LISTED = 30
        private const val DIAG_LINES = 80
        private val GREEN = Color.rgb(0x2E, 0x7D, 0x32)
        private val RED = Color.rgb(0xC6, 0x28, 0x28)
        private val AMBER = Color.rgb(0xB2, 0x6A, 0x00)
        private val BLUE = Color.rgb(0x15, 0x65, 0xC0)
        private val GREY = Color.rgb(0x54, 0x6E, 0x7A)
    }
}
