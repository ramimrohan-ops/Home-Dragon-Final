package com.ramim.homedragon.nearby

import android.content.Context

/** A peer discovered during a scan, with when it was last seen. */
data class DiscoveredPeer(val profile: DragonProfile, val timestamp: Long = System.currentTimeMillis())

/** Listens for discovery events from [NearbyDiscovery]. */
interface DiscoveryListener {
    fun onPeerFound(peer: DiscoveredPeer)
    fun onPeerLost(address: String)
    fun onScanError(error: String)
}

/**
 * Scans for nearby peers over BLE, Bluetooth and Wi-Fi Direct.
 *
 * The platform adapter wiring (BluetoothAdapter / WifiP2pManager / BluetoothLeScanner) is connected
 * on the first device build. Until then, startScan/stopScan are no-ops so the app compiles and runs
 * without crashing on emulators that lack Bluetooth hardware.
 */
class NearbyDiscovery(private val context: Context) {
    private val peers = mutableMapOf<String, DiscoveredPeer>()
    private var listener: DiscoveryListener? = null

    fun setListener(l: DiscoveryListener?) { listener = l }

    /** Begins scanning for peers. No-op until platform adapters are wired. */
    fun startScan() {
        // TODO: wire BluetoothAdapter.startDiscovery / BluetoothLeScanner / WifiP2pManager.discoverPeers
    }

    /** Stops scanning. */
    fun stopScan() {
        // TODO: cancel platform discovery
    }

    /** All peers currently known. */
    val allPeers: List<DiscoveredPeer>
        get() = peers.values.toList()

    /** Look up a peer by address. */
    fun peerFor(address: String): DiscoveredPeer? = peers[address]

    /** Called by the platform adapter when a peer is found. */
    internal fun onPeerDiscovered(profile: DragonProfile) {
        val peer = DiscoveredPeer(profile)
        peers[profile.address] = peer
        listener?.onPeerFound(peer)
    }

    /** Called by the platform adapter when a peer goes out of range. */
    internal fun onPeerLost(address: String) {
        peers.remove(address)
        listener?.onPeerLost(address)
    }

    /** Called by the platform adapter on scan error. */
    internal fun onScanError(error: String) {
        listener?.onScanError(error)
    }
}
