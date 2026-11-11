package com.jespersv.fat32app.core

/** File-name rules for FAT long file names. */
object FatNames {
    const val MAX_LENGTH = 255
    private const val ILLEGAL = "\"*/:<>?\\|"
    private val RESERVED: Set<String> =
        setOf("CON", "PRN", "AUX", "NUL") + (1..9).map { "COM$it" } + (1..9).map { "LPT$it" }

    /** Returns a name FAT accepts, changing as little as possible. */
    fun sanitize(raw: String): String {
        val sb = StringBuilder(raw.length)
        for (c in raw) {
            sb.append(if (c.code < 0x20 || c.code == 0x7F || c in ILLEGAL) '_' else c)
        }
        var s = sb.toString().trim(' ').trimEnd('.', ' ')
        if (s.isEmpty()) s = "file"
        if (s.substringBefore('.').uppercase() in RESERVED) s = "_$s"
        if (s.length > MAX_LENGTH) {
            val (stem, ext) = split(s)
            s = cut(stem, MAX_LENGTH - ext.length) + ext
        }
        return s
    }

    /** Splits "photo.jpg" into "photo" and ".jpg". Long or missing extensions stay in the stem. */
    fun split(name: String): Pair<String, String> {
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || name.length - dot > 16) return name to ""
        return name.substring(0, dot) to name.substring(dot)
    }

    /** "photo.jpg" -> "photo (2).jpg", avoiding every name in [taken] (compared without case). */
    fun unique(name: String, taken: Collection<String>): String {
        if (taken.none { it.equals(name, ignoreCase = true) }) return name
        val (stem, ext) = split(name)
        var n = 2
        while (true) {
            val suffix = " ($n)"
            val candidate = cut(stem, MAX_LENGTH - ext.length - suffix.length) + suffix + ext
            if (taken.none { it.equals(candidate, ignoreCase = true) }) return candidate
            n++
        }
    }

    /** Name used while a file is still being written. */
    fun tempName(finalName: String): String = cut(finalName, 200) + ".part"

    private fun cut(s: String, max: Int): String {
        if (s.length <= max) return s
        var end = max.coerceAtLeast(1)
        if (Character.isHighSurrogate(s[end - 1])) end--
        return s.substring(0, end.coerceAtLeast(1)).trimEnd('.', ' ').ifEmpty { "_" }
    }
}
