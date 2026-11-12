package com.jespersv.fat32app.usb

import com.jespersv.fat32app.core.Entry
import com.jespersv.fat32app.core.FAT_MAX_FILE_SIZE
import com.jespersv.fat32app.core.NewFile
import com.jespersv.fat32app.core.Volume
import de.waldheinz.fs.fat.FatFile
import de.waldheinz.fs.fat.FatLfnDirectory
import de.waldheinz.fs.fat.FatLfnDirectoryEntry
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer

/** A FAT12, FAT16 or FAT32 volume on a USB drive the app talks to directly. */
class FatVolume(private val fs: FatLibFileSystem) : Volume {

    private val fat = fs.fat

    override val title: String = fs.volumeLabel.ifBlank { "USB drive" }
    override val fsName: String = fs.probe.kind.name
    override val maxFileSize: Long = FAT_MAX_FILE_SIZE

    override fun freeBytes(): Long = fs.freeSpace

    override fun list(dir: List<String>): List<Entry> {
        val out = ArrayList<Entry>()
        for (e in resolve(dir)) {
            if (e.name == "." || e.name == "..") continue
            val size = if (e.isFile) (e as FatLfnDirectoryEntry).file.length else 0L
            out += Entry(e.name, e.isDirectory, size)
        }
        return out
    }

    override fun mkdir(dir: List<String>, name: String) {
        resolve(dir).addDirectory(name)
        flush()
    }

    override fun create(dir: List<String>, name: String, size: Long): NewFile {
        val parent = resolve(dir)
        val entry = parent.addFile(name)
        val file = entry.file
        try {
            // Reserves the clusters now, so a full drive is noticed before any data is sent.
            if (size > 0) file.setLength(size)
        } catch (e: IOException) {
            runCatching { parent.remove(entry.name); flush() }
            throw IOException("Not enough free space on the drive", e)
        }
        return Writing(parent, entry, file)
    }

    override fun delete(dir: List<String>, name: String) {
        resolve(dir).remove(name)
        flush()
    }

    /** Writes directories and the allocation table, then any file data still collected. */
    override fun flush() {
        fat.flush()
        fs.device.flush()
    }

    override fun takeStats(): String = fs.device.takeStats()

    override fun describe(): List<String> {
        val p = fs.probe
        return listOf(
            "Volume '$title', $fsName",
            "Layout: ${p.bytesPerSector} bytes/sector, ${p.bytesPerCluster} bytes/cluster, ${p.clusterCount} clusters, " +
                "${p.fatCount} FATs of ${p.fatSizeBytes} bytes at offset ${p.fatOffsetBytes}",
            "Size ${p.totalBytes} bytes, free ${freeBytes()} bytes",
        )
    }

    /**
     * Reads up to [bytes] from the start of the data area and returns the nanoseconds it
     * took. Read only; used by "Inspect drive" to measure the drive's read speed.
     */
    fun readSpeedTest(bytes: Long): Pair<Long, Long> {
        val p = fs.probe
        val length = minOf(bytes, p.totalBytes - p.fatEndBytes).coerceAtLeast(0)
        val buffer = ByteBuffer.allocate(1 shl 20)
        var done = 0L
        val start = System.nanoTime()
        while (done < length) {
            buffer.clear()
            buffer.limit(minOf(buffer.capacity().toLong(), length - done).toInt())
            fs.device.read(p.fatEndBytes + done, buffer)
            done += buffer.limit()
        }
        return done to (System.nanoTime() - start)
    }

    /** Nothing to do: the copy engine flushes after every file, and folder changes flush themselves. */
    override fun close() {}

    private fun resolve(path: List<String>): FatLfnDirectory {
        var dir = fat.root
        for (segment in path) {
            val entry = dir.getEntry(segment) ?: throw FileNotFoundException("Folder not found: $segment")
            if (!entry.isDirectory) throw IOException("Not a folder: $segment")
            dir = entry.directory
        }
        return dir
    }

    private inner class Writing(
        private val parent: FatLfnDirectory,
        private val entry: FatLfnDirectoryEntry,
        private val file: FatFile,
    ) : NewFile {
        private var position = 0L

        override fun write(buf: ByteArray, off: Int, len: Int) {
            file.write(position, ByteBuffer.wrap(buf, off, len))
            position += len
        }

        /**
         * Sends the remaining file data. The directory entry and allocation table follow in
         * the volume flush after the rename, so an unplugged drive never shows a half file.
         */
        override fun finish() {
            if (file.length != position) file.setLength(position)
            fs.device.flush()
        }

        override fun openRead(): InputStream = object : InputStream() {
            private var at = 0L
            private val one = ByteArray(1)

            override fun read(): Int = if (read(one, 0, 1) < 1) -1 else one[0].toInt() and 0xFF

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                val n = minOf(len.toLong(), file.length - at).toInt()
                if (n <= 0) return if (len == 0) 0 else -1
                file.read(at, ByteBuffer.wrap(b, off, n))
                at += n
                return n
            }
        }

        override fun renameTo(name: String) {
            entry.setName(name)
        }

        override fun discard() {
            parent.remove(entry.name)
            flush()
        }
    }
}
