package io.github.xis3794.mirrorbox.core

import android.content.Context
import android.os.Build
import android.os.Environment
import java.io.File

/**
 * Central place for every directory MirrorBox uses.
 *
 * Layout:
 *  - files/mirrorbox/home       HOME for bundled native tools (config files, temporary state)
 *  - files/mirrorbox/images     the image library (qcow2 / vmdk / raw ...)
 *  - files/mirrorbox/iso        ISO projects and results
 *  - files/mirrorbox/work       scratch space for extract → edit → write back pipelines
 *  - files/mirrorbox/logs       per task logs
 *  - cache/tmp                  TMPDIR for the tools
 *  - external ../Android/data/<pkg>/files/MirrorBox   user visible working area (no permission needed)
 */
object AppPaths {

    private lateinit var ctx: Context

    fun init(context: Context) {
        ctx = context.applicationContext
    }

    val root: File get() = File(ctx.filesDir, "mirrorbox").apply { mkdirs() }
    val home: File get() = File(root, "home").apply { mkdirs() }
    val images: File get() = File(root, "images").apply { mkdirs() }
    val iso: File get() = File(root, "iso").apply { mkdirs() }
    val work: File get() = File(root, "work").apply { mkdirs() }
    val logs: File get() = File(root, "logs").apply { mkdirs() }
    val tmp: File get() = File(ctx.cacheDir, "tmp").apply { mkdirs() }

    /** External, user visible area — a real filesystem path, so bundled tools can work in place. */
    fun externalRoot(): File {
        val ext = ctx.getExternalFilesDir(null)
        return if (ext != null) File(ext, "MirrorBox").apply { mkdirs() } else root
    }

    fun imports(): File = File(externalRoot(), "imports").apply { mkdirs() }

    /** /sdcard style access is only possible with MANAGE_EXTERNAL_STORAGE (optional user choice). */
    fun hasAllFilesAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true
        }

    fun uniqueFile(dir: File, baseName: String, extension: String): File {
        dir.mkdirs()
        var candidate = File(dir, "$baseName$extension")
        var i = 1
        while (candidate.exists()) {
            candidate = File(dir, "$baseName-$i$extension")
            i++
        }
        return candidate
    }

    fun sizeOfTree(dir: File): Long {
        var total = 0L
        val kids = dir.listFiles() ?: return 0L
        for (f in kids) {
            total += if (f.isDirectory) sizeOfTree(f) else f.length()
        }
        return total
    }
}