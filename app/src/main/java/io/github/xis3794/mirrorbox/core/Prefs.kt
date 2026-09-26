package io.github.xis3794.mirrorbox.core

import android.content.Context
import android.content.SharedPreferences

/** Small typed wrapper around SharedPreferences — no extra dependency needed. */
object Prefs {

    private lateinit var sp: SharedPreferences
    private var lowRamDevice = false

    fun init(context: Context) {
        sp = context.getSharedPreferences("mirrorbox_prefs", Context.MODE_PRIVATE)
        lowRamDevice = runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val info = android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            // 低内存设备，或总内存 < 4 GB —— 这类机器上每帧少画两层就明显不卡。
            am.isLowRamDevice || info.totalMem in 1 until 4L * 1024 * 1024 * 1024
        }.getOrDefault(false)
        // 旧版本开关（reduce_effects）迁移到新的 performance_mode，用户之前的选择不丢。
        if (sp.contains("reduce_effects") && !sp.contains("performance_mode")) {
            sp.edit().putBoolean("performance_mode", sp.getBoolean("reduce_effects", false)).apply()
        }
    }

    /** 0 = follow system, 1 = light, 2 = dark */
    var themeMode: Int
        get() = sp.getInt("theme_mode", 0)
        set(value) = sp.edit().putInt("theme_mode", value).apply()

    /**
     * 性能模式：纯色卡片、无背景光晕、无过渡动画。
     * 低内存设备默认开启（用户可在设置里改）。
     */
    var performanceMode: Boolean
        get() = sp.getBoolean("performance_mode", lowRamDevice)
        set(value) = sp.edit().putBoolean("performance_mode", value).apply()

    /** 兼容旧键：等价于「性能模式」。 */
    var reduceEffects: Boolean
        get() = performanceMode
        set(value) {
            performanceMode = value
        }

    /** 过渡/指示器动画是否启用。 */
    val animationsEnabled: Boolean get() = !performanceMode

    /** 0..100 glass strength used by GlassSurface. */
    var glassIntensity: Int
        get() = sp.getInt("glass_intensity", 100)
        set(value) = sp.edit().putInt("glass_intensity", value).apply()

    /** Run `qemu-img check` after every mutating operation. */
    var autoCheckAfterWrite: Boolean
        get() = sp.getBoolean("auto_check", true)
        set(value) = sp.edit().putBoolean("auto_check", value).apply()

    /** Ask before destructive operations (delete / zero / resize shrink). */
    var confirmDestructive: Boolean
        get() = sp.getBoolean("confirm_destructive", true)
        set(value) = sp.edit().putBoolean("confirm_destructive", value).apply()

    /** Optional external directory containing user supplied native tools. */
    var externalToolDir: String?
        get() = sp.getString("external_tool_dir", null)
        set(value) = sp.edit().putString("external_tool_dir", value).apply()

    var lastIsoSourceDir: String?
        get() = sp.getString("last_iso_source", null)
        set(value) = sp.edit().putString("last_iso_source", value).apply()

    var onboardingDone: Boolean
        get() = sp.getBoolean("onboarding_done", false)
        set(value) = sp.edit().putBoolean("onboarding_done", value).apply()
}