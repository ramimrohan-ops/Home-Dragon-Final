package com.ramim.homedragon

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // also after the app is updated: Android stops the old service during an update and nothing else would start it again
        if ((intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) &&
            Prefs.enabled(context) && Settings.canDrawOverlays(context)
        ) {
            context.startForegroundService(Intent(context, DragonService::class.java))
        }
    }
}
