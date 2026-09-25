package io.github.xis3794.mirrorbox.core

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap

enum class TaskStatus { RUNNING, SUCCESS, FAILED, CANCELLED }

data class TaskItem(
    val id: Long,
    val title: String,
    val detail: String,
    val toolName: String,
    val createdAt: Long,
    val status: TaskStatus,
    val logFile: String,
    val progress: Int? = null,
    val exitCode: Int? = null,
    val finishedAt: Long? = null,
    val error: String? = null,
)

/**
 * Every long running native operation goes through here:
 *  - live log lines are streamed to the task console and persisted to a log file
 *  - progress percentages are parsed from the tool output
 *  - a foreground service keeps the process alive while tasks run
 */
object TaskManager {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _tasks = MutableStateFlow<List<TaskItem>>(emptyList())
    val tasks: StateFlow<List<TaskItem>> = _tasks

    private val processes = ConcurrentHashMap<Long, Process>()
    private var counter = 1L
    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun logFileFor(id: Long): File = File(AppPaths.logs, "task-$id.log")

    fun submit(
        title: String,
        detail: String,
        tool: NativeTool,
        args: List<String>,
        cwd: File? = null,
        parseProgress: ((String) -> Int?)? = null,
        onFinish: ((TaskItem) -> Unit)? = null,
    ): Long {
        val id = counter++
        val item = TaskItem(
            id = id,
            title = title,
            detail = detail,
            toolName = tool.displayName,
            createdAt = System.currentTimeMillis(),
            status = TaskStatus.RUNNING,
            logFile = logFileFor(id).absolutePath,
        )
        _tasks.value = listOf(item) + _tasks.value
        runCatching { OperationService.start(appContext, title) }
        scope.launch { execute(item, tool, args, cwd, parseProgress, onFinish) }
        return id
    }

    private fun execute(
        item: TaskItem,
        tool: NativeTool,
        args: List<String>,
        cwd: File?,
        parseProgress: ((String) -> Int?)?,
        onFinish: ((TaskItem) -> Unit)?,
    ) {
        val logFile = logFileFor(item.id).apply {
            parentFile?.mkdirs()
            writeText("")
        }
        var exitCode = -1
        var failure: String? = null
        try {
            val exe = NativeTools.resolve(appContext, tool)
                ?: throw IllegalStateException("未找到 ${tool.displayName}：APK 未包含原生工具链")
            logFile.appendText("# ${tool.displayName} ${args.joinToString(" ")}\n")
            val process = ToolRunner.buildProcess(appContext, exe, args, cwd)
            processes[item.id] = process
            ToolRunner.pump(process.inputStream) { line ->
                runCatching { logFile.appendText(line + "\n") }
                val pct = parseProgress?.invoke(line)
                if (pct != null) update(item.id) { it.copy(progress = pct.coerceIn(0, 100)) }
            }
            exitCode = process.waitFor()
        } catch (t: Throwable) {
            failure = t.message ?: t.javaClass.simpleName
        } finally {
            processes.remove(item.id)
        }

        val cancelled = _tasks.value.firstOrNull { it.id == item.id }?.status == TaskStatus.CANCELLED
        val ok = exitCode == 0 && failure == null && !cancelled
        var finalItem: TaskItem? = null
        update(item.id) { current ->
            val updated = current.copy(
                status = when {
                    cancelled -> TaskStatus.CANCELLED
                    ok -> TaskStatus.SUCCESS
                    else -> TaskStatus.FAILED
                },
                exitCode = exitCode,
                finishedAt = System.currentTimeMillis(),
                progress = if (ok) 100 else current.progress,
                error = failure ?: if (ok) null else "退出码 $exitCode",
            )
            finalItem = updated
            updated
        }
        finalItem?.let { onFinish?.invoke(it) }
        OperationService.stopIfIdle(appContext, _tasks.value)
    }

    fun cancel(id: Long) {
        processes[id]?.let { process -> runCatching { process.destroy() } }
        update(id) {
            if (it.status == TaskStatus.RUNNING) {
                it.copy(status = TaskStatus.CANCELLED, finishedAt = System.currentTimeMillis(), error = "已取消")
            } else {
                it
            }
        }
    }

    fun clearFinished() {
        _tasks.value = _tasks.value.filter { it.status == TaskStatus.RUNNING }
    }

    fun runningCount(): Int = _tasks.value.count { it.status == TaskStatus.RUNNING }

    fun readLog(path: String): String = try {
        File(path).readText()
    } catch (t: Throwable) {
        ""
    }

    private fun update(id: Long, transform: (TaskItem) -> TaskItem) {
        _tasks.value = _tasks.value.map { if (it.id == id) transform(it) else it }
    }

    /** Progress strings look like `(12.34/100%)` for qemu-img and `xx%` for xorriso. */
    fun parsePercent(line: String): Int? {
        val slash = Regex("""\((\d+(?:\.\d+)?)/100%\)""").find(line)
        if (slash != null) return slash.groupValues[1].toDoubleOrNull()?.toInt()
        val plain = Regex("""(\d{1,3}(?:\.\d+)?)%""").find(line)
        if (plain != null) return plain.groupValues[1].toDoubleOrNull()?.toInt()
        return null
    }
}