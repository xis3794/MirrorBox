package io.github.xis3794.mirrorbox.qcow2.disk

import java.util.zip.CRC32

/**
 * MBR / GPT partition table parsing and generation.
 *
 * This is what powers the "分区图" preview and the "生成带分区的磁盘" wizard: the app never needs a
 * VM, root or loop device to understand or create the disk layout — everything is plain byte math.
 */
const val SECTOR_SIZE: Long = 512L

data class PartitionEntry(
    val index: Int,
    val scheme: String,
    val typeId: String,
    val typeName: String,
    val name: String,
    val startLba: Long,
    val sectorCount: Long,
    val bootable: Boolean,
) {
    val startByte: Long get() = startLba * SECTOR_SIZE
    val sizeBytes: Long get() = sectorCount * SECTOR_SIZE
    val endByte: Long get() = startByte + sizeBytes - 1
}

data class PartitionTableInfo(
    val scheme: String,
    val diskSizeBytes: Long,
    val partitions: List<PartitionEntry>,
    val diskGuid: String? = null,
    val protectiveMbr: Boolean = false,
    val backupHeaderLba: Long? = null,
) {
    val hasTable: Boolean get() = partitions.isNotEmpty()
}

/** A partition to be created. */
data class NewPartition(
    val startLba: Long,
    val sectorCount: Long,
    val typeId: Int,
    val bootable: Boolean = false,
    val name: String = "",
    val typeGuid: String = Guid.LINUX_FILESYSTEM,
) {
    val endLba: Long get() = startLba + sectorCount - 1
}

object Guid {
    const val EFI_SYSTEM = "C12A7328-F81F-11D2-BA4B-00A0C93EC93B"
    const val LINUX_FILESYSTEM = "0FC63DAF-8483-4772-8E79-3D69D8477DE4"
    const val LINUX_SWAP = "0657FD6D-A4AB-43C4-84E5-0933C84B4F4F"
    const val LINUX_LVM = "E6D6D379-F507-44C2-A23C-238F2A3DF928"
    const val MICROSOFT_BASIC_DATA = "EBD0A0A2-B9E5-4433-87C0-68B6B72699C7"
    const val BIOS_BOOT = "21686148-6449-6E6F-744E-656564454649"
    const val EMPTY = "00000000-0000-0000-0000-000000000000"

    fun typeNameFor(guid: String): String = when (guid.uppercase()) {
        EFI_SYSTEM -> "EFI 系统分区"
        LINUX_FILESYSTEM -> "Linux 文件系统"
        LINUX_SWAP -> "Linux swap"
        LINUX_LVM -> "Linux LVM"
        MICROSOFT_BASIC_DATA -> "Microsoft 基本数据 (NTFS/exFAT)"
        BIOS_BOOT -> "BIOS 启动分区"
        else -> "其他"
    }

    /** Encodes a canonical GUID string into the on-disk (mixed endian) 16 byte layout. */
    fun encode(guid: String): ByteArray {
        val hex = guid.replace("-", "")
        require(hex.length == 32) { "非法的 GUID：$guid" }
        val raw = ByteArray(16) { ((hex.substring(it * 2, it * 2 + 2).toInt(16)) and 0xff).toByte() }
        val out = ByteArray(16)
        out[0] = raw[3]; out[1] = raw[2]; out[2] = raw[1]; out[3] = raw[0] // first 4 bytes LE
        out[4] = raw[5]; out[5] = raw[4] // next 2 bytes LE
        out[6] = raw[7]; out[7] = raw[6] // next 2 bytes LE
        System.arraycopy(raw, 8, out, 8, 8) // rest big endian
        return out
    }

