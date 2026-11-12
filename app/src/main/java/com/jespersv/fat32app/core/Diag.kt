package com.jespersv.fat32app.core

import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.io.Writer
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The app's diagnostics log. Plain Kotlin so the copy engine and tests can use it.
 *
 * Lines go to `diag.log` in [init]'s folder (rotated to `diag.1.log` at
 * [maxBytes]), to a small in-memory list for the screen, and to [init]'s
 * mirror (Logcat in the app). Every line is flushed to the operating system
 * at once, so it survives a crash. Safe to call from any thread.
 */
object Diag {

    enum class Level(val letter: Char) { VERBOSE('V'), INFO('I'), WARN('W'), ERROR('E') }

    const val FILE = "diag.log"
    const val OLD_FILE = "diag.1.log"
    private const val KEEP_RECENT = 400

    @Volatile var maxBytes = 1_000_000L

    /** Per-transfer USB timing and similar detail. Off by default: it is a lot of lines. */
    @Volatile var verbose = false

    private val lock = Any()
    private var dir: File? = null
    private var out: Writer? = null
    private var size = 0L
    private var mirror: ((Level, String) -> Unit)? = null
    private val recent = ArrayDeque<String>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val clock = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault())

    fun init(directory: File, mirror: ((Level, String) -> Unit)? = null) {
        synchronized(lock) {
            closeFile()
            directory.mkdirs()
            dir = directory
            this.mirror = mirror
            openFile()
        }
    }

    fun v(message: String) { if (verbose) write(Level.VERBOSE, message, null) }
    fun i(message: String) = write(Level.INFO, message, null)
    fun w(message: String, error: Throwable? = null) = write(Level.WARN, message, error)
    fun e(message: String, error: Throwable? = null) = write(Level.ERROR, message, error)

    /** The newest [count] lines, oldest first. */
    fun recent(count: Int = KEEP_RECENT): List<String> = synchronized(lock) { recent.toList().takeLast(count) }

    /** The log files on disk, oldest first. */
    fun files(): List<File> = synchronized(lock) {
        val d = dir ?: return emptyList()
        listOf(File(d, OLD_FILE), File(d, FILE)).filter { it.exists() }
    }

    fun clear() {
        synchronized(lock) {
            closeFile()
            dir?.let { File(it, OLD_FILE).delete(); File(it, FILE).delete() }
            recent.clear()
            openFile()
        }
        listeners.forEach { runCatching { it() } }
    }

    fun addListener(listener: () -> Unit) { listeners += listener }
    fun removeListener(listener: () -> Unit) { listeners -= listener }

    private fun write(level: Level, message: String, error: Throwable?) {
        val text = buildString {
            append(clock.format(Instant.now())).append(' ').append(level.letter).append(' ')
            append(Thread.currentThread().name).append(": ").append(message)
            if (error != null) append('\n').append(stackTrace(error).trimEnd())
        }
        synchronized(lock) {
            for (line in text.lines()) recent.addLast(line)
            while (recent.size > KEEP_RECENT) recent.removeFirst()
            val writer = out
            if (writer != null) {
                try {
                    writer.write(text)
                    writer.write("\n")
                    writer.flush()
                    size += text.toByteArray(Charsets.UTF_8).size + 1
                    if (size >= maxBytes) rotate()
                } catch (e: IOException) {
                    // Storage full or gone: keep logging to memory and the mirror only.
                    closeFile()
                }
            }
        }
        mirror?.let { runCatching { it(level, text) } }
        listeners.forEach { runCatching { it() } }
    }

    private fun rotate() {
        val d = dir ?: return
        closeFile()
        val old = File(d, OLD_FILE)
        old.delete()
        File(d, FILE).renameTo(old)
        openFile()
    }

    private fun openFile() {
        val d = dir ?: return
        val file = File(d, FILE)
        try {
            size = file.length()
            out = BufferedWriter(OutputStreamWriter(FileOutputStream(file, true), Charsets.UTF_8))
        } catch (e: IOException) {
            out = null
        }
    }

    private fun closeFile() {
        runCatching { out?.close() }
        out = null
    }

    private fun stackTrace(error: Throwable): String {
        val sw = StringWriter()
        error.printStackTrace(PrintWriter(sw))
        return sw.toString()
    }

    /** "12.3 MB in 4.10 s (3.0 MB/s)" */
    fun rate(bytes: Long, nanos: Long): String {
        val seconds = nanos / 1e9
        val mb = bytes / (1024.0 * 1024)
        val speed = if (seconds > 0) String.format(Locale.ROOT, "%.1f MB/s", mb / seconds) else "-"
        return String.format(Locale.ROOT, "%.2f MB in %.2f s (%s)", mb, seconds, speed)
    }
}
