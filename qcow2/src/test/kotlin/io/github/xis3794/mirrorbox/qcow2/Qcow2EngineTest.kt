package io.github.xis3794.mirrorbox.qcow2

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * JVM tests for the pure Kotlin qcow2 engine.
 *
 * These run in CI (`./gradlew :qcow2:test`) and are also cross checked against `qemu-img` by the
 * workflow: whatever this engine writes must survive `qemu-img check`.
 */
class Qcow2EngineTest {

    private fun tempImage(suffix: String = ".qcow2"): File {
        val f = File.createTempFile("mirrorbox-test", suffix)
        f.delete()
        return f
    }

    private fun pattern(size: Int, seed: Int = 251): ByteArray =
        ByteArray(size) { ((it * 7 + seed) % 251).toByte() }

    @Test
    fun createReadWriteRoundTrip() {
        val f = tempImage()
        try {
            Qcow2Image.create(f, 64L * 1024 * 1024).use { img ->
                assertEquals(64L * 1024 * 1024, img.virtualSize)
                assertEquals(65536, img.clusterSize)
                assertEquals(3, img.version)

                // Fresh image: everything reads as zero and nothing is allocated.
                val zeros = img.readBytes(0, 4096)
                assertTrue(zeros.all { it.toInt() == 0 })
                assertEquals(0L, img.stats().allocatedClusters)

                // Unaligned write spanning two clusters.
                val data = pattern(100_000)
                img.write(1234L, data)
                img.flush()
                assertEquals(data.toList(), img.readBytes(1234L, data.size).toList())

                val stats = img.stats()
                assertTrue(stats.allocatedClusters >= 2, "expected at least two clusters, got ${stats.allocatedClusters}")
                assertEquals(0L, stats.compressedClusters)
            }

            // Reopen and verify persistence.
            Qcow2Image.open(f).use { img ->
                val expected = pattern(100_000)
                assertEquals(expected.toList(), img.readBytes(1234L, expected.size).toList())
            }
        } finally {
            f.delete()
        }
    }

    @Test
    fun zeroWritesStaySparse() {
        val f = tempImage()
        try {
            Qcow2Image.create(f, 8L * 1024 * 1024).use { img ->
                img.write(0L, ByteArray(65536 * 2))
                img.flush()
                assertEquals(0L, img.stats().allocatedClusters, "writing zeros must not allocate")

                img.write(0L, pattern(65536))
                img.flush()
                assertEquals(1L, img.stats().allocatedClusters)

                // Overwriting a whole cluster with zeros releases it again.
                img.write(0L, ByteArray(65536))
                img.flush()
                assertEquals(0L, img.stats().allocatedClusters)
            }
        } finally {
            f.delete()
        }
    }

    @Test
    fun writesAcrossManyClustersAndBounds() {
        val f = tempImage()
        try {
            Qcow2Image.create(f, 4L * 1024 * 1024, clusterBits = 12).use { img ->
                assertEquals(4096, img.clusterSize)
                val total = 300_000
                val data = pattern(total, seed = 13)
                img.write(500L, data)
                img.flush()
                assertEquals(data.toList(), img.readBytes(500L, total).toList())

                // Reads beyond the virtual size are zero padded, never throw.
                val tail = img.readBytes(img.virtualSize - 16L, 64)
                assertTrue(tail.sliceArray(16 until 64).all { it.toInt() == 0 })
            }
        } finally {
            f.delete()
        }
    }

    @Test
    fun clusterStateMapReflectsAllocations() {
        val f = tempImage()
        try {
            Qcow2Image.create(f, 4L * 1024 * 1024).use { img ->
                img.write(0L, pattern(65536))
                img.flush()
                val map = img.clusterStates()
                assertEquals(4 * 1024 * 1024 / 65536, map.cellCount)
                assertEquals(1, map.stateAt(0)) // allocated
                assertEquals(0, map.stateAt(1)) // untouched stays sparse
                assertEquals(0, map.stateAt(2))

                // Zeroing an allocated cluster releases it and switches it to the zero state.
                img.write(0L, ByteArray(65536))
                img.flush()
                val after = img.clusterStates()
                assertEquals(2, after.stateAt(0))
                assertEquals(0L, img.stats().allocatedClusters)
            }
        } finally {
            f.delete()
        }
    }

