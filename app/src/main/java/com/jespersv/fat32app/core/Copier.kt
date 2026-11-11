package com.jespersv.fat32app.core

import java.io.IOException
import java.io.InputStream
import java.util.zip.CRC32

/**
 * Copies files onto a [Volume].
 *
 * Each file is written under a ".part" name, flushed, optionally read back
 * and compared, and only then renamed to its real name. An interrupted copy
 * therefore never leaves something that looks like a finished file.
 */
class Copier(private val volume: Volume, private val bufferSize: Int = 1 shl 20) {

    interface Source {
        val name: String

        /** Size in bytes, or -1 when unknown. */
        val size: Long

        @Throws(IOException::class)
        fun open(): InputStream
    }

    enum class OnCollision { SKIP, OVERWRITE, KEEP_BOTH }

    sealed class Result {
        data class Copied(val finalName: String, val bytes: Long, val verified: Boolean) : Result()
        data class Skipped(val reason: String) : Result()
        data class Failed(val reason: String) : Result()
    }

    interface Listener {
        fun onFileStart(index: Int, name: String, size: Long)
        fun onProgress(index: Int, fileBytes: Long, totalBytes: Long, verifying: Boolean)
        fun onFileDone(index: Int, name: String, result: Result)
    }

    private class Cancelled : IOException("Cancelled")

    private val buf = ByteArray(bufferSize)

    /** Never throws: every outcome is reported per file. */
    fun copyAll(
        sources: List<Source>,
        destDir: List<String>,
        onCollision: OnCollision,
        verify: Boolean,
        listener: Listener,
        isCancelled: () -> Boolean,
    ): List<Result> {
        val results = ArrayList<Result>(sources.size)
        var existing: MutableList<Entry>? = null
        var fatal: String? = null
        var total = 0L

        try {
            existing = volume.list(destDir).toMutableList()
        } catch (e: Exception) {
            fatal = "cannot read the destination folder (${describe(e)})"
        }

        for ((i, src) in sources.withIndex()) {
            listener.onFileStart(i, src.name, src.size)
            val result: Result = when {
                fatal != null -> Result.Failed("Not copied: $fatal")
                isCancelled() -> Result.Skipped("Cancelled")
                else -> try {
                    copyOne(i, src, destDir, existing!!, onCollision, verify, listener, isCancelled, total)
                } catch (e: Cancelled) {
                    Result.Skipped("Cancelled")
                } catch (e: Exception) {
                    // Is the drive still there? If not, stop instead of failing every file slowly.
                    try {
                        existing = volume.list(destDir).toMutableList()
                    } catch (e2: Exception) {
                        fatal = "the drive stopped responding"
                    }
                    Result.Failed(describe(e))
                }
            }
            if (result is Result.Copied) total += result.bytes
            results += result
            listener.onFileDone(i, src.name, result)
        }

        try {
            volume.flush()
        } catch (e: Exception) {
            // Nothing more can be done here; per-file flushes already ran.
        }
        return results
    }

    private fun copyOne(
        index: Int,
        src: Source,
        destDir: List<String>,
        existing: MutableList<Entry>,
        onCollision: OnCollision,
        verify: Boolean,
        listener: Listener,
        isCancelled: () -> Boolean,
        totalBefore: Long,
    ): Result {
        if (src.size > volume.maxFileSize) {
            return Result.Failed("Too large for FAT: the limit is 4 GiB minus 1 byte per file")
        }
        var name = FatNames.sanitize(src.name)
        val clash = existing.firstOrNull { it.name.equals(name, ignoreCase = true) }
        if (clash != null) {
            when (onCollision) {
                OnCollision.SKIP -> return Result.Skipped("Already exists")
                OnCollision.KEEP_BOTH -> name = FatNames.unique(name, existing.map { it.name })
                OnCollision.OVERWRITE ->
                    if (clash.isDir) return Result.Failed("A folder with this name already exists")
            }
        }
        val free = volume.freeBytes()
        if (free != null && src.size > free) {
            return Result.Failed("Not enough free space on the drive")
        }

        val tempName = FatNames.tempName(name)
        existing.firstOrNull { it.name.equals(tempName, ignoreCase = true) }?.let { stale ->
            if (stale.isDir) return Result.Failed("A folder named $tempName is in the way")
            volume.delete(destDir, stale.name)
            existing.remove(stale)
        }

        val file = volume.create(destDir, tempName, src.size)
        var done = false
        try {
            val crc = CRC32()
            var written = 0L
            src.open().use { input ->
                while (true) {
                    if (isCancelled()) throw Cancelled()
                    val n = readFully(input)
                    if (n <= 0) break
                    if (written + n > volume.maxFileSize) {
                        throw IOException("Too large for FAT: the limit is 4 GiB minus 1 byte per file")
                    }
                    file.write(buf, 0, n)
                    crc.update(buf, 0, n)
                    written += n
                    listener.onProgress(index, written, totalBefore + written, false)
                    if (n < buf.size) break
                }
            }
            file.finish()

            if (verify) {
                val check = CRC32()
                var read = 0L
                file.openRead().use { input ->
                    while (true) {
                        if (isCancelled()) throw Cancelled()
                        val n = readFully(input)
                        if (n <= 0) break
                        check.update(buf, 0, n)
                        read += n
                        listener.onProgress(index, read, totalBefore + written, true)
                        if (n < buf.size) break
                    }
                }
                if (read != written || check.value != crc.value) {
                    throw IOException("Verification failed: the data read back from the drive differs")
                }
            }

            if (clash != null && onCollision == OnCollision.OVERWRITE) {
                volume.delete(destDir, clash.name)
                existing.remove(clash)
            }
            file.renameTo(name)
            volume.flush()
            existing += Entry(name, false, written)
            done = true
            return Result.Copied(name, written, verify)
        } finally {
            if (!done) {
                try {
                    file.discard()
                    volume.flush()
                } catch (e: Exception) {
                    // The drive is probably gone; the leftover is a .part file at worst.
                }
            }
        }
    }

    /** Fills [buf] completely unless the stream ends, so writes stay cluster-aligned. */
    private fun readFully(input: InputStream): Int {
        var filled = 0
        while (filled < buf.size) {
            val n = input.read(buf, filled, buf.size - filled)
            if (n < 0) break
            filled += n
        }
        return filled
    }

    private fun describe(e: Exception): String =
        e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
}
