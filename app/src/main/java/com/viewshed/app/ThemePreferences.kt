package com.viewshed.app

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

/**
 * Persists light / dark / system theme choice.
 */
object ThemePreferences {
    private const val PREFS = "viewshed_settings"
    private const val KEY_MODE = "theme_mode"

    const val MODE_SYSTEM = 0
    const val MODE_LIGHT = 1
    const val MODE_DARK = 2

    fun getMode(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_MODE, MODE_SYSTEM)

    fun setMode(context: Context, mode: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_MODE, mode)
            .apply()
        applyMode(mode)
    }

    fun applySaved(context: Context) {
        applyMode(getMode(context.applicationContext))
    }

    fun applyMode(mode: Int) {
        val night = when (mode) {
            MODE_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            MODE_DARK -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        AppCompatDelegate.setDefaultNightMode(night)
    }

    fun labelRes(mode: Int): Int = when (mode) {
        MODE_LIGHT -> R.string.theme_light
        MODE_DARK -> R.string.theme_dark
        else -> R.string.theme_system
    }
}
