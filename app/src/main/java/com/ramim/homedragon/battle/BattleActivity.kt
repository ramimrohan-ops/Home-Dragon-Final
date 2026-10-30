package com.ramim.homedragon.battle

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.ramim.homedragon.dragon.DragonStats

/**
 * Main battle screen. Taps "Attack" to have the player strike, then the opponent ripostes,
 * until one side reaches 0 HP.
 */
class BattleActivity : Activity() {

    private lateinit var engine: BattleEngine
    private lateinit var arena: BattleView
    private lateinit var log: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        engine = BattleEngine()
        engine.start(
            DragonStats(hp = 100, maxHp = 100, attack = 15, defense = 5),
            DragonStats(hp = 90, maxHp = 90, attack = 12, defense = 3)
        )

        arena = BattleView(this)
        arena.showBattle(engine.current())

        val attackBtn = Button(this)
        attackBtn.text = "Attack"
        attackBtn.setOnClickListener {
            engine.playerAttack()
            val cur = engine.current()
            if (cur.outcome == BattleOutcome.IN_PROGRESS) {
                engine.opponentAttack()
            }
            arena.showBattle(engine.current())
            log.text = cur.log.lastOrNull() ?: ""
        }

        log = TextView(this)
        log.text = engine.current().log.lastOrNull() ?: ""
        log.setTextSize(18f)
        log.setPadding(16, 16, 16, 16)

        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
        layout.addView(arena, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        layout.addView(attackBtn)
        layout.addView(log)
        setContentView(layout)
    }
}
