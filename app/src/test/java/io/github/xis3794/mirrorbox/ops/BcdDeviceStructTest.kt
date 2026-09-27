package io.github.xis3794.mirrorbox.ops

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BCD「分区设备」结构（0xC000000E 的根因）扫描与改写的单测。
 *
 * 真实样本（Win7 install.wim 释放出来的 BCD）里有 7 处这种结构，值为
 * `分区偏移 32256（LBA63） + 原机器磁盘签名 0xE488E488`；这里用合成样本覆盖
 * 识别、改写、噪声不误判三件事。
 */
class BcdDeviceStructTest {

    private fun put32(b: ByteArray, at: Int, v: Int) {
        for (i in 0 until 4) b[at + i] = ((v shr (8 * i)) and 0xff).toByte()
    }

    private fun put64(b: ByteArray, at: Int, v: Long) {
        for (i in 0 until 8) b[at + i] = ((v shr (8 * i)) and 0xff).toByte()
    }

    private fun deviceStruct(offset: Long, signature: Long, kind: Int = 6): ByteArray {
        val b = ByteArray(88)
        put32(b, 0x10, kind)
        put32(b, 0x18, 0x48)
        put64(b, 0x20, offset)
        put32(b, 0x34, 1)
        put32(b, 0x38, signature)
        return b
    }

    private fun sampleBcd(): ByteArray {
        val b = ByteArray(0x1000)
        // 两处真实结构
        deviceStruct(32256, 0xE488E488L).copyInto(b, 0x200)
        deviceStruct(32256, 0xA05EA05EL).copyInto(b, 0x400)
        // 噪声：kind 不对、偏移不对齐、签名是 0xFFFFFFFF，都不该被认出来
        deviceStruct(32256, 0xE488E488L, kind = 7).copyInto(b, 0x600)
        deviceStruct(32257, 0xE488E488L).copyInto(b, 0x700)
        deviceStruct(32256, 0xFFFFFFFFL).copyInto(b, 0x800)
        return b
    }

    @Test
    fun `只认出真正的分区设备结构`() {
        val found = BcdFix.findDeviceStructs(sampleBcd())
        assertEquals(2, found.size)
        assertEquals(listOf(0x200, 0x400), found.map { it.fileOffset })
        assertTrue(found.all { it.expectedOffset == 32256L })
        assertEquals(listOf(0xE488E488L, 0xA05EA05EL), found.map { it.expectedSignature })
    }

    @Test
    fun `改写后指向本分区并可复检`() {
        val (patched, structs) = BcdFix.patchDeviceStructs(sampleBcd(), 1048576L, 0xDEADBEEFL)
        assertEquals(2, structs.size)
        val after = BcdFix.findDeviceStructs(patched)
        assertEquals(2, after.size)
        assertTrue(after.all { it.expectedOffset == 1048576L })
        assertTrue(after.all { it.expectedSignature == 0xDEADBEEFL })
        // 噪声区域不应该被改写
        assertEquals(7, (patched[0x600 + 0x10].toInt() and 0xff))
    }

    @Test
    fun `摘要会指出不一致并给出修复建议`() {
        val devices = BcdFix.findDeviceStructs(sampleBcd())
        val text = BcdFix.describeDevices(devices, partitionStart = 1048576L, diskSignature = 0x12345678L)
        assertTrue(text.contains("32256"))
        assertTrue(text.contains("0xE488E488"))
        assertTrue(text.contains("修复 BCD"))
    }
}