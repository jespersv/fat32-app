package com.jespersv.fat32app

import com.jespersv.fat32app.core.Copier
import com.jespersv.fat32app.core.Copier.OnCollision
import com.jespersv.fat32app.core.Copier.Result
import com.jespersv.fat32app.core.FatKind
import com.jespersv.fat32app.core.FatProbe
import com.jespersv.fat32app.core.Volume
import com.jespersv.fat32app.usb.FatCreator
import com.jespersv.fat32app.usb.FatLibFileSystem
import com.jespersv.fat32app.usb.FatVolume
import de.waldheinz.fs.FsDirectory
import de.waldheinz.fs.fat.FatFileSystem
import de.waldheinz.fs.fat.FatType
import de.waldheinz.fs.fat.SuperFloppyFormatter
import me.jahnen.libaums.core.partition.Partition
import me.jahnen.libaums.core.partition.PartitionTableFactory
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.Random

/**
 * End-to-end tests against real FAT images held in memory. They follow the
 * same path as a USB drive in the app: libaums finds the partition, the app's
 * hook hands it to fat32-lib, and the copy engine writes through it. Results
 * are then read back with a freshly opened, read-only fat32-lib instance that
 * looks at the raw bytes directly, bypassing the app's block device layer.
 */
class DiskImageTest {

    private class Bytes(override val name: String, val data: ByteArray) : Copier.Source {
        override val size: Long get() = data.size.toLong()
        override fun open(): InputStream = ByteArrayInputStream(data)
    }

    private object Quiet : Copier.Listener {
        override fun onFileStart(index: Int, name: String, size: Long) {}
        override fun onProgress(index: Int, fileBytes: Long, totalBytes: Long, verifying: Boolean) {}
        override fun onFileDone(index: Int, name: String, result: Result) {}
    }

    private fun random(n: Int, seed: Long) = ByteArray(n).also { Random(seed).nextBytes(it) }

    private fun format(disk: MemDisk, type: FatType, offset: Int = 0, length: Int = disk.bytes.size - offset) {
        SuperFloppyFormatter.get(disk.asFatLibDevice(offset, length))
            .setFatType(type).setVolumeLabel("TESTDISK").format().close()
    }

    /** Opens the disk the way the app does and returns the volume it would use. */
    private fun open(disk: MemDisk): Volume {
        FatCreator.register()
        val table = PartitionTableFactory.createPartitionTable(disk)
        val partitions = table.partitionTableEntries.mapNotNull { Partition.createPartition(it, disk) }
        assertEquals(1, partitions.size)
        return FatVolume(partitions[0].fileSystem as FatLibFileSystem)
    }

    private fun readBack(disk: MemDisk, offset: Int, path: List<String>, name: String): ByteArray? {
        val fs = FatFileSystem.read(disk.asFatLibDevice(offset, readOnly = true), true)
        var dir: FsDirectory = fs.root
        for (segment in path) dir = dir.getEntry(segment).directory
        val entry = dir.getEntry(name) ?: return null
        val file = entry.file
        val out = ByteBuffer.allocate(file.length.toInt())
        file.read(0, out)
        return out.array()
    }

    private fun names(disk: MemDisk, offset: Int, path: List<String>): Set<String> {
        val fs = FatFileSystem.read(disk.asFatLibDevice(offset, readOnly = true), true)
        var dir: FsDirectory = fs.root
        for (segment in path) dir = dir.getEntry(segment).directory
        return dir.map { it.name }.filter { it != "." && it != ".." }.toSet()
    }

