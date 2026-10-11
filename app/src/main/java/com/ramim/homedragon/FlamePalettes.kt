package com.ramim.homedragon

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout

/**
 * Ready-made flame palettes for the settings screen: Blue, Fire (the default), Green, Purple, Ice, Gold and Rainbow, four to a row.
 * The chosen one has a teal outline. onChange gets the palette's colours (hot core first, cooled tip last) when one is tapped.
 */
class FlamePalettes(private val ctx: Context, private val onChange: (IntArray) -> Unit) {

    private companion object {
        val MUTED = Color.parseColor("#8A9BBD")
        val STROKE = Color.parseColor("#223152")
        val PANEL = Color.parseColor("#0E162E")
        val TEAL = Color.parseColor("#2DD4BF")

        val PRESETS: List<Pair<String, IntArray>> = listOf(
            "Blue" to FlameColors.BLUE,
            "Fire" to FlameColors.FIRE,
            "Green" to intArrayOf(0xE6FFE0.opaque(), 0x8BF28B.opaque(), 0x2EDB6B.opaque(), 0x12A05A.opaque(), 0x0A5A3C.opaque()),
            "Purple" to intArrayOf(0xF3E3FF.opaque(), 0xC79BFF.opaque(), 0x9B5CFF.opaque(), 0x6A2FE0.opaque(), 0x35157A.opaque()),
            "Ice" to intArrayOf(0xFFFFFF.opaque(), 0xCFF3FF.opaque(), 0x8EDBFF.opaque(), 0x5BA8FF.opaque(), 0x3566D8.opaque()),
            "Gold" to intArrayOf(0xFFFBE0.opaque(), 0xFFE27A.opaque(), 0xFFC21A.opaque(), 0xE08A00.opaque(), 0x8A4A00.opaque()),
            "Rainbow" to intArrayOf(0xFF5A5A.opaque(), 0xFFA94D.opaque(), 0xFFE066.opaque(), 0x69DB7C.opaque(), 0x4DABF7.opaque(), 0xB197FC.opaque())
        )
    }

    private val dm = ctx.resources.displayMetrics.density
    private fun dp(v: Int) = (v * dm).toInt()

    private var cols: IntArray = Prefs.flameColors(ctx)

    val view = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    private val chips = ArrayList<View>()

    init {
        var row: LinearLayout? = null
        for ((i, pr) in PRESETS.withIndex()) {
            if (i % 4 == 0) {
                row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
                view.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)).apply { topMargin = dp(if (i == 0) 12 else 8) })
            }
            val chip = Chip(ctx, pr.first, pr.second)
            chip.setOnClickListener { pick(pr.second) }
            chips.add(chip)
            row!!.addView(chip, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { if (i % 4 != 0) leftMargin = dp(8) })
        }
        // keep the last row's chips the same width as the others
        val last = PRESETS.size % 4
        if (last != 0) for (k in last until 4) row!!.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f).apply { leftMargin = dp(8) })
    }

    private fun pick(c: IntArray) {
        cols = c.copyOf()
        for (ch in chips) ch.invalidate()
        onChange(cols)
    }

    /** Back to the default flame (Fire). */
    fun reset() = pick(FlameColors.DEFAULT)

    private inner class Chip(c: Context, private val name: String, private val colors: IntArray) : View(c) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()
        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            rect.set(0f, 0f, w, h)
            p.style = Paint.Style.FILL; p.color = PANEL; c.drawRoundRect(rect, dp(12).toFloat(), dp(12).toFloat(), p)
            val chosen = FlameColors.same(cols, colors)
            p.style = Paint.Style.STROKE; p.strokeWidth = dp(if (chosen) 2 else 1).toFloat(); p.color = if (chosen) TEAL else STROKE
            c.drawRoundRect(rect, dp(12).toFloat(), dp(12).toFloat(), p)
            val pos = FloatArray(colors.size) { FlameColors.position(colors.size, it) / 100f }
            p.style = Paint.Style.FILL
            p.shader = LinearGradient(dp(8).toFloat(), 0f, w - dp(8), 0f, colors, pos, Shader.TileMode.CLAMP)
            rect.set(dp(8).toFloat(), dp(9).toFloat(), w - dp(8), dp(19).toFloat())
            c.drawRoundRect(rect, dp(5).toFloat(), dp(5).toFloat(), p)
            p.shader = null
            p.color = MUTED; p.textSize = dp(11).toFloat(); p.textAlign = Paint.Align.CENTER; p.typeface = Typeface.DEFAULT_BOLD
            c.drawText(name, w / 2f, h - dp(10).toFloat(), p)
        }
    }
}

private fun Int.opaque(): Int = this or (0xFF shl 24)
