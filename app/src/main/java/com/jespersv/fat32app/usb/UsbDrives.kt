package com.jespersv.fat32app.usb

import android.content.Context
import com.jespersv.fat32app.core.Volume
import me.jahnen.libaums.core.UsbMassStorageDevice
import me.jahnen.libaums.core.partition.PartitionTableFactory
import java.io.IOException

/** Finds USB mass storage devices and opens the FAT volume on them. */
object UsbDrives {

    class Opened(val device: UsbMassStorageDevice, val volume: Volume, val note: String?)

    fun find(context: Context): List<UsbMassStorageDevice> =
        UsbMassStorageDevice.getMassStorageDevices(context).toList()

    /**
     * Claims the USB device and reads its file system. Nothing is written to
     * the drive here. Needs USB permission; call from a worker thread.
     */
    @Throws(IOException::class)
    fun open(device: UsbMassStorageDevice): Opened {
        FatCreator.register()
        try {
            device.init()
            val partitions = device.partitions
            val fat = partitions.firstOrNull { it.fileSystem is FatLibFileSystem }
                ?: throw IOException(
                    "No FAT12, FAT16 or FAT32 partition was found on this drive. " +
                        "exFAT and NTFS are not supported."
                )
            val note = if (partitions.size > 1) {
                "The drive has ${partitions.size} partitions; using the first FAT one."
            } else null
            return Opened(device, FatVolume(fat.fileSystem as FatLibFileSystem), note)
        } catch (e: Exception) {
            runCatching { device.close() }
            throw IOException(explain(e), e)
        }
    }

    private fun explain(e: Exception): String {
        val message = e.message.orEmpty()
        return when {
            e is PartitionTableFactory.UnsupportedPartitionTableException ->
                "This drive has no partition table or file system the app understands. " +
                    "Only FAT12, FAT16 and FAT32 are supported."
            "differs from FAT" in message ->
                "The file system on this drive is damaged (its two allocation tables differ). " +
                    "Repair it on a computer first: chkdsk on Windows, fsck.fat on Linux."
            message.isBlank() -> e.javaClass.simpleName
            else -> message
        }
    }
}
