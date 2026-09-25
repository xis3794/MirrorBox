package io.github.xis3794.mirrorbox.qcow2

import io.github.xis3794.mirrorbox.qcow2.disk.Guid
import io.github.xis3794.mirrorbox.qcow2.disk.NewPartition
import io.github.xis3794.mirrorbox.qcow2.disk.PartitionTables
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PartitionTableTest {

    private fun reader(disk: ByteArray): (Long, Int) -> ByteArray = { offset, length ->
        val start = offset.toInt()
        val out = ByteArray(length)
        if (start < disk.size) {
            val n = minOf(length, disk.size - start)
            System.arraycopy(disk, start, out, 0, n)
        }
        out
    }

    @Test
    fun guidRoundTrip() {
        val g = Guid.LINUX_FILESYSTEM
        assertEquals(g, Guid.decode(Guid.encode(g), 0))
        val efi = Guid.EFI_SYSTEM
        assertEquals(efi, Guid.decode(Guid.encode(efi), 0))
    }

    @Test
    fun gptRoundTrip() {
        val diskSectors = 2048L // 1 MiB
        val disk = ByteArray((diskSectors * 512L).toInt())
        val partitions = listOf(
            NewPartition(startLba = 34L, sectorCount = 1000L, typeId = 0x83, name = "root", typeGuid = Guid.LINUX_FILESYSTEM),
        )
        val layout = PartitionTables.buildGpt(partitions, diskSectors)
        System.arraycopy(layout.protectiveMbr, 0, disk, 0, layout.protectiveMbr.size)
        System.arraycopy(layout.primaryHeader, 0, disk, 512, layout.primaryHeader.size)
        System.arraycopy(layout.entries, 0, disk, 1024, layout.entries.size)

        val info = PartitionTables.parse(reader(disk), disk.size.toLong())
        assertEquals("GPT", info.scheme)
        assertEquals(1, info.partitions.size)
        val p = info.partitions[0]
        assertEquals(34L, p.startLba)
        assertEquals(1000L, p.sectorCount)
        assertEquals("root", p.name)
        assertEquals(Guid.LINUX_FILESYSTEM, p.typeId)
        assertTrue(info.protectiveMbr)
    }

    @Test
    fun mbrRoundTrip() {
        val disk = ByteArray(1024 * 1024)
        val partitions = listOf(
            NewPartition(startLba = 2048L, sectorCount = 1000L, typeId = 0x83, bootable = true),
        )
        val mbr = PartitionTables.buildMbr(partitions, diskSignature = 0x12345678)
        System.arraycopy(mbr, 0, disk, 0, mbr.size)

        val info = PartitionTables.parse(reader(disk), disk.size.toLong())
        assertEquals("MBR", info.scheme)
        assertEquals(1, info.partitions.size)
        assertEquals(2048L, info.partitions[0].startLba)
        assertEquals(1000L, info.partitions[0].sectorCount)
        assertTrue(info.partitions[0].bootable)
        assertEquals("Linux", info.partitions[0].typeName)
    }

    @Test
    fun emptyDiskHasNoTable() {
        val disk = ByteArray(4096)
        val info = PartitionTables.parse(reader(disk), disk.size.toLong())
        assertEquals("none", info.scheme)
        assertTrue(info.partitions.isEmpty())
    }
}