package io.github.xis3794.mirrorbox.qcow2.tools

import io.github.xis3794.mirrorbox.qcow2.Qcow2
import io.github.xis3794.mirrorbox.qcow2.Qcow2Image
import java.io.File
import java.security.MessageDigest

/**
 * Tiny CLI over the pure Kotlin engine, used by CI to cross validate against `qemu-img`:
 *
 *  - `info FILE`               prints a structural summary
 *  - `verify FILE`             reads the whole virtual disk and prints a SHA-256
 *  - `create FILE SIZE [BITS]` creates a fresh qcow2 v3 image
 *  - `write FILE OFFSET LEN`   writes a deterministic pattern at OFFSET
 *
 * Any mismatch between this engine and qemu-img shows up as a failed CI job.
 */
object Qcow2Cli {

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.isEmpty()) {
            println("usage: info|verify|create|write|stats <args>")
            return
        }
        when (args[0]) {
            "info" -> info(File(args[1]))
            "verify" -> verify(File(args[1]))
            "create" -> create(File(args[1]), args[2].toLong(), args.getOrNull(3)?.toInt() ?: Qcow2.DEFAULT_CLUSTER_BITS)
            "write" -> write(File(args[1]), args[2].toLong(), args[3].toInt())
            "stats" -> stats(File(args[1]))
            else -> println("unknown command: ${args[0]}")
        }
    }

    private fun info(file: File) {
        Qcow2Image.open(file).use { img ->
            val header = img.header
            println("version=${header.version}")
            println("virtualSize=${header.virtualSize}")
            println("clusterSize=${header.clusterSize}")
            println("l1Size=${header.l1Size}")
            println("refcountTableClusters=${header.refcountTableClusters}")
            println("snapshots=${img.snapshots().size}")
            println("backingFile=${img.backingFileName ?: "none"}")
            println("incompatibleFeatures=${header.incompatibleFeatures}")
        }
    }

    private fun stats(file: File) {
        Qcow2Image.open(file).use { img ->
            val stats = img.stats()
            println("allocatedClusters=${stats.allocatedClusters}")
            println("zeroClusters=${stats.zeroClusters}")
            println("compressedClusters=${stats.compressedClusters}")
            println("totalClusters=${stats.totalClusters}")
            println("usedHostBytes=${stats.usedHostBytes}")
        }
    }

    private fun verify(file: File) {
        Qcow2Image.open(file).use { img ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buf = ByteArray(1 shl 20)
            var offset = 0L
            while (offset < img.virtualSize) {
                val want = minOf(buf.size.toLong(), img.virtualSize - offset).toInt()
                img.read(offset, buf, 0, want)
                digest.update(buf, 0, want)
                offset += want
            }
            println("sha256=" + digest.digest().joinToString("") { "%02x".format(it) })
            println("bytes=$offset")
        }
    }

    private fun create(file: File, size: Long, clusterBits: Int) {
        Qcow2Image.create(file, size, clusterBits).use { }
        println("created=${file.absolutePath}")
        println("size=$size")
    }

    private fun write(file: File, offset: Long, length: Int) {
        val payload = ByteArray(length) { ((it * 7 + 13) % 251).toByte() }
        Qcow2Image.open(file, writable = true).use { img ->
            img.write(offset, payload)
            img.flush()
        }
        println("wrote=$length")
        println("offset=$offset")
    }
}