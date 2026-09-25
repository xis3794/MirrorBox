package io.github.xis3794.mirrorbox.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder

/**
 * Keeps the process (and therefore running native tools) alive while long operations execute.
 * MirrorBox deliberately runs fully offline and without any background work when idle.
 */
class OperationService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val title = intent?.getStringExtra(EXTRA_TITLE) ?: "镜像匣"
        ensureChannel(this)
        val notification: Notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("镜像匣 · 任务运行中")
            .setContentText(title)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        startForeground(NOTIFICATION_ID, notification)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
    }

    companion object {
        private const val CHANNEL_ID = "mirrorbox_tasks"
        private const val NOTIFICATION_ID = 0x4D42
        const val EXTRA_TITLE = "title"

        fun start(ctx: Context, title: String) {
            val intent = Intent(ctx, OperationService::class.java).putExtra(EXTRA_TITLE, title)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ctx.startForegroundService(intent)
                } else {
                    ctx.startService(intent)
                }
            }
        }

        fun stopIfIdle(ctx: Context, tasks: List<TaskItem>) {
            if (tasks.none { it.status == TaskStatus.RUNNING }) {
                runCatching { ctx.stopService(Intent(ctx, OperationService::class.java)) }
            }
        }

        private fun ensureChannel(ctx: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = ctx.getSystemService(NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(CHANNEL_ID) != null) return
            val channel = NotificationChannel(
                CHANNEL_ID,
                "镜像匣任务",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "磁盘镜像转换、检查与编辑任务的进度通知"
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
    }
}