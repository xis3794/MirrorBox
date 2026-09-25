package io.github.xis3794.mirrorbox.qcow2

import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/**
 * Pure Kotlin qcow2 engine.
 *
 * Capabilities:
 *  - metadata parsing (v2/v3 headers, L1/L2 tables, refcount table/blocks, internal snapshots)
 *  - random access reads (uncompressed, zero and zlib compressed clusters)
 *  - copy-on-write safe random access writes (v3): cluster allocation, refcount maintenance,
 *    L2 table copy-on-write when shared with snapshots
 *  - structure inspection for the in-app preview UI (cluster heat map, L1/L2 browser, stats)
 *
 * Design notes:
 *  - All metadata is big endian, refcount entries included.
 *  - Cluster sharing is detected through the refcount table: a cluster with refcount > 1 is shared
 *    (e.g. referenced by an internal snapshot) and is therefore copied before modification. This is
 *    the same invariant QEMU relies on, and it makes writing snapshot-safe without extra bookkeeping.
 *  - Every write path keeps the refcount table consistent; `qemu-img check` is still run by the app
 *    after operations as an independent verifier.
 */
class Qcow2Image private constructor(
    private val file: RandomAccessFile,
    val path: String,
    val writable: Boolean,
    headerInfo: Qcow2HeaderInfo,
) : Closeable {

    var header: Qcow2HeaderInfo = headerInfo
        private set

    var backingFileName: String? = null
        private set

    /** Cluster size in bytes. */
    val clusterSize: Int get() = header.clusterSize
    val clusterBits: Int get() = header.clusterBits
    val virtualSize: Long get() = header.virtualSize
    val version: Int get() = header.version
    val clusterCount: Long get() = header.totalClusters
    val l2EntriesPerTable: Long get() = l2Entries

    private val l2Entries: Long = (clusterSize / 8).toLong()
    private val refCountBits: Int get() = 1 shl header.refcountOrder
    private val refCountBytes: Int get() = 1 shl (header.refcountOrder - 3)
    private val refBlockEntries: Long get() = (clusterSize.toLong() * 8L) / refCountBits

    private var l1 = LongArray(0)
    private var refTable = LongArray(0)

    private val l2Cache = LinkedHashMap<Long, ByteArray>(8, 0.75f, true)
    private val l2Dirty = HashSet<Long>()
    private val refBlockCache = LinkedHashMap<Long, ByteArray>(8, 0.75f, true)
    private val refBlockDirty = HashSet<Long>()

    private var l1Dirty = false
    private var refTableDirty = false
    private var headerDirty = false

    private var allocCursor = 4L
    private var closed = false

    private var decompressedIndex = -1L
    private var decompressedData: ByteArray? = null

    // ------------------------------------------------------------------ static API

    companion object {

        /** Opens an existing qcow2 image. */
        fun open(path: File, writable: Boolean = false): Qcow2Image {
            if (!path.isFile) throw Qcow2Exception("镜像文件不存在：${path.absolutePath}")
            val raf = RandomAccessFile(path, if (writable) "rw" else "r")
            try {
                val (info, backing) = readHeader(raf)
                if (writable) {
                    if (info.hasExternalDataFile) {
                        throw Qcow2Exception("该镜像使用外部数据文件（external data file），暂不支持写入")
                    }
                    if (info.hasExtendedL2) {
                        throw Qcow2Exception("该镜像使用 extended L2（子簇分配），暂不支持就地写入；请先用 qemu-img convert 转换")
                    }
                }
                info.backingFileName = backing
                val image = Qcow2Image(raf, path.absolutePath, writable, info)
                image.backingFileName = backing
                image.loadMetadata()
                return image
            } catch (t: Throwable) {
                runCatching { raf.close() }
                throw if (t is Qcow2Exception) t else Qcow2Exception("打开镜像失败：${t.message}", t)
            }
        }

        /**
         * Creates a new empty qcow2 v3 image (no data allocated, sparse).
         * The refcount table is sized up front so that the whole virtual disk can be written.
         */
        fun create(
            path: File,
            virtualSize: Long,
            clusterBits: Int = Qcow2.DEFAULT_CLUSTER_BITS,
        ): Qcow2Image {
            if (clusterBits < Qcow2.MIN_CLUSTER_BITS || clusterBits > Qcow2.MAX_CLUSTER_BITS) {
                throw Qcow2Exception("非法的 cluster_bits：$clusterBits")
            }
            if (virtualSize <= 0L) throw Qcow2Exception("虚拟磁盘大小必须大于 0")
            if (virtualSize % 512L != 0L) throw Qcow2Exception("虚拟磁盘大小必须是 512 字节的倍数")

            val clusterSize = 1 shl clusterBits
            val totalVirtualClusters = Qcow2.ceilDiv(virtualSize, clusterSize.toLong())
            val l2Entries = (clusterSize / 8).toLong()
            val l1Size = maxOf(1L, Qcow2.ceilDiv(totalVirtualClusters, l2Entries))
            val l1Clusters = maxOf(1L, Qcow2.ceilDiv(l1Size * 8L, clusterSize.toLong()))

            val refCountBits = 1 shl Qcow2.REFCOUNT_ORDER_DEFAULT
            val refCountBytes = refCountBits / 8
            val refBlockEntries = (clusterSize.toLong() * 8L) / refCountBits
            val refBlocksNeeded = maxOf(1L, Qcow2.ceilDiv(totalVirtualClusters, refBlockEntries))
            val refTableClusters = maxOf(1L, Qcow2.ceilDiv(refBlocksNeeded * 8L, clusterSize.toLong()))
            if (refTableClusters > 128L) {
                throw Qcow2Exception("簇大小过小/镜像过大：refcount 表需要 $refTableClusters 个簇，请改用更大的簇（推荐 64K）")
            }

            val headerClusters = 1L
            val refBlockClusters = 1L
            val refTableOffset = headerClusters * clusterSize
            val refBlockOffset = refTableOffset + refTableClusters * clusterSize
            val l1Offset = refBlockOffset + refBlockClusters * clusterSize
            val bootstrapClusters = headerClusters + refTableClusters + refBlockClusters + l1Clusters
            if (bootstrapClusters > refBlockEntries) {
                throw Qcow2Exception("簇大小过小，无法容纳元数据（需要 $bootstrapClusters 个簇，单个 refcount 块仅覆盖 $refBlockEntries 个簇）")
            }

            val raf = RandomAccessFile(path, "rw")
            try {
                raf.setLength(0L)

                val headerBuf = ByteArray(Qcow2.HEADER_LENGTH_V3)
                writeHeaderFields(
                    headerBuf, Qcow2.VERSION_3, clusterBits, virtualSize, l1Size, l1Offset,
                    refTableOffset, refTableClusters.toInt(), 0, 0L,
                )
                raf.seek(0L)
                raf.write(headerBuf)

                val tableBytes = ByteArray((refTableClusters * clusterSize).toInt())
                BE.putU64(tableBytes, 0, refBlockOffset)
                raf.seek(refTableOffset)
                raf.write(tableBytes)

                val block = ByteArray(clusterSize)
                for (c in 0L until bootstrapClusters) {
                    putRefEntryRaw(block, (c * refCountBytes).toInt(), refCountBytes, 1L)
                }
                raf.seek(refBlockOffset)
                raf.write(block)

                val l1Bytes = ByteArray((l1Clusters * clusterSize).toInt())
                raf.seek(l1Offset)
                raf.write(l1Bytes)
            } catch (t: Throwable) {
                runCatching { raf.close() }
                throw Qcow2Exception("创建镜像失败：${t.message}", t)
            }
            raf.close()
            return open(path, writable = true)
        }

        private fun readHeader(raf: RandomAccessFile): Pair<Qcow2HeaderInfo, String?> {
            if (raf.length() < Qcow2.HEADER_LENGTH_V2.toLong()) {
                throw Qcow2Exception("文件太小，不是有效的 qcow2 镜像")
            }
            val buf = ByteArray(Qcow2.HEADER_LENGTH_V3)
            raf.seek(0L)
            if (raf.length() >= Qcow2.HEADER_LENGTH_V3.toLong()) {
                raf.readFully(buf)
            } else {
                raf.readFully(buf, 0, Qcow2.HEADER_LENGTH_V2)
            }

            val magic = BE.u32(buf, 0)
            if (magic != Qcow2.MAGIC) {
                throw Qcow2Exception("不是有效的 qcow2 镜像（magic=0x${java.lang.Long.toHexString(magic)}）")
            }
            val version = BE.u32(buf, 4).toInt()
            if (version != Qcow2.VERSION_2 && version != Qcow2.VERSION_3) {
                throw Qcow2Exception("不支持的 qcow2 版本：$version")
            }
            val backingOffset = BE.u64(buf, 8)
            val backingSize = BE.u32(buf, 16).toInt()
            val clusterBits = BE.u32(buf, 20).toInt()
            if (clusterBits < Qcow2.MIN_CLUSTER_BITS || clusterBits > Qcow2.MAX_CLUSTER_BITS) {
                throw Qcow2Exception("非法的 cluster_bits：$clusterBits")
            }
            val virtualSize = BE.u64(buf, 24)
            val cryptMethod = BE.u32(buf, 32)
            if (cryptMethod != 0L) {
                throw Qcow2Exception("加密的 qcow2 镜像暂不支持（crypt_method=$cryptMethod）")
            }
            val l1Size = BE.u32(buf, 36)
            val l1Offset = BE.u64(buf, 40)
            val refTableOffset = BE.u64(buf, 48)
            val refTableClusters = BE.u32(buf, 56).toInt()
            val nbSnapshots = BE.u32(buf, 60).toInt()
            val snapshotsOffset = BE.u64(buf, 64)

            var incompat = 0L
            var compat = 0L
            var autoclear = 0L
            var refOrder = Qcow2.REFCOUNT_ORDER_DEFAULT
            var headerLength = Qcow2.HEADER_LENGTH_V2
            if (version >= Qcow2.VERSION_3) {
                incompat = BE.u64(buf, 72)
                compat = BE.u64(buf, 80)
                autoclear = BE.u64(buf, 88)
                refOrder = BE.u32(buf, 96).toInt()
                headerLength = BE.u32(buf, 100).toInt()
                if (refOrder < 0 || refOrder > 6) throw Qcow2Exception("非法的 refcount_order=$refOrder")
            }
            if (incompat and Qcow2.INCOMPAT_KNOWN_MASK.inv() != 0L) {
                throw Qcow2Exception(
                    "镜像包含未知特性位（incompatible=0x${java.lang.Long.toHexString(incompat)}），请使用更新的 qemu-img 处理",
                )
            }
            if (virtualSize <= 0L) throw Qcow2Exception("镜像虚拟大小为 0，文件可能已损坏")
            if (l1Size <= 0L || l1Offset <= 0L) throw Qcow2Exception("镜像 L1 表缺失，文件可能已损坏")

            val clusterSize = 1 shl clusterBits
            val l2Count = (clusterSize / 8).toLong()
            val expectedL1 = Qcow2.ceilDiv(Qcow2.ceilDiv(virtualSize, clusterSize.toLong()), l2Count)
            if (l1Size < expectedL1) {
                throw Qcow2Exception("镜像 L1 表过小（l1_size=$l1Size，期望至少 $expectedL1）")
            }

            var backingName: String? = null
            if (backingOffset > 0L && backingSize > 0) {
                val bb = ByteArray(backingSize)
                raf.seek(backingOffset)
                raf.readFully(bb)
                backingName = String(bb, Charsets.UTF_8)
            }

            val info = Qcow2HeaderInfo(
                version = version,
                clusterBits = clusterBits,
                clusterSize = clusterSize,
                virtualSize = virtualSize,
                cryptMethod = cryptMethod,
                l1Size = l1Size,
                l1TableOffset = l1Offset,
                refcountTableOffset = refTableOffset,
                refcountTableClusters = refTableClusters,
                nbSnapshots = nbSnapshots,
                snapshotsOffset = snapshotsOffset,
                incompatibleFeatures = incompat,
                compatibleFeatures = compat,
                autoclearFeatures = autoclear,
                refcountOrder = refOrder,
                headerLength = headerLength,
            )
            return info to backingName
        }

        private fun writeHeaderFields(
            buf: ByteArray,
            version: Int,
            clusterBits: Int,
            virtualSize: Long,
            l1Size: Long,
            l1Offset: Long,
            refTableOffset: Long,
            refTableClusters: Int,
            nbSnapshots: Int,
            snapshotsOffset: Long,
        ) {
            BE.putU32(buf, 0, Qcow2.MAGIC)
            BE.putU32(buf, 4, version.toLong())
            BE.putU64(buf, 8, 0L)
            BE.putU32(buf, 16, 0L)
            BE.putU32(buf, 20, clusterBits.toLong())
            BE.putU64(buf, 24, virtualSize)
            BE.putU32(buf, 32, 0L)
            BE.putU32(buf, 36, l1Size)
            BE.putU64(buf, 40, l1Offset)
            BE.putU64(buf, 48, refTableOffset)
            BE.putU32(buf, 56, refTableClusters.toLong())
            BE.putU32(buf, 60, nbSnapshots.toLong())
            BE.putU64(buf, 64, snapshotsOffset)
            if (version >= Qcow2.VERSION_3) {
                BE.putU64(buf, 72, 0L)
                BE.putU64(buf, 80, 0L)
                BE.putU64(buf, 88, 0L)
                BE.putU32(buf, 96, Qcow2.REFCOUNT_ORDER_DEFAULT.toLong())
                BE.putU32(buf, 100, Qcow2.HEADER_LENGTH_V3.toLong())
            }
        }

        private fun putRefEntryRaw(buf: ByteArray, off: Int, bytes: Int, value: Long) {
            when (bytes) {
                1 -> buf[off] = (value and 0xff).toByte()
                2 -> BE.putU16(buf, off, value.toInt())
                4 -> BE.putU32(buf, off, value)
                else -> throw Qcow2Exception("不支持的 refcount 宽度：$bytes")
            }
        }

        private fun getRefEntryRaw(buf: ByteArray, off: Int, bytes: Int): Long = when (bytes) {
            1 -> buf[off].toLong() and 0xffL
            2 -> BE.u16(buf, off).toLong()
            4 -> BE.u32(buf, off)
            else -> throw Qcow2Exception("不支持的 refcount 宽度：$bytes")
        }
    }

    // ------------------------------------------------------------------ lifecycle

    private fun ensureOpen() {
        if (closed) throw Qcow2Exception("镜像已关闭")
    }

    private fun requireWritable() {
        if (!writable) throw Qcow2Exception("镜像以只读方式打开，无法写入")
    }

    private fun readAt(pos: Long, buf: ByteArray, off: Int, len: Int) {
        file.seek(pos)
        file.readFully(buf, off, len)
    }

    private fun writeAt(pos: Long, buf: ByteArray, off: Int, len: Int) {
        file.seek(pos)
        file.write(buf, off, len)
    }

    private fun loadMetadata() {
        val l1Bytes = header.l1Size * 8L
        if (l1Bytes > 384L * 1024L * 1024L) throw Qcow2Exception("L1 表过大（$l1Bytes 字节）")
        if (l1Bytes > 0L) {
            val buf = ByteArray(l1Bytes.toInt())
            readAt(header.l1TableOffset, buf, 0, buf.size)
            l1 = LongArray(header.l1Size.toInt()) { BE.u64(buf, it * 8) }
        } else {
            l1 = LongArray(0)
        }

        val tableBytes = header.refcountTableClusters.toLong() * clusterSize
        if (tableBytes > 384L * 1024L * 1024L) throw Qcow2Exception("refcount 表过大（$tableBytes 字节）")
        if (tableBytes <= 0L) {
            refTable = LongArray(0)
        } else {
            val buf = ByteArray(tableBytes.toInt())
            readAt(header.refcountTableOffset, buf, 0, buf.size)
            refTable = LongArray(buf.size / 8) { BE.u64(buf, it * 8) }
        }
        allocCursor = findFirstFreeCluster(4L)
    }

    /** Persists all cached metadata. */
    @Synchronized
    fun flush() {
        if (closed) return
        if (!writable) return

        for (c in l2Dirty.toList()) {
            val buf = l2Cache[c] ?: continue
            writeAt(c * clusterSize, buf, 0, clusterSize)
            l2Dirty.remove(c)
        }
        for (c in refBlockDirty.toList()) {
            val buf = refBlockCache[c] ?: continue
            writeAt(c * clusterSize, buf, 0, clusterSize)
            refBlockDirty.remove(c)
        }
        if (refTableDirty) {
            val buf = ByteArray(refTable.size * 8)
            for (i in refTable.indices) BE.putU64(buf, i * 8, refTable[i])
            writeAt(header.refcountTableOffset, buf, 0, buf.size)
            refTableDirty = false
        }
        if (l1Dirty) {
            val buf = ByteArray(header.l1Size.toInt() * 8)
            for (i in l1.indices) BE.putU64(buf, i * 8, l1[i])
            writeAt(header.l1TableOffset, buf, 0, buf.size)
            l1Dirty = false
        }
        if (headerDirty) {
            flushHeader()
            headerDirty = false
        }
    }

    private fun flushHeader() {
        val len = if (header.isV3) Qcow2.HEADER_LENGTH_V3 else Qcow2.HEADER_LENGTH_V2
        val buf = ByteArray(len)
        readAt(0L, buf, 0, len)
        BE.putU32(buf, 56, header.refcountTableClusters.toLong())
        BE.putU32(buf, 60, header.nbSnapshots.toLong())
        BE.putU64(buf, 64, header.snapshotsOffset)
        writeAt(0L, buf, 0, len)
    }

    override fun close() {
        if (closed) return
        try {
            flush()
        } finally {
            closed = true
            runCatching { file.close() }
        }
    }

    private fun flushL2(cluster: Long) {
        if (!l2Dirty.contains(cluster)) return
        val buf = l2Cache[cluster]
        if (buf != null) writeAt(cluster * clusterSize, buf, 0, clusterSize)
        l2Dirty.remove(cluster)
    }

    private fun flushRefBlock(cluster: Long) {
        if (!refBlockDirty.contains(cluster)) return
        val buf = refBlockCache[cluster]
        if (buf != null) writeAt(cluster * clusterSize, buf, 0, clusterSize)
        refBlockDirty.remove(cluster)
    }

    private fun evictCaches() {
        while (l2Cache.size > 24) {
            val key = l2Cache.keys.first()
            flushL2(key)
            l2Cache.remove(key)
        }
        while (refBlockCache.size > 8) {
            val key = refBlockCache.keys.first()
            flushRefBlock(key)
            refBlockCache.remove(key)
        }
    }

    // ------------------------------------------------------------------ reads

    /** Fills [dest] with image content at [offset]; regions beyond the virtual size are zeroed. */
    @Synchronized
    fun read(offset: Long, dest: ByteArray, destOffset: Int = 0, length: Int = dest.size - destOffset) {
        ensureOpen()
        if (length <= 0) return
        java.util.Arrays.fill(dest, destOffset, destOffset + length, 0)
        if (offset < 0L || offset >= virtualSize) return

        val end = minOf(offset + length.toLong(), virtualSize)
        val mask = (clusterSize - 1).toLong()
        var pos = offset
        var dOff = destOffset
        while (pos < end) {
            val clusterIdx = pos ushr clusterBits
            val inCluster = (pos and mask).toInt()
            val chunk = minOf((clusterSize - inCluster).toLong(), end - pos).toInt()
            readClusterInto(clusterIdx, inCluster, dest, dOff, chunk)
            pos += chunk
            dOff += chunk
        }
    }

    fun readBytes(offset: Long, length: Int): ByteArray {
        val out = ByteArray(length)
        read(offset, out, 0, length)
        return out
    }

    private fun readClusterInto(clusterIdx: Long, inCluster: Int, dest: ByteArray, destOff: Int, len: Int) {
        val entry = l2EntryFor(clusterIdx)
        when (classifyEntry(entry)) {
            ClusterType.UNALLOCATED, ClusterType.ZERO -> Unit // buffer already zeroed
            ClusterType.ALLOCATED -> readAt((entry and Qcow2.ENTRY_OFFSET_MASK) + inCluster, dest, destOff, len)
            ClusterType.COMPRESSED -> {
                var data = decompressedData
                if (data == null || decompressedIndex != clusterIdx) {
                    val buf = ByteArray(clusterSize)
                    decompressInto(entry, buf)
                    decompressedData = buf
                    decompressedIndex = clusterIdx
                    data = buf
                }
                System.arraycopy(data, inCluster, dest, destOff, len)
            }
        }
    }

    private fun decompressInto(entry: Long, dest: ByteArray) {
        val hostOffset = entry and Qcow2.ENTRY_COMPRESSED_OFFSET_MASK
        val sectors = (entry and Qcow2.ENTRY_COMPRESSED_SECTORS_MASK).toInt()
        val compressedSize = sectors * 512
        if (hostOffset <= 0L || compressedSize <= 0) {
            throw Qcow2Exception("压缩簇描述符非法（offset=$hostOffset sectors=$sectors）")
        }
        val raw = ByteArray(compressedSize)
        readAt(hostOffset, raw, 0, compressedSize)

        val inflater = Inflater()
        try {
            inflater.setInput(raw)
            var total = 0
            while (total < clusterSize && !inflater.finished()) {
                val n = try {
                    inflater.inflate(dest, total, clusterSize - total)
                } catch (e: DataFormatException) {
                    throw Qcow2Exception("解压失败：该簇可能使用 zstd 压缩（v0.1 仅支持 zlib）", e)
                }
                if (n == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) break
                }
                total += n
            }
            if (total != clusterSize) {
                throw Qcow2Exception("解压结果长度异常（$total 字节，期望 $clusterSize）")
            }
        } finally {
            inflater.end()
        }
    }

    private fun l2EntryFor(clusterIdx: Long): Long {
        if (clusterIdx < 0L) return 0L
        val l1Idx = clusterIdx / l2Entries
        if (l1Idx >= l1.size.toLong()) return 0L
        val l2Offset = l1[l1Idx.toInt()] and Qcow2.ENTRY_OFFSET_MASK
        if (l2Offset == 0L) return 0L
        val table = l2Table(l2Offset ushr clusterBits)
        return BE.u64(table, ((clusterIdx % l2Entries).toInt()) * 8)
    }

    private fun l2Table(l2Cluster: Long): ByteArray {
        l2Cache[l2Cluster]?.let { return it }
        val buf = ByteArray(clusterSize)
        readAt(l2Cluster * clusterSize, buf, 0, clusterSize)
        l2Cache[l2Cluster] = buf
        return buf
    }

    private fun classifyEntry(entry: Long): ClusterType {
        if (entry == 0L) return ClusterType.UNALLOCATED
        if (header.version == Qcow2.VERSION_2) {
            if (entry and Qcow2.ENTRY_COMPRESSED_V2 != 0L) return ClusterType.COMPRESSED
            if (entry and Qcow2.ENTRY_OFFSET_MASK == 0L) return ClusterType.UNALLOCATED
            return ClusterType.ALLOCATED
        }
        if ((entry and Qcow2.ENTRY_COMPRESSED_MASK) == Qcow2.ENTRY_COMPRESSED_V3) return ClusterType.COMPRESSED
        if (entry == Qcow2.ENTRY_ALLOCATED_FLAG) return ClusterType.ZERO
        if ((entry and Qcow2.ENTRY_ALLOCATED_FLAG) == 0L) return ClusterType.UNALLOCATED
        return ClusterType.ALLOCATED
    }

    // ------------------------------------------------------------------ writes

    @Synchronized
    fun write(offset: Long, src: ByteArray, srcOffset: Int = 0, length: Int = src.size - srcOffset) {
        ensureOpen()
        requireWritable()
        if (length <= 0) return
        if (offset < 0L || offset + length.toLong() > virtualSize) {
            throw Qcow2Exception("写入超出虚拟磁盘范围（offset=$offset length=$length size=$virtualSize）")
        }
        val end = offset + length.toLong()
        val mask = (clusterSize - 1).toLong()
        var pos = offset
        var sOff = srcOffset
        while (pos < end) {
            val clusterIdx = pos ushr clusterBits
            val inCluster = (pos and mask).toInt()
            val chunk = minOf((clusterSize - inCluster).toLong(), end - pos).toInt()
            writeClusterPart(clusterIdx, inCluster, src, sOff, chunk)
            pos += chunk
            sOff += chunk
        }
        evictCaches()
    }

    private fun writeClusterPart(clusterIdx: Long, inCluster: Int, src: ByteArray, srcOff: Int, len: Int) {
        val l1Idx = clusterIdx / l2Entries
        if (l1Idx >= l1.size.toLong()) throw Qcow2Exception("写入超出 L1 表范围（cluster=$clusterIdx）")
        val l2Idx = (clusterIdx % l2Entries).toInt()
        val wholeCluster = inCluster == 0 && len == clusterSize
        val zeros = isZero(src, srcOff, len)

        var l2Cluster = (l1[l1Idx.toInt()] and Qcow2.ENTRY_OFFSET_MASK) ushr clusterBits
        if (l2Cluster == 0L) {
            if (zeros && wholeCluster) return // stays sparse
            val newTableCluster = allocateCluster()
            val fresh = ByteArray(clusterSize)
            l2Cache[newTableCluster] = fresh
            l2Dirty.add(newTableCluster)
            // L1 entries hold byte offsets, exactly like the on-disk format.
            l1[l1Idx.toInt()] = (newTableCluster * clusterSize) or Qcow2.ENTRY_ALLOCATED_FLAG
            l1Dirty = true
            l2Cluster = newTableCluster
        }

        // Copy-on-write the L2 table when it is shared (e.g. referenced by a snapshot).
        if (refcountOf(l2Cluster) > 1L) {
            l2Cluster = cowL2Table(l1Idx, l2Cluster)
        }

        val table = l2Table(l2Cluster)
        val entry = BE.u64(table, l2Idx * 8)
        val zeroEntry = if (header.isV3) Qcow2.ENTRY_ALLOCATED_FLAG else 0L

        when (classifyEntry(entry)) {
            ClusterType.UNALLOCATED, ClusterType.ZERO -> {
                if (zeros && wholeCluster) return // nothing to store
                val newCluster = allocateCluster()
                writeAt(newCluster * clusterSize + inCluster, src, srcOff, len)
                updateL2Entry(l2Cluster, table, l2Idx, (newCluster * clusterSize) or Qcow2.ENTRY_ALLOCATED_FLAG)
            }

            ClusterType.ALLOCATED -> {
                val hostOffset = entry and Qcow2.ENTRY_OFFSET_MASK
                val oldCluster = hostOffset ushr clusterBits
                if (zeros && wholeCluster) {
                    // Drop the data cluster; snapshots keep their own reference through the refcount.
                    freeCluster(oldCluster)
                    updateL2Entry(l2Cluster, table, l2Idx, zeroEntry)
                    return
                }
                val rc = refcountOf(oldCluster)
                if (rc <= 1L) {
                    writeAt(hostOffset + inCluster, src, srcOff, len)
                } else {
                    val newCluster = allocateCluster()
                    copyCluster(oldCluster, newCluster)
                    writeAt(newCluster * clusterSize + inCluster, src, srcOff, len)
                    updateL2Entry(l2Cluster, table, l2Idx, (newCluster * clusterSize) or Qcow2.ENTRY_ALLOCATED_FLAG)
                    freeCluster(oldCluster)
                }
            }

            ClusterType.COMPRESSED -> {
                val buf = ByteArray(clusterSize)
                decompressInto(entry, buf)
                System.arraycopy(src, srcOff, buf, inCluster, len)
                val newCluster = allocateCluster()
                writeAt(newCluster * clusterSize, buf, 0, clusterSize)
                updateL2Entry(l2Cluster, table, l2Idx, (newCluster * clusterSize) or Qcow2.ENTRY_ALLOCATED_FLAG)
                freeCompressedCluster(entry)
            }
        }
    }

    private fun cowL2Table(l1Idx: Long, oldCluster: Long): Long {
        flushL2(oldCluster)
        val newCluster = allocateCluster()
        copyCluster(oldCluster, newCluster)
        val buf = ByteArray(clusterSize)
        readAt(newCluster * clusterSize, buf, 0, clusterSize)
        l2Cache[newCluster] = buf
        l2Dirty.add(newCluster)
        // Byte offset, matching the on-disk L1 format.
        l1[l1Idx.toInt()] = (newCluster * clusterSize) or Qcow2.ENTRY_ALLOCATED_FLAG
        l1Dirty = true
        l2Cache.remove(oldCluster)
        l2Dirty.remove(oldCluster)
        freeCluster(oldCluster)
        return newCluster
    }

    private fun updateL2Entry(l2Cluster: Long, table: ByteArray, index: Int, value: Long) {
        BE.putU64(table, index * 8, value)
        l2Cache[l2Cluster] = table
        l2Dirty.add(l2Cluster)
    }

    private fun isZero(buf: ByteArray, off: Int, len: Int): Boolean {
        for (i in off until off + len) {
            if (buf[i].toInt() != 0) return false
        }
        return true
    }

    private fun copyCluster(srcCluster: Long, dstCluster: Long) {
        val buf = ByteArray(clusterSize)
        readAt(srcCluster * clusterSize, buf, 0, clusterSize)
        writeAt(dstCluster * clusterSize, buf, 0, clusterSize)
    }

    // ------------------------------------------------------------------ refcounts

    private fun refBlock(blockCluster: Long): ByteArray {
        refBlockCache[blockCluster]?.let { return it }
        val buf = ByteArray(clusterSize)
        readAt(blockCluster * clusterSize, buf, 0, clusterSize)
        refBlockCache[blockCluster] = buf
        return buf
    }

    private fun refcountOf(clusterIdx: Long): Long {
        if (clusterIdx < 0L) return 0L
        val blockIdx = clusterIdx / refBlockEntries
        if (blockIdx >= refTable.size.toLong()) return 0L
        // Refcount table entries are byte offsets to refcount blocks (like L1 entries).
        val blockOffset = refTable[blockIdx.toInt()]
        if (blockOffset == 0L) return 0L
        val block = refBlock(blockOffset ushr clusterBits)
        val off = ((clusterIdx % refBlockEntries) * refCountBytes).toInt()
        return getRefEntryRaw(block, off, refCountBytes)
    }

    private fun setRefcount(clusterIdx: Long, value: Long) {
        val blockIdx = clusterIdx / refBlockEntries
        val blockCluster = ensureRefcountBlock(blockIdx)
        val block = refBlock(blockCluster)
        val off = ((clusterIdx % refBlockEntries) * refCountBytes).toInt()
        if (getRefEntryRaw(block, off, refCountBytes) == value) return
        putRefEntryRaw(block, off, refCountBytes, value)
        refBlockDirty.add(blockCluster)
    }

    private fun ensureRefcountBlock(blockIdx: Long): Long {
        if (blockIdx < 0L) throw Qcow2Exception("非法 refcount 块索引：$blockIdx")
        if (blockIdx >= refTable.size.toLong()) {
            throw Qcow2Exception("refcount 表容量不足（块索引 $blockIdx），请用 qemu-img 重建 / convert 镜像")
        }
        val existingOffset = refTable[blockIdx.toInt()]
        if (existingOffset != 0L) return existingOffset ushr clusterBits

        val base = blockIdx * refBlockEntries
        var chosen = -1L
        var c = maxOf(base, 4L)
        while (c < base + refBlockEntries) {
            if (refcountOf(c) == 0L) {
                chosen = c
                break
            }
            c++
        }
        if (chosen < 0L) throw Qcow2Exception("无法为新 refcount 块分配簇")

        val buf = ByteArray(clusterSize)
        writeAt(chosen * clusterSize, buf, 0, clusterSize)
        refBlockCache[chosen] = buf
        refBlockDirty.add(chosen)
        // Byte offset, matching the on-disk refcount table format.
        refTable[blockIdx.toInt()] = chosen * clusterSize
        refTableDirty = true
        putRefEntryRaw(buf, ((chosen % refBlockEntries) * refCountBytes).toInt(), refCountBytes, 1L)
        return chosen
    }

    private fun findFirstFreeCluster(from: Long): Long {
        var idx = maxOf(from, 4L)
        val limit = maxAllocatableCluster()
        while (idx < limit) {
            if (refcountOf(idx) == 0L) return idx
            idx++
        }
        return limit
    }

    private fun maxAllocatableCluster(): Long = refTable.size.toLong() * refBlockEntries

    private fun allocateCluster(): Long {
        val limit = maxAllocatableCluster()
        if (limit <= 4L) throw Qcow2Exception("镜像元数据异常：没有可分配的簇空间")
        var idx = maxOf(allocCursor, 4L)
        var scanned = 0L
        while (idx < limit) {
            if (refcountOf(idx) == 0L) {
                setRefcount(idx, 1L)
                allocCursor = idx + 1
                return idx
            }
            idx++
            scanned++
            if (scanned > 8_000_000L) break
        }
        throw Qcow2Exception("没有可用的空闲簇（已扫描 $scanned 个），请检查镜像或改用 qemu-img")
    }

    private fun freeCluster(clusterIdx: Long) {
        val rc = refcountOf(clusterIdx)
        if (rc <= 0L) return
        setRefcount(clusterIdx, rc - 1)
        if (clusterIdx < allocCursor) allocCursor = clusterIdx
    }

    private fun freeCompressedCluster(entry: Long) {
        val hostOffset = entry and Qcow2.ENTRY_COMPRESSED_OFFSET_MASK
        val sectors = (entry and Qcow2.ENTRY_COMPRESSED_SECTORS_MASK).toInt()
        val size = sectors.toLong() * 512L
        if (hostOffset <= 0L || size <= 0L) return
        val firstCluster = hostOffset ushr clusterBits
        val count = Qcow2.ceilDiv(size, clusterSize.toLong())
        for (i in 0 until count) {
            if (firstCluster + i < clusterCount) freeCluster(firstCluster + i)
        }
    }

    // ------------------------------------------------------------------ inspection API (UI)

    fun l1Entry(index: Long): Long =
        if (index in 0 until l1.size.toLong()) l1[index.toInt()] else 0L

    fun l1TableSize(): Long = header.l1Size

    fun l2ClusterForL1(l1Index: Long): Long =
        if (l1Index in 0 until l1.size.toLong()) l1[l1Index.toInt()] and Qcow2.ENTRY_OFFSET_MASK else 0L

    fun l2TableEntries(l1Index: Long): LongArray? {
        ensureOpen()
        if (l1Index < 0L || l1Index >= l1.size.toLong()) return null
        val l2Offset = l1[l1Index.toInt()] and Qcow2.ENTRY_OFFSET_MASK
        if (l2Offset == 0L) return null
        val table = l2Table(l2Offset ushr clusterBits)
        return LongArray(l2Entries.toInt()) { BE.u64(table, it * 8) }
    }

    fun clusterTypeAt(clusterIdx: Long): ClusterType = classifyEntry(l2EntryFor(clusterIdx))

    fun clusterInfoAt(clusterIdx: Long): ClusterInfo {
        val entry = l2EntryFor(clusterIdx)
        return when (classifyEntry(entry)) {
            ClusterType.UNALLOCATED -> ClusterInfo(clusterIdx, ClusterType.UNALLOCATED, 0L, 0)
            ClusterType.ZERO -> ClusterInfo(clusterIdx, ClusterType.ZERO, 0L, 0)
            ClusterType.ALLOCATED -> ClusterInfo(
                clusterIdx, ClusterType.ALLOCATED, entry and Qcow2.ENTRY_OFFSET_MASK, clusterSize,
            )
            ClusterType.COMPRESSED -> ClusterInfo(
                clusterIdx,
                ClusterType.COMPRESSED,
                entry and Qcow2.ENTRY_COMPRESSED_OFFSET_MASK,
                ((entry and Qcow2.ENTRY_COMPRESSED_SECTORS_MASK) * 512L).toInt(),
            )
        }
    }

    fun stats(): ImageStats {
        ensureOpen()
        var allocated = 0L
        var zero = 0L
        var compressed = 0L
        var compressedBytes = 0L
        val entriesPerTable = l2Entries.toInt()
        for (i in l1.indices) {
            val l2Offset = l1[i] and Qcow2.ENTRY_OFFSET_MASK
            if (l2Offset == 0L) continue
            val table = l2Table(l2Offset ushr clusterBits)
            for (j in 0 until entriesPerTable) {
                val entry = BE.u64(table, j * 8)
                when (classifyEntry(entry)) {
                    ClusterType.ALLOCATED -> allocated++
                    ClusterType.ZERO -> zero++
                    ClusterType.COMPRESSED -> {
                        compressed++
                        compressedBytes += (entry and Qcow2.ENTRY_COMPRESSED_SECTORS_MASK) * 512L
                    }
                    ClusterType.UNALLOCATED -> Unit
                }
            }
        }
        return ImageStats(
            virtualSize = virtualSize,
            clusterSize = clusterSize,
            totalClusters = clusterCount,
            allocatedClusters = allocated,
            zeroClusters = zero,
            compressedClusters = compressed,
            compressedHostBytes = compressedBytes,
            allocatedHostBytes = allocated * clusterSize.toLong(),
        )
    }

    /** Aggregated cluster state map for the heat map preview. */
    fun clusterStates(maxEntries: Int = 2_000_000): ClusterStateMap {
        ensureOpen()
        val total = clusterCount
        if (total <= 0L) return ClusterStateMap(virtualSize, clusterSize, 1L, ByteArray(0))
        val step = maxOf(1L, Qcow2.ceilDiv(total, maxEntries.toLong()))
        val cellCount = Qcow2.ceilDiv(total, step).toInt()
        val states = ByteArray(cellCount)

        fun mark(clusterIdx: Long, state: Int) {
            if (clusterIdx < 0L || clusterIdx >= total) return
            val cell = (clusterIdx / step).toInt()
            if (cell in states.indices && states[cell] < state) states[cell] = state.toByte()
        }

        val entriesPerTable = l2Entries.toInt()
        for (i in l1.indices) {
            val l2Offset = l1[i] and Qcow2.ENTRY_OFFSET_MASK
            if (l2Offset == 0L) continue
            val table = l2Table(l2Offset ushr clusterBits)
            val base = i.toLong() * l2Entries
            for (j in 0 until entriesPerTable) {
                val entry = BE.u64(table, j * 8)
                val state = when (classifyEntry(entry)) {
                    ClusterType.ALLOCATED -> 1
                    ClusterType.ZERO -> 2
                    ClusterType.COMPRESSED -> 3
                    ClusterType.UNALLOCATED -> 0
                }
                if (state == 0) continue
                mark(base + j, state)
            }
        }
        return ClusterStateMap(virtualSize, clusterSize, step, states)
    }

    fun snapshots(): List<Qcow2SnapshotInfo> {
        ensureOpen()
        if (header.nbSnapshots <= 0 || header.snapshotsOffset <= 0L) return emptyList()
        val out = ArrayList<Qcow2SnapshotInfo>(header.nbSnapshots)
        var pos = header.snapshotsOffset
        try {
            for (index in 0 until header.nbSnapshots) {
                val headerSize = if (header.isV3) 40 else 36
                val hdr = ByteArray(headerSize)
                readAt(pos, hdr, 0, headerSize)
                val l1Off = BE.u64(hdr, 0)
                val snapL1Size = BE.u32(hdr, 8)
                val idSize = BE.u16(hdr, 12)
                val nameSize = BE.u16(hdr, 14)
                val dateSec = BE.u32(hdr, 16)
                val dateNsec = BE.u32(hdr, 20)
                val vmState = BE.u32(hdr, 32)
                val extraSize = if (header.isV3) BE.u32(hdr, 36) else 0L
                var p = pos + headerSize + extraSize
                val idBytes = ByteArray(idSize)
                if (idSize > 0) readAt(p, idBytes, 0, idSize)
                p += idSize
                val nameBytes = ByteArray(nameSize)
                if (nameSize > 0) readAt(p, nameBytes, 0, nameSize)
                p += nameSize
                out.add(
                    Qcow2SnapshotInfo(
                        id = index,
                        name = String(nameBytes, Charsets.UTF_8),
                        l1TableOffset = l1Off,
                        l1Size = snapL1Size,
                        virtualSize = snapL1Size * l2Entries * clusterSize,
                        vmStateSize = vmState,
                        dateSec = dateSec,
                        dateNsec = dateNsec,
                    ),
                )
                pos = Qcow2.alignUp(p, 8L)
            }
        } catch (t: Throwable) {
            // Snapshots are informational for the preview; never break the UI on partial data.
            return out
        }
        return out
    }

    // ------------------------------------------------------------------ bulk helpers

    /** Streams [length] bytes starting at [offset] into [out]. */
    fun copyTo(
        out: OutputStream,
        offset: Long,
        length: Long,
        chunkSize: Int = 4 shl 20,
        onProgress: ((Long) -> Unit)? = null,
    ): Long {
        ensureOpen()
        var remaining = minOf(length, (virtualSize - offset).coerceAtLeast(0L))
        val buf = ByteArray(chunkSize)
        var pos = offset
        var done = 0L
        while (remaining > 0L) {
            val want = minOf(remaining, buf.size.toLong()).toInt()
            read(pos, buf, 0, want)
            out.write(buf, 0, want)
            pos += want
            remaining -= want
            done += want
            onProgress?.invoke(done)
        }
        out.flush()
        return done
    }

    /** Streams [length] bytes from [input] into the image at [offset]. */
    fun copyFrom(
        input: InputStream,
        offset: Long,
        length: Long,
        chunkSize: Int = 4 shl 20,
        onProgress: ((Long) -> Unit)? = null,
    ): Long {
        ensureOpen()
        requireWritable()
        var remaining = minOf(length, (virtualSize - offset).coerceAtLeast(0L))
        val buf = ByteArray(chunkSize)
        var pos = offset
        var done = 0L
        while (remaining > 0L) {
            val want = minOf(remaining, buf.size.toLong()).toInt()
            val n = input.read(buf, 0, want)
            if (n <= 0) break
            write(pos, buf, 0, n)
            pos += n
            remaining -= n
            done += n
            onProgress?.invoke(done)
        }
        flush()
        return done
    }
}