package com.jespersv.fat32app.core

import java.io.IOException
import java.io.InputStream

/** Largest file a FAT volume can hold: 4 GiB minus one byte. */
const val FAT_MAX_FILE_SIZE = 0xFFFF_FFFFL

data class Entry(val name: String, val isDir: Boolean, val size: Long)

/**
 * A place files can be copied to. Three implementations exist: FAT32 through
 * libaums, FAT12/FAT16 through fat32-lib, and a folder Android itself has
 * mounted (Storage Access Framework).
 *
 * A directory is addressed by its path segments from the volume root; the
 * empty list is the root. Implementations are not thread-safe: the app calls
 * them from one worker thread only.
 */
interface Volume {
    val title: String
    val fsName: String

    /** Largest single file this volume accepts, or [Long.MAX_VALUE] if unknown. */
    val maxFileSize: Long

    /** Free space in bytes, or null when it cannot be determined. */
    @Throws(IOException::class)
    fun freeBytes(): Long?

    @Throws(IOException::class)
    fun list(dir: List<String>): List<Entry>

    @Throws(IOException::class)
    fun mkdir(dir: List<String>, name: String)

    /**
     * Creates a new, empty file. [size] is the expected final size (or -1 if
     * unknown) and is used to reserve space up front.
     */
    @Throws(IOException::class)
    fun create(dir: List<String>, name: String, size: Long): NewFile

    @Throws(IOException::class)
    fun delete(dir: List<String>, name: String)

    /** Writes everything still held in memory to the drive. */
    @Throws(IOException::class)
    fun flush()

    fun close()
}

/** A file that is being written. Call order: write* -> finish -> (openRead) -> renameTo. */
interface NewFile {
    @Throws(IOException::class)
    fun write(buf: ByteArray, off: Int, len: Int)

    /** Sets the final length to the bytes written and sends all file data to the drive. */
    @Throws(IOException::class)
    fun finish()

    @Throws(IOException::class)
    fun openRead(): InputStream

    /** Gives the file its real name. Follow with [Volume.flush] to make it permanent. */
    @Throws(IOException::class)
    fun renameTo(name: String)

    /** Deletes the file again. Used when a copy fails or is cancelled. */
    @Throws(IOException::class)
    fun discard()
}
