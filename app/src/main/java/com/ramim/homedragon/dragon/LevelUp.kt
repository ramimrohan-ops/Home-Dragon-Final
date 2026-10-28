package com.ramim.homedragon.dragon

/** Progression logic: XP thresholds and the stats a dragon gains at each level. */
class LevelUp {
    private val baseHp = 50
    private val baseAttack = 10
    private val baseDefense = 2

    /** XP required to reach the given level (level 1 costs 0). */
    fun xpForLevel(level: Int): Int = if (level <= 1) 0 else 100 * (level - 1)

    /** Stats for a dragon at the given level. */
    fun statsForLevel(level: Int): DragonStats {
        val lvl = maxOf(1, level)
        val hp = baseHp + (lvl - 1) * 20
        return DragonStats(
            hp = hp,
            maxHp = hp,
            attack = baseAttack + (lvl - 1) * 3,
            defense = baseDefense + (lvl - 1)
        )
    }

    /** The level a given XP total corresponds to. */
    fun levelForXp(xp: Int): Int {
        var lvl = 1
        while (xpForLevel(lvl + 1) <= xp) lvl++
        return lvl
    }
}
