package com.ramim.homedragon.battle

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * Draws the battle arena: two HP bars (player teal on the left, opponent orange on the right)
 * and a status line. Updated whenever [state] changes.
 */
class BattleView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var state: BattleState? = null
        set(value) { field = value; invalidate() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // background
        paint.color = Color.parseColor("#121212")
        canvas.drawRect(0f, 0f, w, h, paint)

        val currentState = state ?: return

        // player HP bar (left, teal)
        paint.color = Color.parseColor("#4AA896")
        val playerBarW = (w * 0.42f) * currentState.player.hpPercent()
        canvas.drawRect(24f, 52f, 24f + playerBarW, 76f, paint)

        // opponent HP bar (right, orange)
        paint.color = Color.parseColor("#E0713A")
        val oppBarW = (w * 0.42f) * currentState.opponent.hpPercent()
        canvas.drawRect(w - 24f - oppBarW, 52f, w - 24f, 76f, paint)

        // bar outlines
        paint.color = Color.WHITE
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        canvas.drawRect(24f, 52f, 24f + w * 0.42f, 76f, paint)
        canvas.drawRect(w - 24f - w * 0.42f, 52f, w - 24f, 76f, paint)
        paint.style = Paint.Style.FILL

        // labels
        paint.color = Color.WHITE
        paint.textSize = 22f
        canvas.drawText("Player  ${currentState.player.hp}/${currentState.player.maxHp}", 24f, 44f, paint)
        canvas.drawText("Opponent  ${currentState.opponent.hp}/${currentState.opponent.maxHp}", w - 24f - 260f, 44f, paint)

        // outcome banner
        if (currentState.outcome != BattleOutcome.IN_PROGRESS) {
            paint.color = Color.parseColor("#FFD54F")
            paint.textSize = 34f
            val msg = when (currentState.outcome) {
                BattleOutcome.PLAYER_WIN -> "PLAYER WINS!"
                BattleOutcome.OPPONENT_WIN -> "OPPONENT WINS!"
                BattleOutcome.DRAW -> "DRAW"
                BattleOutcome.IN_PROGRESS -> ""
            }
            canvas.drawText(msg, w / 2f - paint.measureText(msg) / 2f, h / 2f, paint)
        }
    }

    fun setState(s: BattleState) { state = s }
}
