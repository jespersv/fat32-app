package com.jespersv.fat32app.core

enum class FatKind { FAT12, FAT16, FAT32 }

/**
 * Reads the BIOS parameter block of a FAT boot sector. The FAT variant is
 * decided by the cluster count, as the FAT specification requires.
 */
class FatProbe private constructor(
    val kind: FatKind,
    val bytesPerSector: Int,
    val sectorsPerCluster: Int,
    val reservedSectors: Int,
    val fatCount: Int,
    val sectorsPerFat: Long,
    val totalSectors: Long,
    val clusterCount: Long,
    private val fsInfoSector: Int,
) {
    val bytesPerCluster: Long get() = bytesPerSector.toLong() * sectorsPerCluster
    val fatOffsetBytes: Long get() = reservedSectors.toLong() * bytesPerSector
    val fatSizeBytes: Long get() = sectorsPerFat * bytesPerSector

    /** First byte after the last copy of the allocation table. */
    val fatEndBytes: Long get() = fatOffsetBytes + fatCount * fatSizeBytes

    /** Byte offset of the FAT32 FSInfo sector, or -1 if there is none. */
    val fsInfoOffsetBytes: Long get() = if (fsInfoSector > 0) fsInfoSector.toLong() * bytesPerSector else -1L
    val totalBytes: Long get() = totalSectors * bytesPerSector

    companion object {
        /** Returns null when [sector] is not a FAT boot sector. */
        fun parse(sector: ByteArray): FatProbe? {
            if (sector.size < 512) return null
            fun u8(i: Int) = sector[i].toInt() and 0xFF
            fun u16(i: Int) = u8(i) or (u8(i + 1) shl 8)
            fun u32(i: Int) = u16(i).toLong() or (u16(i + 2).toLong() shl 16)

            if (u8(510) != 0x55 || u8(511) != 0xAA) return null
            if (u8(0) != 0xEB && u8(0) != 0xE9) return null
            val bytesPerSector = u16(11)
            if (bytesPerSector != 512 && bytesPerSector != 1024 &&
                bytesPerSector != 2048 && bytesPerSector != 4096) return null
            val sectorsPerCluster = u8(13)
            if (sectorsPerCluster == 0 || sectorsPerCluster and (sectorsPerCluster - 1) != 0) return null
            val reserved = u16(14)
            if (reserved == 0) return null
            val fatCount = u8(16)
            if (fatCount != 1 && fatCount != 2) return null
            val rootEntries = u16(17)
            val fatSize16 = u16(22)
            val fatSize = if (fatSize16 != 0) fatSize16.toLong() else u32(36)
            val total16 = u16(19)
            val total = if (total16 != 0) total16.toLong() else u32(32)
            if (fatSize == 0L || total == 0L) return null
            val rootDirSectors = (rootEntries * 32L + bytesPerSector - 1) / bytesPerSector
            val dataSectors = total - (reserved + fatCount * fatSize + rootDirSectors)
            if (dataSectors <= 0) return null
            val clusters = dataSectors / sectorsPerCluster
            val kind = when {
                clusters < 4085 -> FatKind.FAT12
                clusters < 65525 -> FatKind.FAT16
                else -> FatKind.FAT32
            }
            // FAT12/16 have a fixed root directory and a 16-bit FAT size; FAT32 has neither.
            val looks1216 = fatSize16 != 0 && rootEntries != 0
            if ((kind == FatKind.FAT32) == looks1216) return null
            val fsInfo = if (kind == FatKind.FAT32) u16(48).takeIf { it in 1 until reserved } ?: 0 else 0
            return FatProbe(kind, bytesPerSector, sectorsPerCluster, reserved, fatCount, fatSize, total, clusters, fsInfo)
        }
    }
}
