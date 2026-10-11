package com.ramim.homedragon

import android.app.Application
import android.util.Log
import java.io.File

/** Saves the stack trace of any crash so the next launch can show it on screen. */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.dropOldKeys(this)                  // settings that no longer exist
        DragonPalette.load(this)                 // the saved dragon colours, before any dragon is drawn
        Diag.log(this, "App process started")
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                File(filesDir, "crash.txt").writeText(Log.getStackTraceString(error))
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(thread, error)
        }
    }
}
