package de.waldheinz.fs.fat

/**
 * fat32-lib keeps the allocation table in memory but does not expose its free
 * cluster count publicly (FatFileSystem.getFreeSpace() is unimplemented and
 * returns -1). This file sits in the library's package so it can ask for it.
 */
object FatAccess {
    fun freeClusters(fs: FatFileSystem): Long = fs.fat.freeClusterCount.toLong()
}
