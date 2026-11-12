package com.jespersv.fat32app.usb

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import com.jespersv.fat32app.core.Diag
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
        val start = System.nanoTime()
        Diag.i("Opening ${describe(device.usbDevice, withNames = true)}")
        try {
            device.init()
            val partitions = device.partitions
            Diag.i("Partitions with a known file system: ${partitions.size}")
            for ((i, p) in partitions.withIndex()) {
                Diag.i("  #$i: ${p.fileSystem.javaClass.simpleName}, label '${p.volumeLabel}', ${p.fileSystem.capacity} bytes")
            }
            val fat = partitions.firstOrNull { it.fileSystem is FatLibFileSystem }
                ?: throw IOException(
                    "No FAT12, FAT16 or FAT32 partition was found on this drive. " +
                        "exFAT and NTFS are not supported."
                )
            val note = if (partitions.size > 1) {
                "The drive has ${partitions.size} partitions; using the first FAT one."
            } else null
            val volume = FatVolume(fat.fileSystem as FatLibFileSystem)
            Diag.i("Drive ready after ${(System.nanoTime() - start) / 1_000_000} ms")
            volume.describe().forEach { Diag.i("  $it") }
            return Opened(device, volume, note)
        } catch (e: Exception) {
            Diag.e("Opening the drive failed after ${(System.nanoTime() - start) / 1_000_000} ms", e)
            runCatching { device.close() }
            throw IOException(explain(e), e)
        }
    }

    /** One line about a USB device. Names need USB permission, so only ask for them once it is granted. */
    fun describe(d: UsbDevice, withNames: Boolean): String {
        val names = if (withNames) {
            runCatching { " '${d.manufacturerName.orEmpty()} ${d.productName.orEmpty()}'".replace("  ", " ") }
                .getOrDefault("")
        } else ""
        val interfaces = (0 until d.interfaceCount).joinToString("; ") { i ->
            val itf = d.getInterface(i)
            val endpoints = (0 until itf.endpointCount).joinToString(",") { e ->
                val ep = itf.getEndpoint(e)
                val dir = if (ep.direction == UsbConstants.USB_DIR_IN) "in" else "out"
                val type = when (ep.type) {
                    UsbConstants.USB_ENDPOINT_XFER_BULK -> "bulk"
                    UsbConstants.USB_ENDPOINT_XFER_INT -> "int"
                    UsbConstants.USB_ENDPOINT_XFER_ISOC -> "isoc"
                    else -> "ctrl"
                }
                "$type-$dir/${ep.maxPacketSize}"
            }
            "if$i class ${itf.interfaceClass}/${itf.interfaceSubclass}/${itf.interfaceProtocol} [$endpoints]"
        }
        return String.format("USB %04x:%04x%s %s (%s)", d.vendorId, d.productId, names, d.deviceName, interfaces)
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
