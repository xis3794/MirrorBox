package io.github.xis3794.mirrorbox.qcow2

import java.io.IOException

/**
 * Thrown for any structural / unsupported qcow2 condition.
 */
class Qcow2Exception(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * qcow2 on-disk constants.
 *
 * References: QEMU `docs/interop/qcow2.txt`.
 */
object Qcow2 {
    const val MAGIC: Long = 0x514649fbL

    const val VERSION_2 = 2
    const val VERSION_3 = 3

    const val DEFAULT_CLUSTER_BITS = 16
    const val MIN_CLUSTER_BITS = 9
    const val MAX_CLUSTER_BITS = 21

    const val HEADER_LENGTH_V2 = 72
    const val HEADER_LENGTH_V3 = 104
    const val REFCOUNT_ORDER_DEFAULT = 4

    // ---- L1 / L2 entry flags ------------------------------------------------
    /** Entry uses bit0 as "allocated / copied" flag. */
    const val ENTRY_ALLOCATED_FLAG = 1L
    const val ENTRY_COMPRESSED_V3 = 1L shl 62
    const val ENTRY_COMPRESSED_V2 = 1L shl 63
    const val ENTRY_COMPRESSED_MASK = 3L shl 62

    /** Clears the low 9 flag/reserved bits, leaving the host cluster offset. */
    const val ENTRY_OFFSET_MASK = 0x00fffffffffffe00L

    /** Compressed entry: bits 0..8 = number of 512 byte sectors, bits 9..61 = offset. */
    const val ENTRY_COMPRESSED_OFFSET_MASK = 0x3ffffffffffffe00L
    const val ENTRY_COMPRESSED_SECTORS_MASK = 0x1ffL

    // ---- incompatible feature bits -----------------------------------------
    const val INCOMPAT_DIRTY = 1L shl 0
    const val INCOMPAT_CORRUPT = 1L shl 1
    const val INCOMPAT_EXTERNAL_DATA_FILE = 1L shl 2
    const val INCOMPAT_COMPRESSION_TYPE = 1L shl 3
    const val INCOMPAT_EXTENDED_L2 = 1L shl 4
    const val INCOMPAT_KNOWN_MASK = 0x1fL

    fun ceilDiv(a: Long, b: Long): Long = if (a <= 0L) 0L else (a + b - 1) / b

    fun alignUp(value: Long, align: Long): Long = ceilDiv(value, align) * align
}

/** Classification of a qcow2 cluster as seen through an L1/L2 entry. */
enum class ClusterType {
    UNALLOCATED,
    ZERO,
    ALLOCATED,
    COMPRESSED,
}

data class ClusterInfo(
    val clusterIndex: Long,
    val type: ClusterType,
    val hostOffset: Long,
    val compressedSize: Int,
)

data class Qcow2HeaderInfo(
    val version: Int,
    val clusterBits: Int,
    val clusterSize: Int,
    val virtualSize: Long,
    val cryptMethod: Long,
    val l1Size: Long,
    val l1TableOffset: Long,
    val refcountTableOffset: Long,
    val refcountTableClusters: Int,
    val nbSnapshots: Int,
    val snapshotsOffset: Long,
    val incompatibleFeatures: Long,
    val compatibleFeatures: Long,
    val autoclearFeatures: Long,
    val refcountOrder: Int,
    val headerLength: Int,
) {
    /** Raw backing file name (null when the image has no backing file). */
    var backingFileName: String? = null

    val hasBackingFile: Boolean get() = !backingFileName.isNullOrEmpty()

    val isV3: Boolean get() = version >= Qcow2.VERSION_3
    val hasExternalDataFile: Boolean
        get() = incompatibleFeatures and Qcow2.INCOMPAT_EXTERNAL_DATA_FILE != 0L
    val hasExtendedL2: Boolean
        get() = incompatibleFeatures and Qcow2.INCOMPAT_EXTENDED_L2 != 0L
    val hasCustomCompression: Boolean
        get() = incompatibleFeatures and Qcow2.INCOMPAT_COMPRESSION_TYPE != 0L
    val isDirty: Boolean get() = incompatibleFeatures and Qcow2.INCOMPAT_DIRTY != 0L

    val totalClusters: Long get() = Qcow2.ceilDiv(virtualSize, clusterSize.toLong())
}

data class Qcow2SnapshotInfo(
    val id: Int,
    val name: String,
    val l1TableOffset: Long,
    val l1Size: Long,
    val virtualSize: Long,
    val vmStateSize: Long,
    val dateSec: Long,
    val dateNsec: Long,
) {
    val dateMillis: Long get() = dateSec * 1000L + dateNsec / 1_000_000L
}

data class ImageStats(
    val virtualSize: Long,
    val clusterSize: Int,
    val totalClusters: Long,
    val allocatedClusters: Long,
    val zeroClusters: Long,
    val compressedClusters: Long,
    val compressedHostBytes: Long,
    val allocatedHostBytes: Long,
) {
    val unallocatedClusters: Long
        get() = (totalClusters - allocatedClusters - zeroClusters - compressedClusters).coerceAtLeast(0L)

    val usedHostBytes: Long get() = allocatedHostBytes + compressedHostBytes
}

/**
 * Aggregated cluster state map used by the "磁盘热图" preview.
 * States: 0 = unallocated, 1 = allocated, 2 = zero, 3 = compressed.
 * When the image has more clusters than [maxEntries], several clusters are folded into one cell
 * (the "highest" state wins: compressed > allocated > zero > unallocated).
 */
class ClusterStateMap(
    val virtualSize: Long,
    val clusterSize: Int,
    val stepClusters: Long,
    val states: ByteArray,
) {
    val cellCount: Int get() = states.size

    fun stateAt(index: Int): Int = states[index].toInt()

    fun clusterIndexOfCell(index: Int): Long = index * stepClusters

    fun cellOfCluster(clusterIndex: Long): Int = (clusterIndex / stepClusters).toInt()
}

/**
 * Big endian helpers (qcow2 stores all structures big endian, refcount entries included).
 */
object BE {
    fun u16(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xff) shl 8) or (b[off + 1].toInt() and 0xff)

    fun u32(b: ByteArray, off: Int): Long =
        ((u16(b, off).toLong() shl 16) or u16(b, off + 2).toLong()) and 0xffffffffL

    fun u64(b: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 0 until 8) {
            v = (v shl 8) or (b[off + i].toLong() and 0xffL)
        }
        return v
    }

    fun putU16(b: ByteArray, off: Int, value: Int) {
        b[off] = ((value ushr 8) and 0xff).toByte()
        b[off + 1] = (value and 0xff).toByte()
    }

    fun putU32(b: ByteArray, off: Int, value: Long) {
        b[off] = ((value ushr 24) and 0xff).toByte()
        b[off + 1] = ((value ushr 16) and 0xff).toByte()
        b[off + 2] = ((value ushr 8) and 0xff).toByte()
        b[off + 3] = (value and 0xff).toByte()
    }

    fun putU64(b: ByteArray, off: Int, value: Long) {
        for (i in 0 until 8) {
            b[off + i] = ((value ushr ((7 - i) * 8)) and 0xff).toByte()
        }
    }
}