    fun decode(bytes: ByteArray, offset: Int): String {
        val raw = ByteArray(16)
        raw[0] = bytes[offset + 3]; raw[1] = bytes[offset + 2]
        raw[2] = bytes[offset + 1]; raw[3] = bytes[offset]
        raw[4] = bytes[offset + 5]; raw[5] = bytes[offset + 4]
        raw[6] = bytes[offset + 7]; raw[7] = bytes[offset + 6]
        System.arraycopy(bytes, offset + 8, raw, 8, 8)
        val sb = StringBuilder(36)
        for (i in 0 until 16) {
            if (i == 4 || i == 6 || i == 8 || i == 10) sb.append('-')
            sb.append(String.format("%02X", raw[i].toInt() and 0xff))
        }
        return sb.toString()
    }

    fun random(): String {
        val rnd = java.util.Random()
        val b = ByteArray(16)
        rnd.nextBytes(b)
        return decode(b, 0)
    }
}

object MbrType {
    fun nameFor(typeId: Int): String = when (typeId and 0xff) {
        0x00 -> "空"
        0x05, 0x0f, 0x85 -> "扩展分区"
        0x07 -> "NTFS / exFAT / HPFS"
        0x0b, 0x0c -> "FAT32"
        0x04, 0x06, 0x0e -> "FAT16"
        0x01 -> "FAT12"
        0x82 -> "Linux swap"
        0x83 -> "Linux"
        0x8e -> "Linux LVM"
        0xee -> "GPT 保护分区"
        0xef -> "EFI 系统分区"
        0xa5 -> "FreeBSD"
        0xaf -> "HFS / HFS+"
        else -> "0x%02X".format(typeId and 0xff)
    }
}

object PartitionTables {

    fun readSector(read: (offset: Long, length: Int) -> ByteArray, lba: Long): ByteArray =
        read(lba * SECTOR_SIZE, SECTOR_SIZE.toInt())

    /** Parses MBR or GPT. Returns an empty table when no partition table signature is found. */
    fun parse(read: (offset: Long, length: Int) -> ByteArray, diskSizeBytes: Long): PartitionTableInfo {
        val mbr = try {
            read(0L, SECTOR_SIZE.toInt())
        } catch (t: Throwable) {
            return PartitionTableInfo("none", diskSizeBytes, emptyList())
        }
        if (mbr.size < 512) return PartitionTableInfo("none", diskSizeBytes, emptyList())
        val signature = (mbr[510].toInt() and 0xff shl 8) or (mbr[511].toInt() and 0xff)
        if (signature != 0x55AA) return PartitionTableInfo("none", diskSizeBytes, emptyList())

        val firstType = mbr[446 + 4].toInt() and 0xff
        if (firstType == 0xEE) {
            val gpt = parseGpt(read, diskSizeBytes)
            if (gpt != null) return gpt
        }
        return parseMbr(mbr, diskSizeBytes)
    }

    private fun parseMbr(mbr: ByteArray, diskSizeBytes: Long): PartitionTableInfo {
        val parts = ArrayList<PartitionEntry>(4)
        for (i in 0 until 4) {
            val off = 446 + i * 16
            val typeId = mbr[off + 4].toInt() and 0xff
            val startLba = leU32(mbr, off + 8)
            val sectors = leU32(mbr, off + 12)
            if (typeId == 0 || sectors == 0L) continue
            parts.add(
                PartitionEntry(
                    index = i + 1,
                    scheme = "MBR",
                    typeId = "0x%02X".format(typeId),
                    typeName = MbrType.nameFor(typeId),
                    name = "",
                    startLba = startLba,
                    sectorCount = sectors,
                    bootable = (mbr[off].toInt() and 0xff) == 0x80,
                ),
            )
        }
        return PartitionTableInfo("MBR", diskSizeBytes, parts)
    }

