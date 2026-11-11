package com.jespersv.fat32app

import com.jespersv.fat32app.core.Copier
import com.jespersv.fat32app.core.Copier.OnCollision
import com.jespersv.fat32app.core.Copier.Result
import com.jespersv.fat32app.core.Entry
import com.jespersv.fat32app.core.NewFile
import com.jespersv.fat32app.core.Volume
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

class CopierTest {

    /** One flat folder kept in a map, with switches to simulate faults. */
    private class FakeVolume : Volume {
        val files = LinkedHashMap<String, ByteArray>()
        var free: Long? = null
        var corruptReads = false
        var failAfterBytes = Long.MAX_VALUE
        var dead = false
        override val title = "FAKE"
        override val fsName = "FAT16"
        override var maxFileSize = Long.MAX_VALUE

        override fun freeBytes(): Long? = free
        override fun list(dir: List<String>): List<Entry> {
            if (dead) throw IOException("gone")
            return files.map { Entry(it.key, false, it.value.size.toLong()) }
        }
        override fun mkdir(dir: List<String>, name: String) {}
        override fun delete(dir: List<String>, name: String) {
            files.remove(files.keys.first { it.equals(name, ignoreCase = true) })
        }
        override fun flush() {}
        override fun close() {}

        override fun create(dir: List<String>, name: String, size: Long): NewFile = object : NewFile {
            var current = name
            val data = ByteArrayOutputStream()
            init { files[current] = ByteArray(0) }
            override fun write(buf: ByteArray, off: Int, len: Int) {
                if (data.size() + len > failAfterBytes) { dead = true; throw IOException("write failed") }
                data.write(buf, off, len)
            }
            override fun finish() { files[current] = data.toByteArray() }
            override fun openRead(): InputStream {
                val copy = files.getValue(current).copyOf()
                if (corruptReads && copy.isNotEmpty()) copy[copy.size / 2] = (copy[copy.size / 2] + 1).toByte()
                return ByteArrayInputStream(copy)
            }
            override fun renameTo(name: String) {
                files[name] = files.remove(current)!!
                current = name
            }
            override fun discard() {
                if (dead) throw IOException("gone")
                files.remove(current)
            }
        }
    }

    private class Bytes(override val name: String, val data: ByteArray, override val size: Long = data.size.toLong()) :
        Copier.Source {
        override fun open(): InputStream = ByteArrayInputStream(data)
    }

    private object Quiet : Copier.Listener {
        override fun onFileStart(index: Int, name: String, size: Long) {}
        override fun onProgress(index: Int, fileBytes: Long, totalBytes: Long, verifying: Boolean) {}
        override fun onFileDone(index: Int, name: String, result: Result) {}
    }

    private fun data(n: Int, seed: Int = 1) = ByteArray(n) { (it * 31 + seed).toByte() }

    private fun run(
        v: Volume, sources: List<Copier.Source>, collision: OnCollision = OnCollision.SKIP,
        verify: Boolean = true, cancelled: () -> Boolean = { false },
    ) = Copier(v, bufferSize = 4096).copyAll(sources, emptyList(), collision, verify, Quiet, cancelled)

    @Test
    fun copiesAndLeavesNoTempFile() {
        val v = FakeVolume()
        val a = data(10_000)
        val results = run(v, listOf(Bytes("a.bin", a), Bytes("empty.txt", ByteArray(0))))
        assertEquals(Result.Copied("a.bin", 10_000, true), results[0])
        assertEquals(Result.Copied("empty.txt", 0, true), results[1])
        assertArrayEquals(a, v.files["a.bin"])
        assertEquals(setOf("a.bin", "empty.txt"), v.files.keys)
    }

    @Test
    fun skipLeavesExistingFileAlone() {
        val v = FakeVolume()
        v.files["A.BIN"] = data(5)
        val results = run(v, listOf(Bytes("a.bin", data(9, seed = 2))))
        assertEquals(Result.Skipped("Already exists"), results[0])
        assertArrayEquals(data(5), v.files["A.BIN"])
    }

    @Test
    fun overwriteReplacesOnlyAfterSuccessfulWrite() {
        val v = FakeVolume()
        v.files["a.bin"] = data(5)
        val fresh = data(9, seed = 2)
        assertTrue(run(v, listOf(Bytes("a.bin", fresh)), OnCollision.OVERWRITE)[0] is Result.Copied)
        assertArrayEquals(fresh, v.files["a.bin"])
        assertEquals(1, v.files.size)

        v.corruptReads = true
        assertTrue(run(v, listOf(Bytes("a.bin", data(20, seed = 3))), OnCollision.OVERWRITE)[0] is Result.Failed)
        assertArrayEquals(fresh, v.files["a.bin"])
        assertEquals(1, v.files.size)
    }

    @Test
    fun keepBothPicksNewName() {
        val v = FakeVolume()
        v.files["a.bin"] = data(5)
        val results = run(v, listOf(Bytes("a.bin", data(9)), Bytes("a.bin", data(7))), OnCollision.KEEP_BOTH)
        assertEquals("a (2).bin", (results[0] as Result.Copied).finalName)
        assertEquals("a (3).bin", (results[1] as Result.Copied).finalName)
    }

    @Test
    fun rejectsFilesOverTheFatLimitAndFullDrives() {
        val v = FakeVolume()
        v.maxFileSize = 100
        v.free = 50
        val results = run(v, listOf(Bytes("big.bin", data(101)), Bytes("mid.bin", data(60)), Bytes("ok.bin", data(50))))
        assertTrue((results[0] as Result.Failed).reason.contains("4 GiB"))
        assertTrue((results[1] as Result.Failed).reason.contains("free space"))
        assertTrue(results[2] is Result.Copied)
        assertEquals(setOf("ok.bin"), v.files.keys)
    }

    @Test
    fun sanitizesNames() {
        val v = FakeVolume()
        val results = run(v, listOf(Bytes("what?.txt", data(3))))
        assertEquals("what_.txt", (results[0] as Result.Copied).finalName)
    }

    @Test
    fun cancelRemovesPartialFileAndSkipsTheRest() {
        val v = FakeVolume()
        var polls = 0
        val results = run(v, listOf(Bytes("a.bin", data(50_000)), Bytes("b.bin", data(10))), cancelled = { ++polls > 3 })
        assertEquals(Result.Skipped("Cancelled"), results[0])
        assertEquals(Result.Skipped("Cancelled"), results[1])
        assertTrue(v.files.isEmpty())
    }

    @Test
    fun stopsQuicklyWhenTheDriveDisappears() {
        val v = FakeVolume()
        v.failAfterBytes = 8192
        val results = run(v, listOf(Bytes("a.bin", data(50_000)), Bytes("b.bin", data(10))))
        assertEquals(Result.Failed("write failed"), results[0])
        assertTrue((results[1] as Result.Failed).reason.contains("stopped responding"))
    }

    @Test
    fun removesStaleTempFileFromEarlierRun() {
        val v = FakeVolume()
        v.files["a.bin.part"] = data(3)
        assertTrue(run(v, listOf(Bytes("a.bin", data(9))))[0] is Result.Copied)
        assertEquals(setOf("a.bin"), v.files.keys)
    }

    @Test
    fun copesWithWrongReportedSize() {
        val v = FakeVolume()
        val results = run(v, listOf(Bytes("a.bin", data(9), size = -1), Bytes("b.bin", data(9), size = 500)))
        assertEquals(9L, (results[0] as Result.Copied).bytes)
        assertEquals(9L, (results[1] as Result.Copied).bytes)
        assertFalse(v.files.keys.any { it.endsWith(".part") })
    }
}
