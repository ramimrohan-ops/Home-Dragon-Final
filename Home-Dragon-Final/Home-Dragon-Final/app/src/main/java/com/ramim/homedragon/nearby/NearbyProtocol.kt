package com.ramim.homedragon.nearby

import java.nio.charset.Charset

/**
 * Messages exchanged during the BLE / Bluetooth / Wi-Fi Direct handshake.
 *
 * Wire format: 1 byte message type, then a UTF-8 payload of `key=value` pairs separated by `|`.
 * Kept dependency-free so the handshake works with only androidx.core on the classpath.
 */
sealed class NearbyMessage {
    data class Handshake(val profile: DragonProfile) : NearbyMessage()
    data class HandshakeAck(val accepted: Boolean, val reason: String = "") : NearbyMessage()
    data class BattleInvite(val arenaId: String, val from: String) : NearbyMessage()
    data class BattleAccept(val arenaId: String) : NearbyMessage()
    data class Ping(val timeMillis: Long) : NearbyMessage()
    data class Pong(val timeMillis: Long) : NearbyMessage()
    object Bye : NearbyMessage()
}

object NearbyProtocol {
    private const val TYPE_HANDSHAKE = 0x01
    private const val TYPE_ACK = 0x02
    private const val TYPE_INVITE = 0x03
    private const val TYPE_ACCEPT = 0x04
    private const val TYPE_PING = 0x05
    private const val TYPE_PONG = 0x06
    private const val TYPE_BYE = 0x07

    private val charset = Charset.forName("UTF-8")

    /** Encodes a message into the wire byte format. */
    fun encode(msg: NearbyMessage): ByteArray {
        val (type, payload) = when (msg) {
            is NearbyMessage.Handshake ->
                TYPE_HANDSHAKE to "profile=${msg.profile.name}|addr=${msg.profile.address}|type=${msg.profile.type.name}"
            is NearbyMessage.HandshakeAck ->
                TYPE_ACK to "ok=${msg.accepted}|reason=${msg.reason}"
            is NearbyMessage.BattleInvite ->
                TYPE_INVITE to "arena=${msg.arenaId}|from=${msg.from}"
            is NearbyMessage.BattleAccept ->
                TYPE_ACCEPT to "arena=${msg.arenaId}"
            is NearbyMessage.Ping ->
                TYPE_PING to "t=${msg.timeMillis}"
            is NearbyMessage.Pong ->
                TYPE_PONG to "t=${msg.timeMillis}"
            NearbyMessage.Bye -> TYPE_BYE to ""
        }
        val bytes = payload.toByteArray(charset)
        return byteArrayOf(type.toInt().toByte()) + bytes
    }

    /** Decodes a wire byte array back into a message, or null if the format is unrecognized. */
    fun decode(data: ByteArray): NearbyMessage? {
        if (data.isEmpty()) return null
        val type = data[0].toInt() and 0xFF
        val payload = String(data.copyOfRange(1, data.size), charset)
        val map = parsePayload(payload)
        return when (type) {
            TYPE_HANDSHAKE -> {
                val typeName = map["type"] ?: "UNKNOWN"
                val profileType = DragonProfile.ProfileType.values().firstOrNull { it.name == typeName }
                    ?: DragonProfile.ProfileType.UNKNOWN
                NearbyMessage.Handshake(
                    DragonProfile(map["profile"] ?: "", map["addr"] ?: "", profileType,
                        map["rssi"]?.toIntOrNull() ?: 0))
            }
            TYPE_ACK -> NearbyMessage.HandshakeAck(map["ok"] == "true", map["reason"] ?: "")
            TYPE_INVITE -> NearbyMessage.BattleInvite(map["arena"] ?: "", map["from"] ?: "")
            TYPE_ACCEPT -> NearbyMessage.BattleAccept(map["arena"] ?: "")
            TYPE_PING -> NearbyMessage.Ping(map["t"]?.toLongOrNull() ?: 0)
            TYPE_PONG -> NearbyMessage.Pong(map["t"]?.toLongOrNull() ?: 0)
            TYPE_BYE -> NearbyMessage.Bye
            else -> null
        }
    }

    private fun parsePayload(s: String): Map<String, String> {
        return s.split('|').filter { it.contains('=') }.associate {
            val kv = it.split('=', limit = 2)
            kv[0] to kv.getOrElse(1) { "" }
        }.toMap()
    }
}
