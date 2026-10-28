package com.ramim.homedragon.nearby

/** A peer device discovered over BLE, Bluetooth or Wi-Fi Direct. */
data class DragonProfile(
    /** Human-readable device name (may be blank). */
    val name: String = "",
    /** BLE MAC address or Wi-Fi Direct device address. */
    val address: String = "",
    /** Transport type the peer was discovered on. */
    val type: ProfileType = ProfileType.UNKNOWN,
    /** Last seen signal strength (BLE only), in dBm. */
    val rssi: Int = 0,
    /** Epoch millis when the profile was last updated. */
    val lastSeen: Long = System.currentTimeMillis()
) {
    enum class ProfileType { UNKNOWN, BLE, BLUETOOTH, WIFI_DIRECT }

    /** Name for display, falling back to the address when the name is empty. */
    fun displayName(): String = if (name.isNotBlank()) name else address
}