    private fun roundTrip(disk: MemDisk, offset: Int, expectedFs: String) {
        val volume = open(disk)
        assertEquals(expectedFs, volume.fsName)
        assertEquals("TESTDISK", volume.title)
        val freeBefore = volume.freeBytes()

        volume.mkdir(emptyList(), "Music")
        volume.mkdir(listOf("Music"), "Album one")
        val dir = listOf("Music", "Album one")

        val files = listOf(
            Bytes("empty.txt", ByteArray(0)),
            Bytes("tiny.txt", "hello".toByteArray()),
            Bytes("exactly one cluster.bin", random(4096, 1)),
            Bytes("A long file name with spaces and ünïcödé.dat", random(300_001, 2)),
            Bytes("big.bin", random(3 * 1024 * 1024 + 17, 3)),
        )
        val copier = Copier(volume, bufferSize = 256 * 1024)
        val results = copier.copyAll(files, dir, OnCollision.SKIP, true, Quiet) { false }
        for ((i, r) in results.withIndex()) {
            assertEquals(Result.Copied(files[i].name, files[i].size, true), r)
        }

        // Same names again: skipped, then stored next to the originals.
        assertTrue(copier.copyAll(files.take(2), dir, OnCollision.SKIP, true, Quiet) { false }
            .all { it is Result.Skipped })
        val again = copier.copyAll(listOf(Bytes("tiny.txt", "second".toByteArray())), dir,
            OnCollision.KEEP_BOTH, true, Quiet) { false }
        assertEquals("tiny (2).txt", (again[0] as Result.Copied).finalName)
        val replaced = random(70_000, 9)
        assertTrue(copier.copyAll(listOf(Bytes("BIG.BIN", replaced)), dir,
            OnCollision.OVERWRITE, true, Quiet) { false }[0] is Result.Copied)

        // A cancelled copy leaves nothing behind.
        var polls = 0
        val cancelled = copier.copyAll(listOf(Bytes("never.bin", random(2_000_000, 4))), dir,
            OnCollision.SKIP, true, Quiet) { ++polls > 2 }
        assertEquals(Result.Skipped("Cancelled"), cancelled[0])

        val listed = volume.list(dir).associate { it.name to it.size }
        assertEquals(300_001L, listed["A long file name with spaces and ünïcödé.dat"])
        val freeAfter = volume.freeBytes()
        if (freeBefore != null && freeAfter != null) assertTrue(freeAfter < freeBefore)
        volume.close()

        // Independent check of what is really on the disk.
        assertEquals(
            setOf("empty.txt", "tiny.txt", "tiny (2).txt", "exactly one cluster.bin",
                "A long file name with spaces and ünïcödé.dat", "BIG.BIN"),
            names(disk, offset, dir).map { if (it.equals("big.bin", true)) "BIG.BIN" else it }.toSet()
        )
        for (f in files.dropLast(1)) assertArrayEquals(f.name, f.data, readBack(disk, offset, dir, f.name))
        assertArrayEquals("second".toByteArray(), readBack(disk, offset, dir, "tiny (2).txt"))
        assertArrayEquals(replaced, readBack(disk, offset, dir, "big.bin"))
        assertNull(readBack(disk, offset, dir, "never.bin"))
        assertNull(readBack(disk, offset, dir, "never.bin.part"))
    }

    @Test
    fun fat16WithoutPartitionTable() {
        val disk = MemDisk(32 * 1024 * 1024)
        format(disk, FatType.FAT16)
        assertEquals(FatKind.FAT16, FatProbe.parse(disk.bytes.copyOf(512))!!.kind)
        roundTrip(disk, 0, "FAT16")
    }

    @Test
    fun fat12WithoutPartitionTable() {
        val disk = MemDisk(8 * 1024 * 1024)
        format(disk, FatType.FAT12)
        assertEquals(FatKind.FAT12, FatProbe.parse(disk.bytes.copyOf(512))!!.kind)
        roundTrip(disk, 0, "FAT12")
    }

    @Test
    fun fat32WithoutPartitionTable() {
        val disk = MemDisk(64 * 1024 * 1024)
        format(disk, FatType.FAT32)
        assertEquals(FatKind.FAT32, FatProbe.parse(disk.bytes.copyOf(512))!!.kind)
        roundTrip(disk, 0, "FAT32")
    }

    @Test
    fun fat16InsideMbrPartition() {
        val start = 2048
        val sectors = 32 * 1024 * 2
        val disk = MemDisk((start + sectors) * 512)
        format(disk, FatType.FAT16, start * 512, sectors * 512)
        writeMbr(disk, type = 0x06, start = start, sectors = sectors)
        assertNull(FatProbe.parse(disk.bytes.copyOf(512)))
        roundTrip(disk, start * 512, "FAT16")
    }

    @Test
    fun fat32InsideMbrPartition() {
        val start = 2048
        val sectors = 64 * 1024 * 2
        val disk = MemDisk((start + sectors) * 512)
        format(disk, FatType.FAT32, start * 512, sectors * 512)
        writeMbr(disk, type = 0x0C, start = start, sectors = sectors)
        roundTrip(disk, start * 512, "FAT32")
    }

    @Test
    fun fullDriveIsReportedAndLeavesNoTempFile() {
        for (type in listOf(FatType.FAT16, FatType.FAT32)) {
            val disk = MemDisk(if (type == FatType.FAT16) 16 * 1024 * 1024 else 40 * 1024 * 1024)
            format(disk, type)
            val volume = open(disk)
            val tooBig = Bytes("huge.bin", ByteArray(48 * 1024 * 1024))
            val result = Copier(volume).copyAll(listOf(tooBig), emptyList(), OnCollision.SKIP, false, Quiet) { false }[0]
            assertTrue("$type: $result", result is Result.Failed)
            val ok = Copier(volume).copyAll(listOf(Bytes("small.bin", random(50_000, 5))), emptyList(),
                OnCollision.SKIP, true, Quiet) { false }[0]
            assertTrue("$type: $ok", ok is Result.Copied)
            volume.close()
            assertEquals(setOf("small.bin"), names(disk, 0, emptyList()))
        }
    }