    private fun parseGpt(read: (offset: Long, length: Int) -> ByteArray, diskSizeBytes: Long): PartitionTableInfo? {
        val header = try {
            read(SECTOR_SIZE, 92)
        } catch (t: Throwable) {
            return null
        }
        if (header.size < 92) return null
        if (String(header, 0, 8, Charsets.US_ASCII) != "EFI PART") return null
        val entriesLba = leU64(header, 72)
        val entryCount = leU32(header, 80).toInt()
        val entrySize = leU32(header, 84).toInt()
        val backupLba = leU64(header, 32)
        if (entryCount <= 0 || entryCount > 512 || entrySize < 128) return null

        val total = entryCount * entrySize
        val bytes = try {
            read(entriesLba * SECTOR_SIZE, total)
        } catch (t: Throwable) {
            return null
        }
        val parts = ArrayList<PartitionEntry>()
        for (i in 0 until entryCount) {
            val off = i * entrySize
            if (off + 128 > bytes.size) break
            val typeGuid = Guid.decode(bytes, off)
            if (typeGuid == Guid.EMPTY) continue
            val uniqueGuid = Guid.decode(bytes, off + 16)
            val firstLba = leU64(bytes, off + 32)
            val lastLba = leU64(bytes, off + 40)
            val nameChars = ArrayList<Char>()
            for (c in 0 until 36) {
                val lo = bytes[off + 56 + c * 2].toInt() and 0xff
                val hi = bytes[off + 56 + c * 2 + 1].toInt() and 0xff
                val code = (hi shl 8) or lo
                if (code == 0) break
                nameChars.add(code.toChar())
            }
            parts.add(
                PartitionEntry(
                    index = i + 1,
                    scheme = "GPT",
                    typeId = typeGuid,
                    typeName = Guid.typeNameFor(typeGuid),
                    name = String(nameChars.toCharArray()).trim(),
                    startLba = firstLba,
                    sectorCount = (lastLba - firstLba + 1).coerceAtLeast(0L),
                    bootable = false,
                ),
            )
        }
        val diskGuid = Guid.decode(header, 56)
        return PartitionTableInfo(
            scheme = "GPT",
            diskSizeBytes = diskSizeBytes,
            partitions = parts,
            diskGuid = diskGuid,
            protectiveMbr = true,
            backupHeaderLba = backupLba,
        )
    }

    // ------------------------------------------------------------------ generation

    /** Builds a 512 byte MBR (LBA addressed, CHS fields filled with the classic "out of range" values). */
    fun buildMbr(partitions: List<NewPartition>, diskSignature: Int = 0): ByteArray {
        val b = ByteArray(512)
        for ((i, p) in partitions.take(4).withIndex()) {
            val off = 446 + i * 16
            b[off] = if (p.bootable) 0x80.toByte() else 0x00
            b[off + 1] = 0x00; b[off + 2] = 0x02; b[off + 3] = 0x00 // start CHS (best effort)
            b[off + 4] = (p.typeId and 0xff).toByte()
            b[off + 5] = 0xFE.toByte(); b[off + 6] = 0xFF.toByte(); b[off + 7] = 0xFF.toByte()
            putLe32(b, off + 8, p.startLba)
            putLe32(b, off + 12, p.sectorCount)
        }
        putLe32(b, 440, diskSignature.toLong() and 0xffffffffL)
        b[510] = 0x55
        b[511] = 0xAA.toByte()
        return b
    }

    /** Builds a protective MBR used by GPT disks. */
    fun buildProtectiveMbr(diskSectors: Long): ByteArray {
        val sectors = minOf(diskSectors - 1L, 0xFFFFFFFFL).coerceAtLeast(1L)
        return buildMbr(listOf(NewPartition(1L, sectors, 0xEE)), 0)
    }

