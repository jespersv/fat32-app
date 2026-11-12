package com.jespersv.fat32app.usb

import com.jespersv.fat32app.core.Diag
import com.jespersv.fat32app.core.FatProbe
import de.waldheinz.fs.BlockDevice
import me.jahnen.libaums.core.driver.BlockDeviceDriver
import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * Lets fat32-lib use a libaums partition as its disk.
 *
 * [driver] must take byte offsets (a libaums ByteBlockDevice / Partition).
 * Every transfer handed to it starts and ends on a block boundary and is at
 * most 64 KiB. On top of plain pass-through this class does four things:
 *
 *  - Allocation table writes are skipped when the bytes are already on the
 *    drive. fat32-lib rewrites every copy of the whole table on each flush,
 *    which on a large FAT32 volume would be tens of megabytes per file.
 *  - File data that fat32-lib writes one cluster at a time is collected into
 *    64 KiB transfers.
 *  - Sequential reads of file data are read ahead 64 KiB at a time.
 *  - The FAT32 "free clusters" hint is replaced by a large number when read.
 *    On real drives it is often stale or marked unknown, and fat32-lib refuses
 *    to open a volume whose hint is lower than the true count. The correct
 *    value is written back on the first flush.
 */
class AumsBlockDevice(
    private val driver: BlockDeviceDriver,
    private val layout: FatProbe,
) : BlockDevice {

    private val blockSize = driver.blockSize
    private val chunk = maxOf(blockSize, (64 * 1024 / blockSize) * blockSize)
    private val fatStart = layout.fatOffsetBytes
    private val fatEnd = layout.fatEndBytes
    private val freeHint = layout.fsInfoOffsetBytes.let { if (it < 0) -1L else it + 0x1E8 }
    private var closed = false

    private val sha = MessageDigest.getInstance("SHA-256")
    private val onDrive = HashMap<Long, ByteArray>()

    private val pending = ByteArray(chunk)
    private var pendingStart = -1L
    private var pendingLength = 0

    private val ahead = ByteArray(chunk)
    private var aheadStart = -1L
    private var aheadLength = 0
    private var lastReadEnd = -1L

    /** Device transfers made, for tests. */
    var deviceWrites = 0
        private set

    // Counters for the diagnostics log, reset by takeStats().
    private var statWrites = 0
    private var statWriteBytes = 0L
    private var statWriteNanos = 0L
    private var statReads = 0
    private var statReadBytes = 0L
    private var statReadNanos = 0L
    private var statSkipped = 0
    private var statSlowest = 0L

    /** "USB writes: ... reads: ..." since the last call. */
    fun takeStats(): String {
        val text = "USB writes ${statWrites}x, ${Diag.rate(statWriteBytes, statWriteNanos)}; " +
            "reads ${statReads}x, ${Diag.rate(statReadBytes, statReadNanos)}; " +
            "unchanged table blocks skipped $statSkipped; slowest transfer ${statSlowest / 1_000_000} ms"
        statWrites = 0; statWriteBytes = 0; statWriteNanos = 0
        statReads = 0; statReadBytes = 0; statReadNanos = 0
        statSkipped = 0; statSlowest = 0
        return text
    }

    override fun getSize(): Long = layout.totalBytes
    override fun getSectorSize(): Int = layout.bytesPerSector
    override fun close() { closed = true }
    override fun isClosed(): Boolean = closed
    override fun isReadOnly(): Boolean = false

    /** Sends file data that is still being collected. */
    override fun flush() {
        if (pendingLength > 0) {
            val start = pendingStart
            val length = pendingLength
            pendingLength = 0
            pendingStart = -1
            writeNow(start, pending, 0, length)
        }
    }

    override fun read(devOffset: Long, dest: ByteBuffer) {
        val len = dest.remaining()
        if (len == 0) return
        flush()
        if (aheadStart >= 0 && devOffset >= aheadStart && devOffset + len <= aheadStart + aheadLength) {
            dest.put(ahead, (devOffset - aheadStart).toInt(), len)
            lastReadEnd = devOffset + len
            return
        }
        val sequential = devOffset == lastReadEnd
        lastReadEnd = devOffset + len
        if (sequential && devOffset >= fatEnd && devOffset % blockSize == 0L && len < chunk) {
            val want = minOf(chunk.toLong(), floor(layout.totalBytes - devOffset)).toInt()
            if (want >= len) {
                transfer(devOffset, ahead, 0, want, write = false)
                aheadStart = devOffset
                aheadLength = want
                dest.put(ahead, 0, len)
                return
            }
        }
        val start = floor(devOffset)
        val tmp = ByteArray((ceil(devOffset + len) - start).toInt())
        transfer(start, tmp, 0, tmp.size, write = false)
        if (freeHint >= 0) {
            // 0x7FFFFFFF, little-endian: the largest value fat32-lib reads as positive.
            for (i in 0 until 4) {
                val at = freeHint + i - start
                if (at >= 0 && at < tmp.size) tmp[at.toInt()] = (if (i == 3) 0x7F else 0xFF).toByte()
            }
        }
        dest.put(tmp, (devOffset - start).toInt(), len)
    }

    override fun write(devOffset: Long, src: ByteBuffer) {
        val len = src.remaining()
        if (len == 0) return
        aheadStart = -1
        if (devOffset >= fatEnd && len <= chunk) {
            if (pendingLength > 0 && devOffset == pendingStart + pendingLength && pendingLength + len <= chunk) {
                src.get(pending, pendingLength, len)
                pendingLength += len
                return
            }
            flush()
            if (devOffset % blockSize == 0L) {
                src.get(pending, 0, len)
                pendingStart = devOffset
                pendingLength = len
                return
            }
        }
        flush()
        val data = ByteArray(len)
        src.get(data)
        writeNow(devOffset, data, 0, len)
    }

    private fun writeNow(devOffset: Long, data: ByteArray, from: Int, len: Int) {
        val start = floor(devOffset)
        val end = ceil(devOffset + len)
        val tmp = ByteArray((end - start).toInt())
        // Partly covered blocks at either end keep their other bytes.
        if (devOffset != start) transfer(start, tmp, 0, blockSize, write = false)
        if (devOffset + len != end && (tmp.size > blockSize || devOffset == start)) {
            transfer(end - blockSize, tmp, tmp.size - blockSize, blockSize, write = false)
        }
        System.arraycopy(data, from, tmp, (devOffset - start).toInt(), len)
        transfer(start, tmp, 0, tmp.size, write = true)
    }

    private fun transfer(offset: Long, data: ByteArray, from: Int, length: Int, write: Boolean) {
        var done = 0
        while (done < length) {
            val n = minOf(chunk, length - done)
            val at = offset + done
            val piece = ByteArray(n)
            val inTable = at >= fatStart && at + n <= fatEnd
            if (write) {
                System.arraycopy(data, from + done, piece, 0, n)
                val digest = if (inTable) sha.digest(piece) else null
                if (digest == null || !digest.contentEquals(onDrive[at])) {
                    if (digest != null) onDrive.remove(at)
                    val start = System.nanoTime()
                    driver.write(at, ByteBuffer.wrap(piece))
                    val took = System.nanoTime() - start
                    deviceWrites++
                    statWrites++; statWriteBytes += n; statWriteNanos += took
                    noteTiming("write", at, n, took)
                    if (digest != null) onDrive[at] = digest
                } else {
                    statSkipped++
                }
            } else {
                val start = System.nanoTime()
                driver.read(at, ByteBuffer.wrap(piece))
                val took = System.nanoTime() - start
                statReads++; statReadBytes += n; statReadNanos += took
                noteTiming("read", at, n, took)
                System.arraycopy(piece, 0, data, from + done, n)
                if (inTable) onDrive[at] = sha.digest(piece)
            }
            done += n
        }
    }

    private fun noteTiming(kind: String, offset: Long, length: Int, nanos: Long) {
        if (nanos > statSlowest) statSlowest = nanos
        val ms = nanos / 1_000_000
        if (ms >= SLOW_MS) {
            Diag.w("Slow USB $kind: $length bytes at offset $offset took $ms ms")
        } else if (Diag.verbose) {
            Diag.v("USB $kind $length bytes at $offset: ${nanos / 1000} us")
        }
    }

    private fun floor(offset: Long) = offset - offset % blockSize
    private fun ceil(offset: Long) = floor(offset + blockSize - 1)

    private companion object {
        /** A single 64 KiB transfer taking this long points at a struggling drive or cable. */
        const val SLOW_MS = 1000L
    }
}
