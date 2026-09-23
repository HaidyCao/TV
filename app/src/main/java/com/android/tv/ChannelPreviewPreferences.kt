package com.android.tv

import android.content.Context

/** Shared TV and phone preference for the optional muted channel preview. */
object ChannelPreviewPreferences {
    private const val PREFS_NAME = "channel_preview_preferences"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_TV_MODE = "tv_mode"

    fun isEnabled(context: Context): Boolean {
        return context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, true)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
    }

    /** TV preview has its own setting; the legacy boolean remains the phone setting. */
    fun getTvMode(context: Context): TvChannelPreviewMode {
        val preferences = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val storedMode = preferences.getString(KEY_TV_MODE, null)
        val mode = TvChannelPreviewModePolicy.fromStoredOrLegacy(
            storedMode = storedMode,
            legacyEnabled = preferences.getBoolean(KEY_ENABLED, true)
        )
        if (storedMode == null) {
            preferences.edit().putString(KEY_TV_MODE, mode.preferenceValue).apply()
        }
        return mode
    }

    fun setTvMode(context: Context, mode: TvChannelPreviewMode) {
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_TV_MODE, mode.preferenceValue)
            .apply()
    }
}
