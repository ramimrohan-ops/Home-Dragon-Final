package com.ramim.homedragon

import android.content.Context
import android.content.Intent

/**
 * Tells the Phone Status app one of two things, so its widget only updates while you can see it on the right page:
 *  - Run: the home screen is on top (phone unlocked, screen on, no keyboard, no shade) AND the page bar names page [RUN_PAGE].
 *  - Don't run: anything else (another page, another app, recents, a locked or dark screen).
 * Messages go only to the Phone Status package (explicit broadcasts) and carry no screen contents:
 *  - HOME_STATE (this app -> Phone Status): run (true/false), why (short reason), seq (message number), t (time).
 *  - HOME_ACK (Phone Status -> this app): seq and run of the message it received.
 *  - HOME_ASK (Phone Status -> this app): "what is the state now?", sent when Phone Status starts.
 */
object HomeSignal {
    const val ACTION = "com.ramim.homedragon.HOME_STATE"
    const val ACTION_ACK = "dev.ramim.phonestatus.HOME_ACK"
    const val ACTION_ASK = "dev.ramim.phonestatus.HOME_ASK"
    const val TARGET = "dev.ramim.phonestatus"

    /** The home screen page the Phone Status widget lives on: "Run" is only sent there. */
    const val RUN_PAGE = 2

    fun send(c: Context, run: Boolean, why: String, seq: Long) {
        try {
            c.sendBroadcast(
                Intent(ACTION)
                    .setPackage(TARGET)
                    .putExtra("run", run)
                    .putExtra("why", why)
                    .putExtra("seq", seq)
                    .putExtra("t", System.currentTimeMillis())
            )
        } catch (_: Throwable) {
        }
    }
}
