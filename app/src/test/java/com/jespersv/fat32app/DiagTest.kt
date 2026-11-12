package com.jespersv.fat32app

import com.jespersv.fat32app.core.Diag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

class DiagTest {

    private fun fresh(maxBytes: Long = 1_000_000): File {
        val dir = Files.createTempDirectory("diag").toFile()
        Diag.verbose = false
        Diag.maxBytes = maxBytes
        Diag.init(dir)
        Diag.clear()
        return dir
    }

    private fun lines(dir: File) =
        listOf(Diag.OLD_FILE, Diag.FILE).map { File(dir, it) }.filter { it.exists() }.flatMap { it.readLines() }

    @Test
    fun writesToFileAndMemory() {
        val dir = fresh()
        Diag.i("hello")
        Diag.w("careful")
        val onDisk = lines(dir)
        assertEquals(2, onDisk.size)
        assertTrue(onDisk[0], onDisk[0].contains(" I ") && onDisk[0].endsWith(": hello"))
        assertTrue(onDisk[1], onDisk[1].contains(" W "))
        assertEquals(onDisk, Diag.recent())
    }

    @Test
    fun rotatesAndKeepsOneOldFile() {
        val dir = fresh(maxBytes = 2000)
        repeat(300) { Diag.i("line number $it with some padding to fill the file") }
        assertTrue(File(dir, Diag.OLD_FILE).exists())
        assertTrue(File(dir, Diag.FILE).length() < 2000)
        assertTrue(File(dir, Diag.OLD_FILE).length() in 2000..2200)
        assertTrue(lines(dir).last().endsWith("line number 299 with some padding to fill the file"))
        assertEquals(2, Diag.files().size)
    }

    @Test
    fun verboseLinesOnlyWhenSwitchedOn() {
        val dir = fresh()
        Diag.v("hidden")
        Diag.verbose = true
        Diag.v("shown")
        Diag.verbose = false
        assertEquals(1, lines(dir).size)
        assertTrue(lines(dir)[0].endsWith(": shown"))
    }

    @Test
    fun errorsIncludeTheStackTrace() {
        val dir = fresh()
        Diag.e("it broke", IOException("disk gone", IllegalStateException("root cause")))
        val text = lines(dir).joinToString("\n")
        assertTrue(text, text.contains("it broke"))
        assertTrue(text, text.contains("java.io.IOException: disk gone"))
        assertTrue(text, text.contains("Caused by: java.lang.IllegalStateException: root cause"))
        assertTrue(text, text.contains("at com.jespersv.fat32app.DiagTest"))
    }

    @Test
    fun manyThreadsNeverMixLines() {
        val dir = fresh()
        val threads = (0 until 8).map { t ->
            Thread { repeat(500) { Diag.i("thread $t message $it end") } }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        val all = lines(dir)
        assertEquals(4000, all.size)
        val pattern = Regex(""".* I Thread-\d+: thread (\d) message (\d+) end""")
        assertTrue(all.all { pattern.matches(it) })
        for (t in 0 until 8) assertEquals(500, all.count { it.contains("thread $t message") })
    }

    @Test
    fun clearRemovesEverything() {
        val dir = fresh(maxBytes = 500)
        repeat(50) { Diag.i("filler line $it") }
        Diag.clear()
        assertFalse(File(dir, Diag.OLD_FILE).exists())
        assertEquals(0L, File(dir, Diag.FILE).length())
        assertTrue(Diag.recent().isEmpty())
        Diag.i("after")
        assertEquals(1, lines(dir).size)
    }

    @Test
    fun rateIsReadable() {
        assertEquals("10.00 MB in 2.00 s (5.0 MB/s)", Diag.rate(10L * 1024 * 1024, 2_000_000_000L))
    }
}
