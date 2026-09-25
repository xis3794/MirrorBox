package io.github.xis3794.mirrorbox

import android.app.Application
import io.github.xis3794.mirrorbox.core.AppPaths
import io.github.xis3794.mirrorbox.core.Prefs
import io.github.xis3794.mirrorbox.core.TaskManager

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        AppPaths.init(this)
        Prefs.init(this)
        TaskManager.init(this)
    }
}