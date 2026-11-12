package com.jespersv.fat32app.usb

import com.jespersv.fat32app.core.Diag
import com.jespersv.fat32app.core.FatKind
import com.jespersv.fat32app.core.FatProbe
import de.waldheinz.fs.fat.FatAccess
import de.waldheinz.fs.fat.FatFileSystem
import me.jahnen.libaums.core.driver.BlockDeviceDriver
import me.jahnen.libaums.core.fs.FileSystem
import me.jahnen.libaums.core.fs.FileSystemCreator
import me.jahnen.libaums.core.fs.FileSystemFactory
import me.jahnen.libaums.core.fs.UsbFile
import me.jahnen.libaums.core.partition.PartitionTableEntry
import me.jahnen.libaums.core.partition.PartitionTypes
import java.io.IOException
import java.nio.ByteBuffer

/**
 * libaums finds the USB drive and its partitions; fat32-lib does the FAT work.
 *
 * libaums has its own FAT32 code, but it only updates the first copy of the
 * allocation table, which leaves a drive that disk checkers flag as damaged,
 * and it has no FAT12/FAT16 support. So every FAT partition is claimed here
 * before libaums' own reader sees it.
 *
 * The object returned to libaums is only a carrier: the app takes [fat] and
 * [device] out of it and never uses the libaums file API on it.
 */
class FatLibFileSystem(
    val fat: FatFileSystem,
    val probe: FatProbe,
    val device: AumsBlockDevice,
) : FileSystem {
    override val rootDirectory: UsbFile
        get() = throw UnsupportedOperationException("Use FatVolume instead of the libaums file API")
    override val volumeLabel: String
        get() = fat.volumeLabel?.trim().orEmpty()
    override val capacity: Long
        get() = probe.totalBytes
    override val occupiedSpace: Long
        get() = capacity - freeSpace
    override val freeSpace: Long
        get() = FatAccess.freeClusters(fat) * probe.bytesPerCluster
    override val chunkSize: Int
        get() = probe.bytesPerCluster.toInt()
    override val type: Int
        get() = when (probe.kind) {
            FatKind.FAT12 -> PartitionTypes.FAT12
            FatKind.FAT16 -> PartitionTypes.FAT16
            FatKind.FAT32 -> PartitionTypes.FAT32
        }
}

object FatCreator : FileSystemCreator {
    private var registered = false

    /** Must run before the first drive is opened. Safe to call repeatedly. */
    @Synchronized
    fun register() {
        if (registered) return
        // The default priority sorts ahead of libaums' own FAT32 reader.
        FileSystemFactory.registerFileSystem(this)
        registered = true
    }

    override fun read(entry: PartitionTableEntry, blockDevice: BlockDeviceDriver): FileSystem? {
        val where = if (entry.logicalBlockAddress == 0L && entry.totalNumberOfSectors == 0L) {
            "whole device (no partition table)"
        } else {
            "partition type ${entry.partitionType}, start block ${entry.logicalBlockAddress}, " +
                "${entry.totalNumberOfSectors} blocks"
        }
        Diag.i("Looking for FAT: $where; device block size ${blockDevice.blockSize}, ${blockDevice.blocks} blocks")
        val first = ByteBuffer.allocate(maxOf(512, blockDevice.blockSize))
        blockDevice.read(0, first)
        val probe = FatProbe.parse(first.array())
        if (probe == null) {
            Diag.i("  Not a FAT boot sector: ${describeSector(first.array())}")
            return null
        }
        Diag.i(
            "  ${probe.kind}: ${probe.bytesPerSector} bytes/sector, ${probe.sectorsPerCluster} sectors/cluster, " +
                "${probe.clusterCount} clusters, ${probe.fatCount} FATs of ${probe.fatSizeBytes} bytes, " +
                "${probe.totalBytes} bytes total"
        )

        // fat32-lib holds the allocation table in memory: 8 bytes per cluster, and
        // two copies plus the raw bytes while it compares them when opening.
        val needed = probe.clusterCount * 16 + probe.fatSizeBytes * 2
        if (needed > Runtime.getRuntime().maxMemory() * 6 / 10) {
            throw IOException(
                "This volume has ${probe.clusterCount} clusters, too many for this app to hold in memory. " +
                    "Reformat it with a larger cluster size."
            )
        }
        val device = AumsBlockDevice(blockDevice, probe)
        val start = System.nanoTime()
        val fat = FatFileSystem.read(device, false)
        Diag.i("  Opened in ${(System.nanoTime() - start) / 1_000_000} ms. ${device.takeStats()}")
        return FatLibFileSystem(fat, probe, device)
    }

    /** Enough of a non-FAT first sector to tell an MBR, exFAT or NTFS apart in the log. */
    private fun describeSector(b: ByteArray): String {
        fun ascii(from: Int, len: Int) =
            String(b, from, len, Charsets.ISO_8859_1).map { if (it in ' '..'~') it else '.' }.joinToString("")
        val signature = (b[510].toInt() and 0xFF == 0x55) && (b[511].toInt() and 0xFF == 0xAA)
        val head = b.take(16).joinToString(" ") { String.format("%02X", it) }
        return "first bytes $head, OEM '${ascii(3, 8)}', text at 54 '${ascii(54, 8)}', " +
            "text at 82 '${ascii(82, 8)}', boot signature ${if (signature) "present" else "missing"}"
    }
}
