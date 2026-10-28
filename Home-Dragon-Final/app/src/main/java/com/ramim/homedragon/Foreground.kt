package com.ramim.homedragon

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Process

/**
 * Which app is in front, asked straight from Android (usage access). It names the app even when its window cannot be read
 * (banking apps, secure screens), and it is a cheap lookup: only the few events since the last call are read.
 * Usage access is a special permission the user switches on by hand; without it every call answers null and the icon finder
 * works as before.
 */
object Foreground {
    private var pkg: String? = null
    private var lastQuery = 0L

    fun granted(c: Context): Boolean {
        return try {
            val ops = c.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.packageName)
            if (mode == AppOpsManager.MODE_DEFAULT) {
                c.checkCallingOrSelfPermission(android.Manifest.permission.PACKAGE_USAGE_STATS) == PackageManager.PERMISSION_GRANTED
            } else {
                mode == AppOpsManager.MODE_ALLOWED
            }
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Package of the app in front, or null when it is not known (no usage access, no recent event, or the app is changing right now).
     * Home Dragon itself counts as unknown.
     */
    fun current(c: Context): String? {
        if (!granted(c)) { pkg = null; lastQuery = 0L; return null }
        try {
            val usm = c.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val now = System.currentTimeMillis()
            val from = if (lastQuery == 0L) now - 600_000L else lastQuery - 2_000L
            val events = usm.queryEvents(from, now)
            val e = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(e)
                when (e.eventType) {
                    UsageEvents.Event.ACTIVITY_RESUMED -> pkg = e.packageName
                    UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED -> if (e.packageName == pkg) pkg = null
                    UsageEvents.Event.SCREEN_NON_INTERACTIVE, UsageEvents.Event.KEYGUARD_SHOWN -> pkg = null
                }
            }
            lastQuery = now
        } catch (_: Throwable) {
            return null
        }
        val p = pkg
        return if (p == null || p == c.packageName) null else p
    }
}
