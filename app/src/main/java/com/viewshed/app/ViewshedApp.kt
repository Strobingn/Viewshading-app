package com.viewshed.app

import android.app.Application

class ViewshedApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ThemePreferences.applySaved(this)
    }
}
