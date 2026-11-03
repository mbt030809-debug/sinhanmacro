package com.sinhanmacro.app

import android.app.Application

class MacroApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Engine.attach(this)
    }
}
