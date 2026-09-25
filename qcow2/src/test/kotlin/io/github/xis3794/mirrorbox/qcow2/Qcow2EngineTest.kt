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