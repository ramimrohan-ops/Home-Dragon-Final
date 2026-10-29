package com.ramim.homedragon.battle

import com.ramim.homedragon.dragon.DragonStats
import kotlin.math.maxOf

/** Result of a completed battle. */
enum class BattleOutcome { PLAYER_WIN, OPPONENT_WIN, DRAW, IN_PROGRESS }

/** Snapshot of the battle at a moment in time. */
data class BattleState(
    val player: DragonStats,
    val opponent: DragonStats,
    val turn: Int = 0,
    val outcome: BattleOutcome = BattleOutcome.IN_PROGRESS,
    val log: List<String> = emptyList()
) {
    fun withLog(entry: String): BattleState = copy(log = log + entry)
}

/**
 * Battle state machine: players take turns attacking; damage = attacker.attack - defender.defense
 * (minimum 1). The battle ends when one side reaches 0 HP.
 */
class BattleEngine {
    private var state: BattleState = BattleState(
        DragonStats(0, 0, 0, 0), DragonStats(0, 0, 0, 0)
    )

    /** Start a new battle between the player and opponent dragons. */
    fun start(player: DragonStats, opponent: DragonStats): BattleState {
        state = BattleState(player, opponent, turn = 0, outcome = BattleOutcome.IN_PROGRESS, log = emptyList())
        return state.withLog("Battle started: ${player.hp} HP vs ${opponent.hp} HP")
    }

    /** Current snapshot (read-only). */
    fun current(): BattleState = state

    /** Player attacks the opponent. Returns the new state. */
    fun playerAttack(): BattleState {
        if (state.outcome != BattleOutcome.IN_PROGRESS) return state
        val dmg = maxOf(1, state.player.attack - state.opponent.defense)
        val opp = state.opponent.takeDamage(dmg)
        val turn = state.turn + 1
        val log = state.log + ("Player hits for $dmg (opponent ${opp.hp}/${opp.maxHp} HP)")
        state = if (!opp.isAlive()) {
            state.copy(opponent = opp, turn = turn, outcome = BattleOutcome.PLAYER_WIN, log = log + ("Opponent defeated — player wins!"))
        } else {
            state.copy(opponent = opp, turn = turn, log = log)
        }
        return state
    }

    /** Opponent attacks the player. Returns the new state. */
    fun opponentAttack(): BattleState {
        if (state.outcome != BattleOutcome.IN_PROGRESS) return state
        val dmg = maxOf(1, state.opponent.attack - state.player.defense)
        val ply = state.player.takeDamage(dmg)
        val turn = state.turn + 1
        val log = state.log + ("Opponent hits for $dmg (player ${ply.hp}/${ply.maxHp} HP)")
        state = if (!ply.isAlive()) {
            state.copy(player = ply, turn = turn, outcome = BattleOutcome.OPPONENT_WIN, log = log + ("Player defeated — opponent wins!"))
        } else {
            state.copy(player = ply, turn = turn, log = log)
        }
        return state
    }
}
