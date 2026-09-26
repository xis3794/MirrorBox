package io.github.xis3794.mirrorbox

import android.app.Application
import io.github.xis3794.mirrorbox.core.AppPaths
import io.github.xis3794.mirrorbox.core.CrashReporter
import io.github.xis3794.mirrorbox.core.Prefs
import io.github.xis3794.mirrorbox.core.TaskManager

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        AppPaths.init(this)
        Prefs.init(this)
        TaskManager.init(this)
        // 真机上闪退拿不到 logcat，先把堆栈落到文件里（设置 → 诊断 可查看/导出）。
        CrashReporter.install(this)
    }
}