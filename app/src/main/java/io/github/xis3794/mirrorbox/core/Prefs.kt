package io.github.xis3794.mirrorbox.core

import android.content.Context
import android.content.SharedPreferences

/** Small typed wrapper around SharedPreferences — no extra dependency needed. */
object Prefs {

    private lateinit var sp: SharedPreferences

    fun init(context: Context) {
        sp = context.getSharedPreferences("mirrorbox_prefs", Context.MODE_PRIVATE)
    }

    /** 0 = follow system, 1 = light, 2 = dark */
    var themeMode: Int
        get() = sp.getInt("theme_mode", 0)
        set(value) = sp.edit().putInt("theme_mode", value).apply()

    /** Reduces blur/translucency for low end devices. */
    var reduceEffects: Boolean
        get() = sp.getBoolean("reduce_effects", false)
        set(value) = sp.edit().putBoolean("reduce_effects", value).apply()

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