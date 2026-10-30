package com.ramim.homedragon

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * The dragon's own family. She decides. The nest covers about 60% of her length.
 * Natural bird-like cycle: build nest, lay eggs, fly away, return to brood, hatch, feed chick.
 * Nest is anchored to a specific icon. If that icon moves, the nest is lost and she builds again.
 * Chick is independent and grows gradually by eating.
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
    var chickGrowth = 0.42f   // starts small, grows toward ~0.75 by eating
        private set

    // Anchored position (set when nest is built)
    var anchorX = 0f
        private set
    var anchorY = 0f
        private set
    var hasAnchor = false
        private set
    var anchorPage = -1
        private set

    // Natural build progress (twigs appear one by one)
    private var twigsPlaced = 0
    private val maxTwigs = 28

    private val twig = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val egg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val eggLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.parseColor("#C9794A") }
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val box = RectF()
    private val path = Path()

    fun hold(): Boolean = phase == Phase.BUILD || phase == Phase.LAY || phase == Phase.BROOD || phase == Phase.CHICK

    fun readyToFly(): Boolean = phase == Phase.SKY && !skyUsed

    fun usedSkyFlight() { skyUsed = true }

    /** Mother should bring food to chick sometimes after hatch. */
    fun wantsFoodForChick(): Boolean = showChick && phase == Phase.CHICK && Random.nextFloat() < 0.35f

    /** Call when mother successfully brings a bug to the chick. */
    fun feedChick() {
        if (showChick && chickGrowth < 0.78f) {
            chickGrowth = min(0.78f, chickGrowth + 0.04f)
        }
    }

    fun tick(dt: Float, canBegin: Boolean) {
        when (phase) {
            Phase.NONE -> {
                if (!canBegin) return
                wait -= dt
                if (wait <= 0f) begin()
            }
            Phase.BUILD -> {
                t += dt
                // Twigs appear gradually like a bird placing them
                val targetTwigs = (t / 11f * maxTwigs).toInt().coerceIn(0, maxTwigs)
                if (targetTwigs > twigsPlaced) twigsPlaced = targetTwigs
                built = min(1f, twigsPlaced / maxTwigs.toFloat())
                if (t >= 11f) { phase = Phase.LAY; t = 0f }
            }
            Phase.LAY -> {
                t += dt
                built = 1f
                // Egg appears after a short settling period
                lay = if (t < 3.5f) 0f else min(1f, (t - 3.5f) / 5f)
                if (t >= 12f) { phase = Phase.BROOD; t = 0f; lay = 1f }
            }
            Phase.BROOD -> {
                t += dt
                if (t >= 9f) { phase = Phase.CHICK; t = 0f; shell = 1f; showChick = true; chickGrowth = 0.42f }
            }
            Phase.CHICK -> {
                t += dt
                shell = max(0f, 1f - t / 14f)
                showChick = true
                if (t >= 16f) { phase = Phase.SKY; t = 0f; skyUsed = false; shell = 0f }
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

    fun setAnchor(x: Float, y: Float, page: Int) {
        anchorX = x
        anchorY = y
        anchorPage = page
        hasAnchor = true
    }

    fun loseNest() {
        if (phase == Phase.NONE) return
        forget()
        hasAnchor = false
        anchorPage = -1
    }

    fun placeChick(mx: Float, my: Float, ds: Float, time: Float, width: Float, height: Float) {
        if (!showChick) return
        chick.ds = ds * chickGrowth
        chick.eye = 1f
        chick.time = time
        chick.mouth = 0f
        chick.rest = 0f
        chick.lower = 0f
        chick.crouch = 0f
        val baseX = if (hasAnchor) anchorX else mx
        val baseY = if (hasAnchor) anchorY else my
        when (phase) {
            Phase.CHICK -> {
                val play = sin(time * 0.55f)
                val hop = max(0f, sin(time * 2.1f))
                chick.x = baseX + ds * (22f + play * 36f)
                chick.y = baseY - hop * hop * ds * 12f
                chick.face = if (play > 0f) 1f else -1f
                chick.sp = 0f
                chick.wingPh = time * 1.3f
            }
            Phase.SKY -> {
                chick.x = width * (0.48f + 0.18f * sin(time * 0.48f + 1.8f))
                chick.y = height * (0.36f + 0.06f * sin(time * 0.31f + 0.9f))
                chick.face = if (cos(time * 0.48f + 1.8f) >= 0f) 1f else -1f
                chick.sp = 1f
                chick.wingPh = time * 8.5f
            }
            Phase.AWAY -> {
                val u = min(1f, t / 5.5f)
                chick.x = baseX + u * (width - baseX + 80f)
                chick.y = baseY - u * height * 0.38f
                chick.face = 1f
                chick.sp = if (u > 0.1f) 1f else 0f
                chick.wingPh = time * 9.5f
            }
            Phase.VISIT -> {
                val u = t / 8f
                if (u < 0.25f) {
                    val k = u / 0.25f
                    chick.x = width + 20f - k * width * 0.38f
                    chick.y = baseY - (1f - k) * height * 0.28f
                    chick.sp = 1f
                    chick.face = -1f
                } else if (u < 0.72f) {
                    val play = sin(time * 1.05f)
                    chick.x = baseX + ds * (34f + play * 28f)
                    chick.y = baseY - max(0f, sin(time * 2.2f)) * ds * 10f
                    chick.sp = 0f
                    chick.face = if (play > 0f) 1f else -1f
                } else {
                    val k = (u - 0.72f) / 0.28f
                    chick.x = baseX + k * width * 0.4f
                    chick.y = baseY - k * height * 0.36f
                    chick.sp = 1f
                    chick.face = 1f
                }
                chick.wingPh = time * 8.5f
            }
            else -> showChick = false
        }
    }

    fun drawUnder(c: Canvas, x: Float, y: Float, ds: Float) {
        if (!nestVisible()) return
        val nx = if (hasAnchor) anchorX else x
        val ny = if (hasAnchor) anchorY else y
        drawNest(c, nx, ny, ds, front = false)
        if (lay > 0.04f && (phase == Phase.LAY || phase == Phase.BROOD)) drawEgg(c, nx, ny, ds)
        if (shell > 0.02f) drawShells(c, nx, ny, ds)
    }

    fun drawOver(c: Canvas, x: Float, y: Float, ds: Float) {
        if (!nestVisible()) return
        val nx = if (hasAnchor) anchorX else x
        val ny = if (hasAnchor) anchorY else y
        drawNest(c, nx, ny, ds, front = true)
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
        hasAnchor = false
        twigsPlaced = 0
        chickGrowth = 0.42f
    }

    private fun afterLeave() {
        showChick = false
        if (Random.nextFloat() < 0.55f) {
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
        wait = 18f + Random.nextFloat() * 16f
        showChick = false
        lay = 0f
        shell = 0f
        built = 0f
        eggs = 0
        hasAnchor = false
        anchorPage = -1
        twigsPlaced = 0
        chickGrowth = 0.42f
    }

    private fun drawNest(c: Canvas, x: Float, y: Float, ds: Float, front: Boolean) {
        val rx = 72f * ds * built
        val ry = 32f * ds * built
        val cy = y - 18f * ds
        if (!front) {
            // Soft ground shadow
            shadow.color = Color.argb(65, 0, 0, 0)
            box.set(x - rx * 1.08f, cy + ry * 0.12f, x + rx * 1.08f, cy + ry * 0.92f)
            c.drawOval(box, shadow)
            // Inner soft straw fill
            fill.color = Color.parseColor("#E9D6B6")
            box.set(x - rx * 0.68f, cy - ry * 0.28f, x + rx * 0.68f, cy + ry * 0.38f)
            c.drawOval(box, fill)
        }
        // Layered twigs that appear progressively (natural placement)
        val n = max(2, twigsPlaced)
        for (layer in 0..1) {
            val scale = if (layer == 0) 1f else 0.84f
            val offset = if (layer == 0) 0f else 3.5f * ds
            twig.strokeWidth = (if (layer == 0) 2.5f else 1.7f) * ds
            for (i in 0 until n) {
                val a = i / max(1f, n.toFloat()) * 6.28318f + 0.22f + layer * 0.28f
                val near = sin(a) > -0.12f
                if (front != near) continue
                val shade = when (i % 5) {
                    0 -> Color.parseColor("#7A4E2A")
                    1 -> Color.parseColor("#C49560")
                    2 -> Color.parseColor("#5C3A22")
                    3 -> Color.parseColor("#A67C4E")
                    else -> Color.parseColor("#8B6A3E")
                }
                twig.color = shade
                val x0 = x + cos(a) * rx * scale
                val y0 = cy + sin(a) * ry * scale + offset
                val x1 = x + cos(a + 0.48f) * rx * 0.91f * scale
                val y1 = cy + sin(a + 0.48f) * ry * scale + offset
                c.drawLine(x0, y0, x1, y1, twig)
            }
        }
    }

    private fun drawEgg(c: Canvas, x: Float, y: Float, ds: Float) {
        val s = ds * (0.22f + lay * 0.20f)
        val ex = x + 8f * ds
        val ey = y - 5f * ds
        egg.color = Color.parseColor("#F7E3C9")
        box.set(ex - 14f * s, ey - 19f * s, ex + 14f * s, ey + 17f * s)
        c.drawOval(box, egg)
        eggLine.strokeWidth = 1.15f * ds
        if (phase == Phase.BROOD && t > 4f) {
            c.drawLine(ex - 2.5f * s, ey - 11f * s, ex + 0.5f * s, ey - 1f * s, eggLine)
            c.drawLine(ex + 0.5f * s, ey - 1f * s, ex + 6f * s, ey - 6.5f * s, eggLine)
        }
    }

    private fun drawShells(c: Canvas, x: Float, y: Float, ds: Float) {
        egg.color = Color.argb((shell * 230f).toInt().coerceIn(0, 255), 247, 227, 201)
        val sx = x - 15f * ds
        val sy = y - 1f * ds
        c.drawOval(sx, sy - 4.5f * ds, sx + 13f * ds, sy + 4.5f * ds, egg)
        c.drawOval(sx + 17f * ds, sy - 2.5f * ds, sx + 29f * ds, sy + 5.5f * ds, egg)
    }
}
