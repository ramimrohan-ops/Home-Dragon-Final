package com.ramim.homedragon

import android.content.Context
import android.content.Intent

/**
 * Tells the Phone Status app whether the home screen is on top, so its widget only updates while you can see it.
 * Messages go only to the Phone Status package (explicit broadcasts) and carry no screen contents:
 *  - HOME_STATE (this app -> Phone Status): home (true/false), why (short reason), seq (message number), t (time).
 *  - HOME_ACK (Phone Status -> this app): seq and home of the message it received.
 *  - HOME_ASK (Phone Status -> this app): "what is the state now?", sent when Phone Status starts.
 */
object HomeSignal {
    const val ACTION = "com.ramim.homedragon.HOME_STATE"
    const val ACTION_ACK = "dev.ramim.phonestatus.HOME_ACK"
    const val ACTION_ASK = "dev.ramim.phonestatus.HOME_ASK"
    const val TARGET = "dev.ramim.phonestatus"

    fun send(c: Context, home: Boolean, why: String, seq: Long) {
        try {
            c.sendBroadcast(
                Intent(ACTION)
                    .setPackage(TARGET)
                    .putExtra("home", home)
                    .putExtra("why", why)
                    .putExtra("seq", seq)
                    .putExtra("t", System.currentTimeMillis())
            )
        } catch (_: Throwable) {
        }
    }
}
