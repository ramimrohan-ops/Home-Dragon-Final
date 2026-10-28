package com.ramim.homedragon.nearby

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * Connection lifecycle for an active BLE / Bluetooth / Wi-Fi Direct link to a peer.
 *
 * Runs as a foreground service so the link survives screen-off while a battle is in progress.
 * The actual socket/GATT wiring is connected on the first device build.
 */
class NearbyService : Service() {

    companion object {
        const val ACTION_CONNECT = "com.ramim.homedragon.CONNECT"
        const val ACTION_DISCONNECT = "com.ramim.homedragon.DISCONNECT"
        const val ACTION_SEND = "com.ramim.homedragon.SEND"
        const val EXTRA_NAME = "name"
        const val EXTRA_ADDRESS = "address"
        const val EXTRA_TYPE = "type"
        const val EXTRA_MESSAGE = "message"
    }

    private var connectedPeer: DragonProfile? = null
    private var sendListener: ((NearbyMessage) -> Unit)? = null

    /** Set a listener to receive decoded inbound messages. Called from the platform socket thread. */
    fun setSendListener(l: ((NearbyMessage) -> Unit)?) { sendListener = l }

    /** The peer currently linked, or null if not connected. */
    val linkedPeer: DragonProfile? get() = connectedPeer

    /** Send an encoded message to the linked peer. No-op until the platform socket is wired. */
    fun send(msg: NearbyMessage) {
        val bytes = NearbyProtocol.encode(msg)
        // TODO: write bytes to the connected BluetoothSocket / WifiP2p socket / GATT characteristic
    }

    override fun onBind(intent: Intent): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        when (action) {
            ACTION_CONNECT -> {
                val name = intent.getStringExtra(EXTRA_NAME) ?: ""
                val address = intent.getStringExtra(EXTRA_ADDRESS) ?: ""
                val typeName = intent.getStringExtra(EXTRA_TYPE) ?: "UNKNOWN"
                val type = DragonProfile.ProfileType.values().firstOrNull { it.name == typeName }
                    ?: DragonProfile.ProfileType.UNKNOWN
                connectedPeer = DragonProfile(name, address, type)
            }
            ACTION_DISCONNECT -> {
                connectedPeer = null
            }
            ACTION_SEND -> {
                intent.getStringExtra(EXTRA_MESSAGE)?.let { text ->
                    sendListener?.invoke(NearbyProtocol.decode(text.toByteArray()))
                }
            }
        }
        return android.app.Service.START_NOT_STICKY
    }

    override fun onDestroy() {
        connectedPeer = null
        sendListener = null
        super.onDestroy()
    }
}
