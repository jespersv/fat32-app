package com.jespersv.fat32app

import de.waldheinz.fs.BlockDevice
import me.jahnen.libaums.core.driver.BlockDeviceDriver
import java.nio.ByteBuffer

/**
 * A disk held in memory. It is a libaums raw block driver (offsets counted in
 * blocks, as a USB drive presents itself) and can also be viewed as a
 * fat32-lib device for formatting and for checking results independently.
 */
class MemDisk(val bytes: ByteArray, override val blockSize: Int = 512) : BlockDeviceDriver {
    constructor(size: Int) : this(ByteArray(size))

    var writes = 0
        private set
    var failWrites = false

    override val blocks: Long get() = (bytes.size / blockSize).toLong()
    override fun init() {}

    override fun read(deviceOffset: Long, buffer: ByteBuffer) {
        require(buffer.remaining() % blockSize == 0) { "read is not a whole number of blocks" }
        buffer.put(bytes, (deviceOffset * blockSize).toInt(), buffer.remaining())
    }

    override fun write(deviceOffset: Long, buffer: ByteBuffer) {
        require(buffer.remaining() % blockSize == 0) { "write is not a whole number of blocks" }
        if (failWrites) throw java.io.IOException("drive unplugged")
        writes++
        buffer.get(bytes, (deviceOffset * blockSize).toInt(), buffer.remaining())
    }

    /** fat32-lib view of the bytes starting at [offset]. */
    fun asFatLibDevice(offset: Int = 0, length: Int = bytes.size - offset, readOnly: Boolean = false) =
        object : BlockDevice {
            override fun getSize(): Long = length.toLong()
            override fun getSectorSize(): Int = 512
            override fun flush() {}
            override fun close() {}
            override fun isClosed(): Boolean = false
            override fun isReadOnly(): Boolean = readOnly
            override fun read(devOffset: Long, dest: ByteBuffer) {
                dest.put(bytes, offset + devOffset.toInt(), dest.remaining())
            }
            override fun write(devOffset: Long, src: ByteBuffer) {
                src.get(bytes, offset + devOffset.toInt(), src.remaining())
            }
        }
}
