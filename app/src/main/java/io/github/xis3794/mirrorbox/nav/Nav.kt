package io.github.xis3794.mirrorbox.nav

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Lightweight navigation: a typed screen stack with no extra dependency.
 * (Kept intentionally small — the app has ~10 destinations and full control over transitions.)
 */
sealed interface Screen {
    data object Home : Screen
    data object Library : Screen
    data object Tools : Screen
    data object Tasks : Screen
    data object Settings : Screen
    data object Create : Screen
    data object Convert : Screen
    data object IsoStudio : Screen
    data class Inspector(val path: String) : Screen
    data class Editor(val path: String) : Screen
    data class Partitions(val path: String) : Screen

    /** DISM++ 式「释放 WIM」；[path] 为可选的目标镜像（为空时只能展开到目录）。 */
    data class WimRelease(val path: String? = null) : Screen

    /** 客户机文件浏览器（extract → debugfs/mtools/ntfs → commit）。 */
    data class GuestFiles(val path: String) : Screen

    data class SelfCheck(val placeholder: Boolean = false) : Screen
}

class Navigator(initial: Screen = Screen.Home) {
    var stack by mutableStateOf(listOf(initial))
        private set

    val current: Screen get() = stack.last()
    val canGoBack: Boolean get() = stack.size > 1

    fun push(screen: Screen) {
        stack = stack + screen
    }

    fun pop(): Boolean {
        if (stack.size <= 1) return false
        stack = stack.dropLast(1)
        return true
    }

    fun resetTo(screen: Screen) {
        stack = listOf(screen)
    }
}