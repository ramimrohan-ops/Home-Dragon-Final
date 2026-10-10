package com.ramim.homedragon

import android.content.Context
import android.content.Intent

/**
 * Tells the Phone Status app whether the home screen is on top, so its widget only updates while you can see it.
 * The message goes only to the Phone Status package (an explicit broadcast) and holds three values:
 * home (true/false), why (a short reason for its Debug tab) and t (time). It carries no screen contents.
 */
object HomeSignal {
    const val ACTION = "com.ramim.homedragon.HOME_STATE"
    const val TARGET = "dev.ramim.phonestatus"

    fun send(c: Context, home: Boolean, why: String) {
        try {
            c.sendBroadcast(
                Intent(ACTION)
                    .setPackage(TARGET)
                    .putExtra("home", home)
                    .putExtra("why", why)
                    .putExtra("t", System.currentTimeMillis())
            )
        } catch (_: Throwable) {
        }
    }
}
