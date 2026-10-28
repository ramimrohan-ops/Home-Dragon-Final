package com.ramim.homedragon.battle

/** A single finished battle, kept for the history list. */
data class BattleRecord(
    val outcome: BattleOutcome,
    val timestamp: Long = System.currentTimeMillis(),
    val playerHp: Int = 0,
    val opponentHp: Int = 0
)

/** In-memory battle history. Persistence (Room/Preferences) can be layered on top later. */
class BattleHistory {
    private val records = mutableListOf<BattleRecord>()

    fun add(outcome: BattleOutcome, playerHp: Int, opponentHp: Int) {
        records.add(BattleRecord(outcome, playerHp = playerHp, opponentHp = opponentHp))
    }

    val all: List<BattleRecord>
        get() = records.toList()

    val wins: Int
        get() = records.count { it.outcome == BattleOutcome.PLAYER_WIN }

    val losses: Int
        get() = records.count { it.outcome == BattleOutcome.OPPONENT_WIN }

    fun clear() { records.clear() }
}