    @Test
    fun writtenEntriesUseTheOnDiskCopiedFlag() {
        // Verified against QEMU generated images: a standard cluster entry is
        // `copied(bit63) | offset(bits 9..55)` and bit0 is reserved (the zero-cluster marker).
        val f = tempImage()
        try {
            Qcow2Image.create(f, 32L * 1024 * 1024).use { img ->
                img.write(0L, pattern(200_000))
                img.flush()
                val l1 = img.l1Entry(0L)
                assertEquals(1L shl 63, l1 and (1L shl 63), "L1 entry must set the copied flag")
                assertEquals(0L, l1 and 1L, "bit0 must stay clear on L1 entries")

val l2 = img.l2TableEntries(0L)!!
                val entry = l2[0]
                assertEquals(1L shl 63, entry and (1L shl 63), "L2 standard entry must set the copied flag")
                assertEquals(0L, entry and 1L, "bit0 must stay clear on standard L2 entries")
                val offset = entry and 0x00fffffffffffe00L
                assertTrue(offset != 0L, "standard entry must point at an allocated cluster")
                assertEquals(0L, offset % img.clusterSize, "offset must be cluster aligned")
            }
        } finally {
            f.delete()
        }
    }

    @Test
    fun partialWritesExtendTheHostFileToWholeClusters() {
        // Regression: a partial write into a fresh cluster used to leave the host file shorter than
        // the cluster it references (qemu tolerates short files, our reader must too — but we also
        // make sure we never produce one).
        val f = tempImage()
        try {
            Qcow2Image.create(f, 8L * 1024 * 1024).use { img ->
                img.write(12345L, pattern(200_000))
                img.flush()

                val l2 = img.l2TableEntries(0L)!!
                var referenced = 0
                for (entry in l2) {
                    if (entry == 0L) continue
                    val offset = entry and 0x00fffffffffffe00L
                    referenced++
                    assertTrue(
                        offset + img.clusterSize <= f.length(),
                        "cluster at $offset (end ${offset + img.clusterSize}) must exist in the host file (${f.length()})",
                    )
                }
                assertTrue(referenced >= 2, "expected several referenced clusters, got $referenced")

                // Reading the whole virtual disk must never throw.
                val readBack = img.readBytes(12_000L, 300_000)
                assertEquals(pattern(200_000)[8_000].toInt(), readBack[8_000 - (12_000 - 12_345)].toInt())
            }
        } finally {
            f.delete()
        }
    }

    @Test
    fun readsTolerateTruncatedHostFiles() {
        val f = tempImage()
        try {
            Qcow2Image.create(f, 8L * 1024 * 1024).use { img ->
                img.write(0L, pattern(65536))
                img.flush()
            }
            // Simulate an image whose data region is physically shorter than the cluster it maps
            // (qemu-img reads those bytes as zeros; so must we).
            java.io.RandomAccessFile(f, "rw").use { raf -> raf.setLength(70_000L) }
            Qcow2Image.open(f).use { img ->
                // Metadata itself is gone beyond the physical end, so the whole disk reads as zeros —
                // the important part is that this never throws.
                val data = img.readBytes(0L, 65536)
                assertTrue(data.all { it.toInt() == 0 }, "bytes beyond the physical end must read as zero")
                val tail = img.readBytes(img.virtualSize - 1024L, 1024)
                assertTrue(tail.all { it.toInt() == 0 })
            }
        } finally {
            f.delete()
        }
    }

    @Test
    fun rejectsInvalidImages() {
        val f = tempImage()
        try {
            f.writeBytes(ByteArray(4096))
            val failed = try {
                Qcow2Image.open(f)
                false
            } catch (e: Qcow2Exception) {
                true
            }
            assertTrue(failed, "opening a non qcow2 file must fail")
        } finally {
            f.delete()
        }
    }
}