package com.jespersv.fat32app

import com.jespersv.fat32app.core.FatNames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FatNamesTest {
    @Test
    fun keepsOrdinaryNames() {
        assertEquals("Holiday photo 01.JPG", FatNames.sanitize("Holiday photo 01.JPG"))
        assertEquals(".gitignore", FatNames.sanitize(".gitignore"))
        assertEquals("räksmörgås.txt", FatNames.sanitize("räksmörgås.txt"))
    }

    @Test
    fun replacesCharactersFatForbids() {
        assertEquals("a_b_c_d_e_f_g_h_i_.txt", FatNames.sanitize("a\"b*c/d:e<f>g?h\\i|.txt"))
        assertEquals("tab_here", FatNames.sanitize("tab\there"))
    }

    @Test
    fun trimsTrailingDotsAndSpaces() {
        assertEquals("name", FatNames.sanitize("  name. . "))
        assertEquals("file", FatNames.sanitize(" .. "))
        assertEquals("file", FatNames.sanitize(""))
    }

    @Test
    fun avoidsReservedDeviceNames() {
        assertEquals("_CON", FatNames.sanitize("CON"))
        assertEquals("_nul.txt", FatNames.sanitize("nul.txt"))
        assertEquals("CONSOLE.txt", FatNames.sanitize("CONSOLE.txt"))
    }

    @Test
    fun shortensLongNamesButKeepsExtension() {
        val name = FatNames.sanitize("x".repeat(300) + ".mp4")
        assertEquals(255, name.length)
        assertTrue(name.endsWith(".mp4"))
    }

    @Test
    fun uniqueAddsCounterBeforeExtension() {
        assertEquals("a.txt", FatNames.unique("a.txt", listOf("b.txt")))
        assertEquals("a (2).txt", FatNames.unique("a.txt", listOf("A.TXT")))
        assertEquals("a (3).txt", FatNames.unique("a.txt", listOf("a.txt", "a (2).txt")))
        assertEquals("README (2)", FatNames.unique("README", listOf("readme")))
    }

    @Test
    fun tempNameIsShortEnough() {
        val temp = FatNames.tempName("y".repeat(255))
        assertTrue(temp.length <= 255)
        assertTrue(temp.endsWith(".part"))
        assertEquals("a.txt.part", FatNames.tempName("a.txt"))
    }
}