    @Test
    fun unplugDuringCopyFailsCleanly() {
        for (type in listOf(FatType.FAT16, FatType.FAT32)) {
            val disk = MemDisk(if (type == FatType.FAT16) 16 * 1024 * 1024 else 40 * 1024 * 1024)
            format(disk, type)
            val volume = open(disk)
            val copier = Copier(volume, bufferSize = 64 * 1024)
            var progress = 0
            val listener = object : Copier.Listener {
                override fun onFileStart(index: Int, name: String, size: Long) {}
                override fun onFileDone(index: Int, name: String, result: Result) {}
                override fun onProgress(index: Int, fileBytes: Long, totalBytes: Long, verifying: Boolean) {
                    if (++progress == 4) disk.failWrites = true
                }
            }
            val results = copier.copyAll(
                listOf(Bytes("first.bin", random(1_000_000, 6)), Bytes("second.bin", random(10, 7))),
                emptyList(), OnCollision.SKIP, true, listener) { false }
            assertTrue("$type: ${results[0]}", results[0] is Result.Failed)
            assertTrue("$type: ${results[1]}", results[1] is Result.Failed)
            assertNotNull(results)
            // Whatever is left must not carry the final name.
            assertTrue(names(disk, 0, emptyList()).none { it.equals("first.bin", true) || it.equals("second.bin", true) })
        }
    }

    @Test
    fun openingAndListingWritesNothing() {
        for (type in listOf(FatType.FAT12, FatType.FAT16, FatType.FAT32)) {
            val disk = MemDisk(if (type == FatType.FAT32) 40 * 1024 * 1024 else 8 * 1024 * 1024)
            format(disk, type)
            val before = disk.bytes.copyOf()
            val volume = open(disk)
            volume.list(emptyList())
            volume.freeBytes()
            volume.close()
            assertEquals("$type", 0, disk.writes)
            assertArrayEquals(before, disk.bytes)
        }
    }

    @Test
    fun staleFat32FreeSpaceHintIsToleratedAndCorrected() {
        val disk = MemDisk(40 * 1024 * 1024)
        format(disk, FatType.FAT32)
        val hint = ByteBuffer.wrap(disk.bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        // A count that is far too low, as left behind by devices that never update it.
        for (stale in listOf(5, -1)) {
            hint.putInt(512 + 0x1E8, stale)
            val volume = open(disk)
            assertTrue(volume.freeBytes()!! > 30L * 1024 * 1024)
            val result = Copier(volume).copyAll(listOf(Bytes("f$stale.bin", random(9000, 8))), emptyList(),
                OnCollision.SKIP, true, Quiet) { false }[0]
            assertTrue("$result", result is Result.Copied)
            volume.close()
            val real = volume.freeBytes()!! / 512
            assertEquals(real, hint.getInt(512 + 0x1E8).toLong())
            // A strict reader accepts the volume again.
            assertTrue(names(disk, 0, emptyList()).contains("f$stale.bin"))
        }
    }

    @Test
    fun largeFilesAreSentInFewLargeTransfers() {
        val disk = MemDisk(64 * 1024 * 1024)
        format(disk, FatType.FAT32)
        val volume = open(disk)
        val size = 16 * 1024 * 1024
        val result = Copier(volume).copyAll(listOf(Bytes("big.bin", random(size, 10))), emptyList(),
            OnCollision.SKIP, false, Quiet) { false }[0]
        assertTrue("$result", result is Result.Copied)
        // 16 MiB in 64 KiB pieces is 256 transfers; the rest is directory and table updates.
        assertTrue("writes: ${disk.writes}", disk.writes < 256 + 60)
    }

    @Test
    fun damagedAllocationTableIsRefused() {
        val disk = MemDisk(16 * 1024 * 1024)
        format(disk, FatType.FAT16)
        val probe = FatProbe.parse(disk.bytes.copyOf(512))!!
        disk.bytes[(probe.fatOffsetBytes + probe.fatSizeBytes + 40).toInt()] = 0x55
        var message = ""
        try {
            open(disk)
        } catch (e: java.io.IOException) {
            message = e.message.orEmpty()
        }
        assertTrue(message, message.contains("differs"))
        assertEquals(0, disk.writes)
    }

    @Test
    fun otherFileSystemsAreNotMistakenForFat() {
        val ntfs = ByteArray(512)
        ntfs[0] = 0xEB.toByte(); ntfs[1] = 0x52; ntfs[2] = 0x90.toByte()
        "NTFS    ".toByteArray().copyInto(ntfs, 3)
        ntfs[11] = 0; ntfs[12] = 2; ntfs[13] = 8
        ntfs[510] = 0x55; ntfs[511] = 0xAA.toByte()
        assertNull(FatProbe.parse(ntfs))

        val exfat = ByteArray(512)
        exfat[0] = 0xEB.toByte(); exfat[1] = 0x76; exfat[2] = 0x90.toByte()
        "EXFAT   ".toByteArray().copyInto(exfat, 3)
        exfat[510] = 0x55; exfat[511] = 0xAA.toByte()
        assertNull(FatProbe.parse(exfat))
        assertNull(FatProbe.parse(ByteArray(512)))
    }

    private fun writeMbr(disk: MemDisk, type: Int, start: Int, sectors: Int) {
        val b = ByteBuffer.wrap(disk.bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        b.put(0, 0x33.toByte()).put(1, 0xC0.toByte())
        b.put(446 + 4, type.toByte())
        b.putInt(446 + 8, start)
        b.putInt(446 + 12, sectors)
        b.put(510, 0x55.toByte()).put(511, 0xAA.toByte())
    }
}
