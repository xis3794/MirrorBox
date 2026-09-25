package io.github.xis3794.mirrorbox.core

import android.content.Context
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

data class ToolResult(val exitCode: Int, val lines: List<String>) {
    val output: String get() = lines.joinToString("\n")
    val success: Boolean get() = exitCode == 0
}

/**
 * Executes bundled native tools with a sanely controlled environment
 * (HOME / TMPDIR / LD_LIBRARY_PATH / PATH pointing at the app's own directories).
 */
object ToolRunner {

    fun environment(context: Context): Map<String, String> {
        val nativeDir = context.applicationInfo?.nativeLibraryDir.orEmpty()
        val env = HashMap<String, String>()
        env["HOME"] = AppPaths.home.absolutePath
        env["TMPDIR"] = AppPaths.tmp.absolutePath
        env["TMP"] = AppPaths.tmp.absolutePath
        env["LD_LIBRARY_PATH"] = listOf(nativeDir, AppPaths.root.absolutePath).filter { it.isNotEmpty() }.joinToString(":")
        env["PATH"] = listOf(nativeDir, "/system/bin", "/system/xbin").filter { it.isNotEmpty() }.joinToString(":")
        env["LANG"] = "C"
        env["LC_ALL"] = "C"
        env["TERM"] = "dumb"
        // mtools refuses to touch images it considers suspicious; it is safe for us to relax that.
        env["MTOOLS_SKIP_CHECK"] = "1"
        return env
    }

    fun buildProcess(context: Context, executable: File, args: List<String>, cwd: File?): Process {
        val cmd = ArrayList<String>(args.size + 1)
        cmd.add(executable.absolutePath)
        cmd.addAll(args)
        val pb = ProcessBuilder(cmd)
        pb.redirectErrorStream(true)
        pb.directory((cwd ?: AppPaths.work).also { it.mkdirs() })
        pb.environment().putAll(environment(context))
        return pb.start()
    }

    /** Pumps a tool's output, splitting on both \n and \r so live progress updates are not delayed. */
    fun pump(stream: InputStream, onLine: (String) -> Unit) {
        val reader = InputStreamReader(stream, Charsets.UTF_8)
        val sb = StringBuilder()
        val buf = CharArray(2048)
        while (true) {
            val n = try {
                reader.read(buf)
            } catch (t: Throwable) {
                break
            }
            if (n < 0) break
            for (i in 0 until n) {
                val c = buf[i]
                if (c == '\n' || c == '\r') {
                    if (sb.isNotEmpty()) {
                        onLine(sb.toString())
                        sb.setLength(0)
                    }
                } else {
                    sb.append(c)
                }
            }
        }
        if (sb.isNotEmpty()) onLine(sb.toString())
    }

    suspend fun run(
        context: Context,
        tool: NativeTool,
        args: List<String>,
        cwd: File? = null,
        onLine: (String) -> Unit = {},
    ): ToolResult {
        val exe = NativeTools.resolve(context, tool)
            ?: return ToolResult(127, listOf("未找到 ${tool.displayName}：APK 中未包含原生工具链"))
        return try {
            val process = buildProcess(context, exe, args, cwd)
            val lines = ArrayList<String>()
            pump(process.inputStream) { line ->
                lines.add(line)
                onLine(line)
            }
            val code = process.waitFor()
            ToolResult(code, lines)
        } catch (t: Throwable) {
            ToolResult(-1, listOf("执行 ${tool.displayName} 失败：${t.message}"))
        }
    }

    /** Returns the first line of `tool --version`, or null when the tool is missing / broken. */
    fun version(context: Context, tool: NativeTool): String? {
        val exe = NativeTools.resolve(context, tool) ?: return null
        return try {
            val process = buildProcess(context, exe, tool.versionFlag, null)
            val first = StringBuilder()
            pump(process.inputStream) { line ->
                if (first.isEmpty() && line.isNotBlank()) first.append(line.trim())
            }
            process.waitFor(6, TimeUnit.SECONDS)
            if (process.isAlive) process.destroy()
            first.toString().ifBlank { null }
        } catch (t: Throwable) {
            null
        }
    }
}