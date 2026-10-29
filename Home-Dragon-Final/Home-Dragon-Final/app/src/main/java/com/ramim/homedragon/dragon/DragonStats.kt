package com.ramim.homedragon.dragon

import kotlin.math.maxOf
import kotlin.math.minOf

/** Combat stats for a dragon: HP, attack power and damage reduction. */
data class DragonStats(
    val hp: Int,
    val maxHp: Int,
    val attack: Int,
    val defense: Int
) {
    fun isAlive(): Boolean = hp > 0
    fun hpPercent(): Float = if (maxHp <= 0) 0f else hp.coerceAtLeast(0) / maxHp.toFloat()

    /** Apply incoming damage (reduced by defense) and return a new stats instance. */
    fun takeDamage(rawDamage: Int): DragonStats {
        val damage = maxOf(1, rawDamage - defense)
        return copy(hp = maxOf(0, hp - damage))
    }

    fun heal(amount: Int): DragonStats = copy(hp = minOf(maxHp, hp + amount))
}