    /**
     * Builds the complete GPT: primary header (LBA1), entry array (LBA2..33), backup entry array and
     * backup header (last two areas). The caller writes these bytes into the image at their LBAs.
     */
    fun buildGpt(partitions: List<NewPartition>, diskSectors: Long, diskGuid: String = Guid.random()): GptLayout {
        val entryCount = 128
        val entrySize = 128
        val entriesBytes = ByteArray(entryCount * entrySize)
        for ((i, p) in partitions.withIndex()) {
            if (i >= entryCount) break
            val off = i * entrySize
            System.arraycopy(Guid.encode(p.typeGuid), 0, entriesBytes, off, 16)
            System.arraycopy(Guid.encode(Guid.random()), 0, entriesBytes, off + 16, 16)
            putLe64(entriesBytes, off + 32, p.startLba)
            putLe64(entriesBytes, off + 40, p.endLba)
            putLe64(entriesBytes, off + 48, 0L)
            val name = p.name.take(35)
            for (c in name.indices) {
                val code = name[c].code
                entriesBytes[off + 56 + c * 2] = (code and 0xff).toByte()
                entriesBytes[off + 56 + c * 2 + 1] = ((code shr 8) and 0xff).toByte()
            }
        }
        val entriesCrc = crc32(entriesBytes)

        val entriesSectors = ((entryCount * entrySize) + 511) / 512
        val firstUsable = 2L + entriesSectors
        val lastUsable = diskSectors - 2L - entriesSectors
        val backupEntriesLba = diskSectors - 1L - entriesSectors
        val backupHeaderLba = diskSectors - 1L

        val header = ByteArray(512)
        System.arraycopy("EFI PART".toByteArray(Charsets.US_ASCII), 0, header, 0, 8)
        putLe32(header, 8, 0x00010000L)
        putLe32(header, 12, 92L)
        putLe32(header, 16, 0L) // crc filled below
        putLe32(header, 20, 0L)
        putLe64(header, 24, 1L) // current LBA
        putLe64(header, 32, backupHeaderLba)
        putLe64(header, 40, firstUsable)
        putLe64(header, 48, lastUsable)
        System.arraycopy(Guid.encode(diskGuid), 0, header, 56, 16)
        putLe64(header, 72, 2L) // entries LBA
        putLe32(header, 80, entryCount.toLong())
        putLe32(header, 84, entrySize.toLong())
        putLe32(header, 88, entriesCrc)
        putLe32(header, 16, crc32(header, 0, 92))

        val backupHeader = header.copyOf()
        putLe64(backupHeader, 24, backupHeaderLba)
        putLe64(backupHeader, 32, 1L)
        putLe64(backupHeader, 72, backupEntriesLba)
        putLe32(backupHeader, 16, 0L)
        putLe32(backupHeader, 16, crc32(backupHeader, 0, 92))

        return GptLayout(
            protectiveMbr = buildProtectiveMbr(diskSectors),
            primaryHeader = header,
            entries = entriesBytes,
            primaryEntriesLba = 2L,
            backupHeader = backupHeader,
            backupHeaderLba = backupHeaderLba,
            backupEntries = entriesBytes,
            backupEntriesLba = backupEntriesLba,
        )
    }

    data class GptLayout(
        val protectiveMbr: ByteArray,
        val primaryHeader: ByteArray,
        val entries: ByteArray,
        val primaryEntriesLba: Long,
        val backupHeader: ByteArray,
        val backupHeaderLba: Long,
        val backupEntries: ByteArray,
        val backupEntriesLba: Long,
    )

    // ------------------------------------------------------------------ helpers

    fun crc32(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size): Long {
        val crc = CRC32()
        crc.update(bytes, offset, length)
        return crc.value
    }

    private fun leU32(b: ByteArray, off: Int): Long {
        return ((b[off].toLong() and 0xff) or
            ((b[off + 1].toLong() and 0xff) shl 8) or
            ((b[off + 2].toLong() and 0xff) shl 16) or
            ((b[off + 3].toLong() and 0xff) shl 24)) and 0xffffffffL
    }

    private fun leU64(b: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 7 downTo 0) {
            v = (v shl 8) or (b[off + i].toLong() and 0xffL)
        }
        return v
    }

    private fun putLe32(b: ByteArray, off: Int, value: Long) {
        b[off] = (value and 0xff).toByte()
        b[off + 1] = ((value shr 8) and 0xff).toByte()
        b[off + 2] = ((value shr 16) and 0xff).toByte()
        b[off + 3] = ((value shr 24) and 0xff).toByte()
    }

    private fun putLe64(b: ByteArray, off: Int, value: Long) {
        for (i in 0 until 8) {
            b[off + i] = ((value shr (i * 8)) and 0xff).toByte()
        }
    }
}