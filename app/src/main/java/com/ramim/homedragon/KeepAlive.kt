package com.ramim.homedragon

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A short event log saved on the phone (last 16 events). It survives the app being killed, so after a lock/unlock you can see
 * in the app what really happened: did the dragon service stop, did the icon finder disconnect, was the app process restarted.
 * It stores only event names and times, nothing else.
 */
object Diag {
    private const val MAX = 16

    private fun sp(c: Context) = c.applicationContext.getSharedPreferences("dragon_diag", Context.MODE_PRIVATE)

    @Synchronized
    fun log(c: Context, msg: String) {
        try {
            val s = sp(c)
            val lines = (s.getString("log", "") ?: "").split('\n').filter { it.isNotEmpty() }.toMutableList()
            lines.add(System.currentTimeMillis().toString() + "|" + msg.replace('\n', ' ').replace('|', '/'))
            while (lines.size > MAX) lines.removeAt(0)
            s.edit().putString("log", lines.joinToString("\n")).commit()   // commit: the process may be killed a moment later
        } catch (_: Throwable) {
        }
    }

    /** Newest first, e.g. "23:41:07  Screen off". */
    @Synchronized
    fun lines(c: Context): List<String> {
        val fmt = SimpleDateFormat("d MMM HH:mm:ss", Locale.getDefault())
        val raw = (sp(c).getString("log", "") ?: "").split('\n').filter { it.isNotEmpty() }
        return raw.reversed().map {
            val i = it.indexOf('|')
            if (i < 0) it else fmt.format(Date(it.substring(0, i).toLongOrNull() ?: 0L)) + "  " + it.substring(i + 1)
        }
    }

    @Synchronized
    fun clear(c: Context) {
        try { sp(c).edit().remove("log").commit() } catch (_: Throwable) {}
    }

    fun restartCount(c: Context) = sp(c).getInt("restarts", 0)
    fun addRestart(c: Context) { sp(c).edit().putInt("restarts", restartCount(c) + 1).apply() }
}

/**
 * Samsung One UI has no autostart manager, so the dragon service can be stopped while the phone sleeps. The icon finder
 * (accessibility service) is restarted by Android itself, so it is used as the way back: whenever it connects or sees the
 * screen change and the dragon should be on but its service is gone, it starts the service again.
 * This cannot work if Samsung also switched the icon finder off or force-stopped the whole app.
 */
object KeepAlive {
    private var lastTry = 0L

    fun ensureDragon(c: Context, why: String) {
        if (DragonService.instance != null) return                 // already running: nothing to do (cheap check first)
        if (!Prefs.enabled(c)) return                               // the user stopped the dragon on purpose
        if (!Settings.canDrawOverlays(c)) return
        val now = SystemClock.elapsedRealtime()
        if (lastTry != 0L && now - lastTry < 30_000) return         // do not retry in a loop if something keeps failing
        lastTry = now
        try {
            ContextCompat.startForegroundService(c, Intent(c, DragonService::class.java))
            Diag.addRestart(c)
            Diag.log(c, "Dragon restarted by the icon finder ($why)")
        } catch (t: Throwable) {
            Diag.log(c, "Dragon restart blocked: " + t.javaClass.simpleName)
        }
    }
}
