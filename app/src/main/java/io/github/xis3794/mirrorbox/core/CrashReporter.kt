package io.github.xis3794.mirrorbox.core

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 最低限度的崩溃记录：把未捕获异常的堆栈写到 `files/mirrorbox/logs/crash-<时间>.txt`，并把路径
 * 记在 Prefs 里，下次启动后可以在「设置 → 诊断」里查看/导出。
 *
 * 目的很实际：真机上闪退时用户拿不到 logcat（尤其是华为等没有 Google 服务的机器），
 * 有了这个文件就能把现场带回来。
 */
object CrashReporter {

    /** 记录未捕获异常；必须在 Application.onCreate 里调用（越早越好）。 */
    fun install(context: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { persist(context, thread, throwable) }
            if (previous != null) {
                previous.uncaughtException(thread, throwable)
            } else {
                android.os.Process.killProcess(android.os.Process.myPid())
            }
        }
    }

    fun lastCrashFile(): File? = Prefs.lastCrashPath?.takeIf { it.isNotBlank() }?.let { File(it) }?.takeIf { it.isFile }

    fun readLastCrash(): String? = runCatching { lastCrashFile()?.readText() }.getOrNull()

    fun clearLastCrash() {
        runCatching { lastCrashFile()?.delete() }
        Prefs.lastCrashPath = null
    }

    private fun persist(context: Context, thread: Thread, throwable: Throwable) {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(AppPaths.logs, "crash-$stamp.txt")
        file.parentFile?.mkdirs()
        StringWriter().use { buffer ->
            PrintWriter(buffer).use { writer ->
                writer.println("time   : ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
                writer.println("thread : ${thread.name}")
                writer.println("android: ${android.os.Build.VERSION.SDK_INT} (${android.os.Build.MODEL})")
                writer.println("version: ${runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()}")
                writer.println()
                throwable.printStackTrace(writer)
            }
            file.writeText(buffer.toString())
        }
        Prefs.lastCrashPath = file.absolutePath
    }
}