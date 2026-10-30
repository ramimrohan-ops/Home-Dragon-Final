package com.ramim.homedragon

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * The dragon's own family. She decides. The nest covers about 60% of her length.
 * The child plays and flies on its own path, then leaves and is forgotten.
 */
class NestLife {
    enum class Phase { NONE, BUILD, LAY, BROOD, CHICK, SKY, AWAY, VISIT }

    var phase = Phase.NONE
        private set
    private var t = 0f
    private var wait = 18f
    private var eggs = 0
    private var shell = 0f
    private var built = 0f
    private var lay = 0f
    private var skyUsed = false
    val chick = DragonModel.State()
    var showChick = false
        private set

    private val twig = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val egg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val eggLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.parseColor("#C9794A") }
    private val box = RectF()

    fun hold(): Boolean = phase == Phase.BUILD || phase == Phase.LAY || phase == Phase.BROOD || phase == Phase.CHICK

    fun readyToFly(): Boolean = phase == Phase.SKY && !skyUsed

    fun usedSkyFlight() { skyUsed = true }

    fun tick(dt: Float, canBegin: Boolean) {
        when (phase) {
            Phase.NONE -> {
                if (!canBegin) return
                wait -= dt
                if (wait <= 0f) begin()
            }
            Phase.BUILD -> {
                t += dt
                built = min(1f, t / 8f)
                if (t >= 11f) { phase = Phase.LAY; t = 0f }
            }
            Phase.LAY -> {
                t += dt
                built = 1f
                lay = if (t < 3.5f) 0f else min(1f, (t - 3.5f) / 5f)
                if (t >= 12f) { phase = Phase.BROOD; t = 0f; lay = 1f }
            }
            Phase.BROOD -> {
                t += dt
                if (t >= 8f) { phase = Phase.CHICK; t = 0f; shell = 1f; showChick = true }
            }
            Phase.CHICK -> {
                t += dt
                shell = max(0f, 1f - t / 12f)
                showChick = true
                if (t >= 14f) { phase = Phase.SKY; t = 0f; skyUsed = false; shell = 0f }
            }
            Phase.SKY -> {
                t += dt
                showChick = true
                if (t >= 12f) { eggs = max(0, eggs - 1); phase = Phase.AWAY; t = 0f }
            }
            Phase.AWAY -> {
                t += dt
                showChick = t < 5.5f
                if (t >= 7f) afterLeave()
            }
            Phase.VISIT -> {
                t += dt
                showChick = true
                if (t >= 8f) afterVisit()
            }
        }
    }

    fun placeChick(mx: Float, my: Float, ds: Float, time: Float, width: Float, height: Float) {
        if (!showChick) return
        chick.ds = ds * 0.46f
        chick.eye = 1f
        chick.time = time
        chick.mouth = 0f
        chick.rest = 0f
        chick.lower = 0f
        chick.crouch = 0f
        when (phase) {
            Phase.CHICK -> {
                val play = sin(time * 0.6f)
                chick.x = mx + ds * (36f + play * 55f)
                val hop = max(0f, sin(time * 2.2f))
                chick.y = my - hop * hop * ds * 16f
                chick.face = if (play > 0f) 1f else -1f
                chick.sp = 0f
                chick.wingPh = time * 1.4f
            }
            Phase.SKY -> {
                chick.x = width * (0.5f + 0.22f * sin(time * 0.52f + 2.2f))
                chick.y = height * (0.38f + 0.07f * sin(time * 0.34f + 1.1f))
                chick.face = if (cos(time * 0.52f + 2.2f) >= 0f) 1f else -1f
                chick.sp = 1f
                chick.wingPh = time * 9f
            }
            Phase.AWAY -> {
                val u = min(1f, t / 5.5f)
                chick.x = mx + u * (width - mx + 90f)
                chick.y = my - u * height * 0.42f
                chick.face = 1f
                chick.sp = if (u > 0.12f) 1f else 0f
                chick.wingPh = time * 10f
            }
            Phase.VISIT -> {
                val u = t / 8f
                if (u < 0.28f) {
                    val k = u / 0.28f
                    chick.x = width + 30f - k * width * 0.42f
                    chick.y = my - (1f - k) * height * 0.32f
                    chick.sp = 1f
                    chick.face = -1f
                } else if (u < 0.74f) {
                    val play = sin(time * 1.1f)
                    chick.x = mx + ds * (48f + play * 36f)
                    chick.y = my - max(0f, sin(time * 2.3f)) * ds * 14f
                    chick.sp = 0f
                    chick.face = if (play > 0f) 1f else -1f
                } else {
                    val k = (u - 0.74f) / 0.26f
                    chick.x = mx + k * width * 0.45f
                    chick.y = my - k * height * 0.4f
                    chick.sp = 1f
                    chick.face = 1f
                }
                chick.wingPh = time * 9f
            }
            else -> showChick = false
        }
    }

    fun drawUnder(c: Canvas, x: Float, y: Float, ds: Float) {
        if (!nestVisible()) return
        drawNest(c, x, y, ds, front = false)
        if (lay > 0.04f && (phase == Phase.LAY || phase == Phase.BROOD)) drawEgg(c, x, y, ds)
        if (shell > 0.02f) drawShells(c, x, y, ds)
    }

    fun drawOver(c: Canvas, x: Float, y: Float, ds: Float) {
        if (!nestVisible()) return
        drawNest(c, x, y, ds, front = true)
    }

    private fun nestVisible() = built > 0.02f && (phase == Phase.BUILD || phase == Phase.LAY || phase == Phase.BROOD || phase == Phase.CHICK || phase == Phase.SKY)

    private fun begin() {
        phase = Phase.BUILD
        t = 0f
        eggs = if (Random.nextFloat() < 0.45f) 2 else 1
        built = 0f
        lay = 0f
        shell = 0f
        showChick = false
        skyUsed = false
    }

    private fun afterLeave() {
        showChick = false
        if (Random.nextFloat() < 0.6f) {
            phase = Phase.VISIT
            t = 0f
            showChick = true
        } else if (eggs > 0) {
            phase = Phase.LAY
            t = 0f
            lay = 0f
        } else forget()
    }

    private fun afterVisit() {
        showChick = false
        if (eggs > 0) {
            phase = Phase.LAY
            t = 0f
            lay = 0f
        } else forget()
    }

    private fun forget() {
        phase = Phase.NONE
        wait = 22f + Random.nextFloat() * 20f
        showChick = false
        lay = 0f
        shell = 0f
        built = 0f
        eggs = 0
    }

    private fun drawNest(c: Canvas, x: Float, y: Float, ds: Float, front: Boolean) {
        val rx = 66f * ds * built
        val ry = 28f * ds * built
        val cy = y - 16f * ds
        if (!front) {
            fill.color = Color.argb(90, 0, 0, 0)
            box.set(x - rx, cy + ry * 0.2f, x + rx, cy + ry * 0.85f)
            c.drawOval(box, fill)
            fill.color = Color.parseColor("#E7D3B0")
            box.set(x - rx * 0.72f, cy - ry * 0.35f, x + rx * 0.72f, cy + ry * 0.45f)
            c.drawOval(box, fill)
        }
        val n = (18 * built).toInt().coerceAtLeast(1)
        twig.strokeWidth = 2.2f * ds
        for (i in 0 until n) {
            val a = i / n.toFloat() * 6.28318f + 0.3f
            val near = sin(a) > 0.05f
            if (front != near) continue
            twig.color = if (i % 3 == 0) Color.parseColor("#8D5A32") else if (i % 3 == 1) Color.parseColor("#D2A56C") else Color.parseColor("#4E3424")
            val x0 = x + cos(a) * rx
            val y0 = cy + sin(a) * ry
            val x1 = x + cos(a + 0.55f) * rx * 0.92f
            val y1 = cy + sin(a + 0.55f) * ry
            c.drawLine(x0, y0, x1, y1, twig)
        }
    }

    private fun drawEgg(c: Canvas, x: Float, y: Float, ds: Float) {
        val s = ds * (0.22f + lay * 0.2f)
        val ex = x + 12f * ds
        val ey = y - 8f * ds
        egg.color = Color.parseColor("#F4E0C4")
        box.set(ex - 16f * s, ey - 22f * s, ex + 16f * s, ey + 20f * s)
        c.drawOval(box, egg)
        eggLine.strokeWidth = 1.3f * ds
        if (phase == Phase.BROOD && t > 5f) {
            c.drawLine(ex - 4f * s, ey - 14f * s, ex + 2f * s, ey, eggLine)
            c.drawLine(ex + 2f * s, ey, ex + 8f * s, ey - 8f * s, eggLine)
        }
    }

    private fun drawShells(c: Canvas, x: Float, y: Float, ds: Float) {
        egg.color = Color.argb((shell * 255f).toInt().coerceIn(0, 255), 244, 224, 196)
        val sx = x - 18f * ds
        val sy = y - 4f * ds
        c.drawOval(sx, sy - 6f * ds, sx + 16f * ds, sy + 6f * ds, egg)
        c.drawOval(sx + 20f * ds, sy - 4f * ds, sx + 34f * ds, sy + 7f * ds, egg)
    }
}
