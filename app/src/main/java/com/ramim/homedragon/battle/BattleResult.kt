package com.ramim.homedragon.battle

import android.app.Activity
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView

/**
 * End-of-battle result screen. Shows the outcome and lets the player return to the arena.
 */
class BattleResult : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val outcome = intent.getStringExtra("outcome") ?: "DRAW"
        val text = TextView(this)
        text.text = "Battle over — $outcome"
        text.textSize = 26f
        text.setPadding(24, 24, 24, 24)
        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
        layout.addView(text)
        setContentView(layout)
    }
}
