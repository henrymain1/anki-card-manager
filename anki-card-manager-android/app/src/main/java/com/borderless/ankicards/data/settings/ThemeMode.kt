package com.borderless.ankicards.data.settings

/**
 * User's preferred theme. Persisted in [SettingsRepository] as a short code so
 * the value survives the (very rare) case where the enum changes shape.
 */
enum class ThemeMode(val code: String, val displayName: String) {
    /** Follow the OS-level light/dark setting. Default. */
    System("system", "Follow system"),
    Light("light", "Light"),
    Dark("dark", "Dark");

    companion object {
        fun fromCode(code: String?): ThemeMode =
            entries.firstOrNull { it.code == code } ?: System
    }
}
