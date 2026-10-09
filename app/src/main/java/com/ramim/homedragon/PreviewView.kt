package com.ramim.homedragon

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.hardware.display.DisplayManager
import android.view.Choreographer
import android.view.View
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Preview box for the settings screen.
 *
 * SIZE: the sitting dragon at the chosen size inside a box that is just bigger than the dragon at 150%,
 *       with a bar that shows the icon width at 100%.
 * QUALITY: the sitting dragon with its frame rate (30, or the Quality rate if lower) in the top-right corner.
 * FLYING: the dragon in the flying pose, flapping in place, with its frame rate (screen rate x Quality) in the top-right corner.
 * PARTICLES: the dragon's head breathing fire at a dummy icon, with the flame count of the chosen particle quality.
 * CHARGE: the whole sitting dragon showing the charge-up before the breath (glow wave along the spine, orb, sparks and rings in the mouth, flash at
 *         release) and the breath into empty space; the value is tenths of a second. Still while the slider is dragged, loops after it is released.
 *
 * It only animates while [start] has been called (the slider is being dragged); otherwise it holds the last frame.
 */
class PreviewView(context: Context, private val kind: Int) : View(context) {

    companion object {
        const val SIZE = 0
        const val QUALITY = 1
        const val PARTICLES = 2
        const val FLYING = 3
        const val SEETHROUGH = 4
        const val CHARGE = 5
        private const val FLY_W = 256f               // flying pose: width in model units
        private const val FLY_H = 250f               // flying pose: height in model units
        private const val FLY_CX = -31.5f            // flying pose: horizontal centre
        private const val FLY_CY = -66f              // flying pose: vertical centre
        private const val CYCLE = 3.6f                 // seconds per fire cycle
        private const val MAXF = 260
        private const val MAXS = 120
        private const val MODEL_W = 200.5f             // dragon width in model units (tail tip to snout)
        private const val MODEL_H = 201f               // dragon height in model units (ground to wing tip)
        private const val MODEL_CX = -32.2f            // horizontal centre of the dragon in model units
    }

    private val dm = context.resources.displayMetrics.density
    private fun dp(v: Float) = v * dm

    private val model = DragonModel()
    private val st = DragonModel.State()        // sitting dragon
    private val headSt = DragonModel.State()    // head only

    private var pct = 100
    private var running = false
    private var t = 1.2f
    private var cyc = 0.5f
    private var blink = 0f
    private var nextBlink = 2f
    private var mouth = 0f
    private var heat = 0f
    private var acc = 0f
    private var lastNs = 0L
    private var dueNs = 0L
    private var seed = 12345L
    private var wingPh = 0f
    private var loopsLeft = -1                 // PARTICLES (flame colours): fire loops still to play before pausing, -1 = no limit
    private val cbx = FloatArray(16); private val cby = FloatArray(16); private val ctx = FloatArray(16); private val cty = FloatArray(16); private val cfr = FloatArray(16)
    private val chB = FloatArray(16)
    private val orbP = FloatArray(2)

    // the best refresh rate this screen offers (the overlay asks for the same one)
    private val hz: Float = try {
        val d = (context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).getDisplay(0)
        val cur = d.mode
        val best = d.supportedModes.filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight }
            .maxOfOrNull { it.refreshRate } ?: d.refreshRate
        max(30f, best)
    } catch (_: Throwable) {
        60f
    }

    // flame particles
    private val fx = FloatArray(MAXF); private val fy = FloatArray(MAXF)
    private val fvx = FloatArray(MAXF); private val fvy = FloatArray(MAXF)
    private val fage = FloatArray(MAXF); private val flife = FloatArray(MAXF); private val fsz = FloatArray(MAXF)
    private val fph = FloatArray(MAXF)
    private var nf = 0
    // sparks
    private val sx = FloatArray(MAXS); private val sy = FloatArray(MAXS)
    private val svx = FloatArray(MAXS); private val svy = FloatArray(MAXS)
    private val sage = FloatArray(MAXS); private val slife = FloatArray(MAXS)
    private var ns = 0

    // geometry of the quality preview
    private val leftRect = RectF(); private val rightRect = RectF()
    private var mx = 0f; private var my = 0f          // mouth tip
    private var icx = 0f; private var icy = 0f; private var icSize = 0f
    private var geomReady = false
    private val tmp = FloatArray(2)

    private var flameCols: IntArray = FlameColors.DEFAULT.copyOf()
    private var sprites: Array<Bitmap> = Array(6) { makeSprite(ramp(it / 5f)) }

    /** Use the flame colours chosen in the app (the original blue flame when none were chosen). */
    fun reloadFlame() {
        val cols = Prefs.flameColors(context)
        if (FlameColors.same(cols, flameCols)) return
        flameCols = cols
        sprites = Array(6) { makeSprite(ramp(it / 5f)) }
        if (FlameColors.isDefault(cols)) model.setFlameGlow(Color.rgb(220, 245, 255), Color.rgb(70, 170, 255), Color.rgb(30, 80, 255))
        else model.setFlameGlow(ramp(0f), ramp(0.18f), ramp(0.5f))
        invalidate()
    }

    init { reloadFlame() }
    private val spritePaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply { blendMode = BlendMode.PLUS }
    private val dst = RectF()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val textFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER }
    private val textOutline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER; style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND
    }
    private val clip = Path()

    // ---------------------------------------------------------------- control

    private val callback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            if (lastNs != 0L && frameTimeNanos < dueNs - 300_000L) {
                Choreographer.getInstance().postFrameCallback(this)
                return
            }
            val dt = if (lastNs == 0L) 0f else min(0.1f, (frameTimeNanos - lastNs) / 1e9f)
            lastNs = frameTimeNanos
            if (dt > 0f) advance(dt)
            invalidate()
            val want = (1e9f / rate()).toLong()
            dueNs = if (dueNs == 0L || frameTimeNanos - dueNs > want) frameTimeNanos + want else dueNs + want
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    /** Frame rate this preview draws at: the real rate the dragon would use for this Quality. */
    private fun rate(): Float = when (kind) {
        QUALITY -> min(30f, max(5f, hz * pct / 100f))      // sitting still: 30 fps at most
        FLYING -> max(5f, hz * pct / 100f)
        CHARGE -> min(60f, hz)
        else -> hz
    }

    private val fireKind get() = kind == PARTICLES || kind == CHARGE
    private val chargeS get() = if (kind == CHARGE) pct / 10f else 0f          // charge-up seconds (CHARGE preview only)

    fun setValue(v: Int) {
        if (v == pct) return
        pct = v
        if (!running) { if (fireKind) warm(); invalidate() }
    }

    private var bodyT = 50
    private var wingT = 65

    /** See-through preview: Transparency (body) and Wing transparency, both 0..100. */
    fun setTransparency(body: Int, wing: Int) {
        bodyT = body; wingT = wing
        invalidate()
    }

    /** The slider is being touched. The charge-up preview holds a still picture, every other preview starts moving. */
    fun start() {
        if (kind == CHARGE) { halt(); return }
        beginAnim()
    }

    private fun beginAnim() {
        if (running) return
        running = true
        reloadFlame()
        lastNs = 0L; dueNs = 0L
        Choreographer.getInstance().postFrameCallback(callback)
    }

    /** The slider was let go. The charge-up preview now plays on a loop, every other preview stops. */
    fun stop() {
        if (kind == CHARGE) {
            if (!geomReady) return
            nf = 0; ns = 0; acc = 0f; heat = 0f; mouth = 0f; cyc = 0f
            beginAnim()
            return
        }
        halt()
    }

    /** Really stop moving and hold a still picture. */
    fun halt() {
        loopsLeft = -1
        if (running) {
            running = false
            Choreographer.getInstance().removeFrameCallback(callback)
        }
        if (kind == CHARGE) warm()                  // hold a clear still picture of the charge
        invalidate()
    }

    /** Flame colours preview: play the fire for [n] loops (restarts the count when it is already playing), then pause on a still picture. */
    fun playLoops(n: Int) {
        loopsLeft = n
        beginAnim()
    }

    /** Hold a still picture that already shows the effect. */
    fun showStill() {
        halt()
        warm()
        invalidate()
    }

    override fun onDetachedFromWindow() {
        halt()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        if ((kind == QUALITY || kind == FLYING) && w > 0 && h > 0) {
            leftRect.set(0f, 0f, w.toFloat(), h.toFloat())
            geomReady = true
        }
        if (kind == CHARGE && w > 0 && h > 0) {
            geomReady = true
            layoutChargeDragon()
            warm()
        }
        if (kind == PARTICLES && w > 0 && h > 0) {
            rightRect.set(0f, 0f, w.toFloat(), h.toFloat())
            val rw = rightRect.width()
            headSt.apply {
                face = 1f; sp = 0f; pitch = 0f; head = 0.2f; eye = 1f; wingPh = 0f; bob = 0f; crouch = 0f; walk = 0f; gait = 0f
                ds = rw * 0.26f / 80f
                x = rightRect.left + rw * 0.20f
                y = h * 0.50f
            }
            model.mouthLocal(headSt, tmp)
            mx = headSt.x + tmp[0] * headSt.ds
            my = headSt.y + tmp[1] * headSt.ds
            icSize = rw * 0.17f
            icx = rightRect.right - rw * 0.15f
            icy = min(h - icSize * 0.7f, max(icSize * 0.7f, my + dp(2f)))
            geomReady = true
            warm()
        }
    }

    // ---------------------------------------------------------------- simulation

    private fun rnd(): Float {
        seed = (seed * 6364136223846793005L + 1442695040888963407L)
        return ((seed ushr 33).toInt() and 0xFFFFFF) / 16777216f
    }

    private fun advance(dt: Float) {
        t += dt
        nextBlink -= dt
        if (nextBlink <= 0f) { blink = 0.14f; nextBlink = 2f + rnd() * 3f }
        if (blink > 0f) blink -= dt
        if (kind == FLYING || kind == SEETHROUGH) wingPh += dt * 6.2832f * 2.2f
        if (fireKind) {
            step(dt)
            if (loopsLeft == 0) { halt(); warm() }       // the requested loops are done: pause on a still picture
        }
    }

    /** Run the fire for a moment so a still picture already shows a flame in flight. */
    private fun warm() {
        if (!geomReady) return
        nf = 0; ns = 0; acc = 0f; heat = 0f; mouth = 0f; cyc = 0.45f
        if (chargeS > 0.05f) {                      // still picture of the charge: the orb is gathering in the mouth
            cyc = 0.3f + 0.88f * chargeS; mouth = 0.5f
            return
        }
        for (i in 0 until 66) step(1f / 60f)
    }

    private fun step(dt: Float) {
        val off = chargeS                           // the breath starts this much later (0 for the Particles preview)
        if (kind == CHARGE) layoutChargeDragon()
        cyc += dt
        while (cyc >= CYCLE + off) { cyc -= CYCLE + off; if (loopsLeft > 0) loopsLeft-- }
        val firing = cyc in (0.45f + off)..(2.1f + off)
        val mouthGoal = if (cyc in (0.3f + off)..(2.25f + off)) 1f else if (off > 0.05f && cyc in (0.3f + off * 0.7f)..(0.3f + off)) 0.5f else 0f
        mouth += (mouthGoal - mouth) * min(1f, dt * 12f)
        heat = if (firing && cyc > 0.7f + off) min(1f, heat + dt * 2.2f) else max(0f, heat - dt * 0.8f)

        val q = if (kind == CHARGE) 1f else pct / 100f
        val capF = min(MAXF, (70 + 130 * q).toInt())
        val capS = min(MAXS, (30 + 60 * q).toInt())

        if (firing) {
            acc += capF / 0.5f * dt
            while (acc >= 1f) {
                acc -= 1f
                if (nf < capF) spawnFlame()
            }
        } else acc = 0f

        var i = 0
        while (i < nf) {
            fage[i] += dt
            if (fage[i] >= flife[i]) {
                // impact: a few sparks fly off the icon
                if (kind != CHARGE && rnd() < 0.5f && ns < capS) spawnSpark(fx[i], fy[i])
                nf--
                fx[i] = fx[nf]; fy[i] = fy[nf]; fvx[i] = fvx[nf]; fvy[i] = fvy[nf]
                fage[i] = fage[nf]; flife[i] = flife[nf]; fsz[i] = fsz[nf]; fph[i] = fph[nf]
                continue
            }
            fx[i] += fvx[i] * dt
            fy[i] += fvy[i] * dt + sin(fage[i] * 22f + fph[i]) * dp(14f) * dt
            i++
        }
        i = 0
        while (i < ns) {
            sage[i] += dt
            if (sage[i] >= slife[i]) {
                ns--
                sx[i] = sx[ns]; sy[i] = sy[ns]; svx[i] = svx[ns]; svy[i] = svy[ns]; sage[i] = sage[ns]; slife[i] = slife[ns]
                continue
            }
            svy[i] += dp(240f) * dt
            sx[i] += svx[i] * dt; sy[i] += svy[i] * dt
            i++
        }
    }

    private fun spawnFlame() {
        val i = nf++
        val life = 0.38f + rnd() * 0.22f
        val tx = icx + (rnd() - 0.5f) * icSize * 0.4f
        val ty = icy + (rnd() - 0.5f) * icSize * 0.4f
        fx[i] = mx + (rnd() - 0.5f) * dp(3f); fy[i] = my + (rnd() - 0.5f) * dp(3f)
        fvx[i] = (tx - fx[i]) / life; fvy[i] = (ty - fy[i]) / life
        fage[i] = 0f; flife[i] = life; fsz[i] = dp(7f) + rnd() * dp(7f); fph[i] = rnd() * 6.28f
    }

    private fun spawnSpark(x: Float, y: Float) {
        val i = ns++
        val a = -3.14159f * (0.15f + rnd() * 0.7f)       // mostly upwards
        val sp = dp(50f + rnd() * 90f)
        sx[i] = x; sy[i] = y; svx[i] = cos(a) * sp * (if (rnd() < 0.5f) 1f else -1f); svy[i] = sin(a) * sp
        sage[i] = 0f; slife[i] = 0.4f + rnd() * 0.4f
    }

    // ---------------------------------------------------------------- drawing

    private fun ramp(a: Float): Int {
        if (!FlameColors.isDefault(flameCols)) {
            val c = FlameColors.at(flameCols, a * 100f)
            return if (a <= 0f) FlameColors.lighten(c, 0.8f) else c
        }
        // white -> cyan -> blue -> violet
        val stops = arrayOf(floatArrayOf(0f, 255f, 255f, 255f), floatArrayOf(0.25f, 120f, 232f, 255f),
            floatArrayOf(0.6f, 50f, 120f, 255f), floatArrayOf(1f, 135f, 80f, 232f))
        var k = 0
        while (k < stops.size - 2 && a > stops[k + 1][0]) k++
        val a0 = stops[k]; val a1 = stops[k + 1]
        val u = ((a - a0[0]) / (a1[0] - a0[0])).coerceIn(0f, 1f)
        return Color.rgb((a0[1] + (a1[1] - a0[1]) * u).toInt(), (a0[2] + (a1[2] - a0[2]) * u).toInt(), (a0[3] + (a1[3] - a0[3]) * u).toInt())
    }

    private fun makeSprite(col: Int): Bitmap {
        val b = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val clear = col and 0x00FFFFFF
        p.shader = RadialGradient(24f, 24f, 24f, intArrayOf(col or (255 shl 24), clear or (150 shl 24), clear),
            floatArrayOf(0f, 0.4f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(24f, 24f, 24f, p)
        return b
    }

    private fun sprite(c: Canvas, idx: Int, x: Float, y: Float, size: Float, alpha: Float) {
        if (alpha <= 0.01f) return
        spritePaint.alpha = (alpha.coerceIn(0f, 1f) * 255f).toInt()
        dst.set(x - size, y - size, x + size, y + size)
        c.drawBitmap(sprites[idx.coerceIn(0, 5)], null, dst, spritePaint)
    }

    private fun setBlink() { st.eye = if (blink > 0f) 0.1f else 1f; headSt.eye = st.eye }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        // box
        clip.reset(); clip.addRoundRect(0f, 0f, w, h, dp(16f), dp(16f), Path.Direction.CW)
        c.save(); c.clipPath(clip)
        fill.shader = LinearGradient(0f, 0f, 0f, h, Color.parseColor("#1D2D55"), Color.parseColor("#0E162E"), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, fill); fill.shader = null
        setBlink()
        when (kind) {
            SIZE -> drawSize(c, w, h)
            QUALITY -> drawFps(c, w, h, false)
            FLYING -> drawFps(c, w, h, true)
            SEETHROUGH -> drawSeeThrough(c, w, h)
            CHARGE -> drawChargeScene(c, w, h)
            else -> drawParticles(c, w, h)
        }
        c.restore()
        line.color = Color.parseColor("#2C3F6B"); line.strokeWidth = dp(1f)
        c.drawRoundRect(dp(0.5f), dp(0.5f), w - dp(0.5f), h - dp(0.5f), dp(16f), dp(16f), line)
    }

    private fun sit(s: DragonModel.State, time: Float) {
        s.face = 1f; s.sp = 0f; s.pitch = 0f; s.mouth = 0f; s.wingPh = 0f; s.bob = 0f; s.crouch = 0f; s.walk = 0f; s.gait = 0f
        s.head = model.restHead(0f, 0f) + 0.04f * sin(time * 0.6f)
        s.time = time
    }

    private fun drawSize(c: Canvas, w: Float, h: Float) {
        val base = h - dp(26f)                                   // ground line
        val ds150 = min(w * 0.94f / MODEL_W, (base - dp(8f)) / MODEL_H)
        val ds = ds150 * pct / 150f
        val ds100 = ds150 * 100f / 150f
        // bar = the dragon's width at 100%, a reference for how much the size changes
        val bw = MODEL_W * ds100
        fill.color = Color.argb(210, 220, 230, 247)
        c.drawRoundRect(w / 2f - bw / 2f, base, w / 2f + bw / 2f, base + dp(6f), dp(3f), dp(3f), fill)
        textFill.textSize = dp(10f); textFill.color = Color.parseColor("#8A9BBD")
        c.drawText("width at 100%", w / 2f, h - dp(6f), textFill)
        sit(st, t)
        st.ds = ds
        st.x = w / 2f - MODEL_CX * ds
        st.y = base
        model.draw(c, st)
    }

    private fun drawFps(c: Canvas, w: Float, h: Float, flying: Boolean) {
        if (!geomReady) return
        val full = max(5, Math.round(hz * pct / 100f))
        val shown = if (flying) full else min(30, full)

        if (flying) {
            sit(st, t)
            st.sp = 1f
            st.head = model.restHead(1f, 0f)
            st.wingPh = wingPh
            st.bob = sin(wingPh) * 3f - 4f
            val ds = min(w * 0.62f / FLY_W, (h - dp(60f)) / FLY_H)
            st.ds = ds
            st.x = w / 2f - FLY_CX * ds
            st.y = (h + dp(8f)) / 2f - FLY_CY * ds
            model.draw(c, st)
        } else {
            val base = h - dp(26f)
            val ds = min(w * 0.62f / MODEL_W, (base - dp(8f)) / MODEL_H)
            sit(st, t)
            st.ds = ds
            st.x = w / 2f - MODEL_CX * ds
            st.y = base
            fill.color = Color.argb(200, 220, 230, 247)
            c.drawRoundRect(w / 2f - MODEL_W * ds * 0.5f, base, w / 2f + MODEL_W * ds * 0.5f, base + dp(4f), dp(2f), dp(2f), fill)
            model.draw(c, st)
        }

        // caption, bottom centre
        textFill.textAlign = Paint.Align.CENTER
        textFill.textSize = dp(10f); textFill.color = Color.parseColor("#B8C6E4")
        textFill.setShadowLayer(dp(4f), 0f, dp(1f), Color.argb(200, 0, 0, 0))
        c.drawText(if (flying) "FLYING" else "SITTING", w / 2f, h - dp(7f), textFill)

        // frame rate, top-right: big white number on a soft shadow
        val numSize = min(dp(46f), w * 0.24f)
        val rx = w - dp(10f)
        val ny = dp(8f) + numSize * 0.80f
        fill.shader = RadialGradient(rx - dp(26f), ny - dp(8f), dp(56f),
            intArrayOf(Color.argb(170, 4, 8, 20), Color.argb(80, 4, 8, 20), Color.argb(0, 4, 8, 20)),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(rx - dp(26f), ny - dp(8f), dp(56f), fill)
        fill.shader = null
        textFill.textAlign = Paint.Align.RIGHT
        textFill.typeface = Typeface.DEFAULT_BOLD
        textFill.textSize = numSize; textFill.color = Color.WHITE
        textFill.setShadowLayer(dp(9f), 0f, dp(2f), Color.argb(235, 0, 0, 0))
        c.drawText(shown.toString(), rx, ny, textFill)
        textFill.textSize = dp(12f); textFill.color = Color.WHITE
        textFill.setShadowLayer(dp(6f), 0f, dp(1.5f), Color.argb(235, 0, 0, 0))
        c.drawText("fps", rx, ny + dp(14f), textFill)
        textFill.setShadowLayer(0f, 0f, 0f, 0)
        textFill.textAlign = Paint.Align.CENTER
    }

    private val tileCols = intArrayOf(
        Color.parseColor("#E8574A"), Color.parseColor("#F2B134"), Color.parseColor("#4CB86B"),
        Color.parseColor("#3D8BE8"), Color.parseColor("#A66BE0"), Color.parseColor("#EDEDED")
    )

    /** The dragon hovering over a grid of dummy app icons, so the see-through effect shows. */
    private fun drawSeeThrough(c: Canvas, w: Float, h: Float) {
        // dummy icons: 3 columns, as many rows as fit
        val tile = w * 0.24f
        val gap = (w - 3f * tile) / 4f
        val rows = max(1, ((h - gap) / (tile + gap)).toInt())
        val top = (h - (rows * tile + (rows - 1) * gap)) / 2f
        val r = tile * 0.24f
        for (ry in 0 until rows) for (cx in 0 until 3) {
            val x0 = gap + cx * (tile + gap); val y0 = top + ry * (tile + gap)
            val k = (ry * 3 + cx) % tileCols.size
            fill.shader = LinearGradient(0f, y0, 0f, y0 + tile, tileCols[k], Color.argb(255,
                (Color.red(tileCols[k]) * 0.62f).toInt(), (Color.green(tileCols[k]) * 0.62f).toInt(), (Color.blue(tileCols[k]) * 0.62f).toInt()),
                Shader.TileMode.CLAMP)
            c.drawRoundRect(x0, y0, x0 + tile, y0 + tile, r, r, fill)
            fill.shader = null
            fill.color = Color.argb(200, 255, 255, 255)
            when (k % 3) {
                0 -> c.drawCircle(x0 + tile / 2f, y0 + tile / 2f, tile * 0.22f, fill)
                1 -> c.drawRoundRect(x0 + tile * 0.28f, y0 + tile * 0.28f, x0 + tile * 0.72f, y0 + tile * 0.72f, tile * 0.08f, tile * 0.08f, fill)
                else -> c.drawRoundRect(x0 + tile * 0.24f, y0 + tile * 0.42f, x0 + tile * 0.76f, y0 + tile * 0.58f, tile * 0.07f, tile * 0.07f, fill)
            }
        }
        // dragon hovering in the middle, wings spread
        sit(st, t)
        st.sp = 1f
        st.head = model.restHead(1f, 0f)
        st.wingPh = wingPh + 0.6f
        st.bob = 0f
        val ds = min(w * 0.96f / FLY_W, h * 0.80f / FLY_H)
        st.ds = ds
        st.x = w / 2f - FLY_CX * ds
        st.y = h / 2f - FLY_CY * ds
        model.setTransparency(bodyT, wingT)
        // Android 12+ makes this kind of overlay window 20% see-through, so the most solid it can be is 80%: show the same here
        val capped = android.os.Build.VERSION.SDK_INT >= 31
        val sv = if (capped) c.saveLayerAlpha(0f, 0f, w, h, (0.8f * 255f).toInt()) else 0
        model.draw(c, st)
        if (capped) c.restoreToCount(sv)
        textFill.textAlign = Paint.Align.CENTER
        textFill.textSize = dp(9.5f); textFill.color = Color.parseColor("#B8C6E4")
        textFill.setShadowLayer(dp(3f), 0f, dp(1f), Color.argb(220, 0, 0, 0))
        c.drawText("body $bodyT% - wings $wingT%", w / 2f, h - dp(7f), textFill)
        textFill.setShadowLayer(0f, 0f, 0f, 0)
        textFill.textAlign = Paint.Align.CENTER
    }

    private fun sm(a: Float, b: Float, x: Float): Float {
        val u = ((x - a) / (b - a)).coerceIn(0f, 1f)
        return u * u * (3f - 2f * u)
    }

    /** Charge-up preview: place the whole sitting dragon on the left, and aim the breath to the right into empty space. */
    private fun layoutChargeDragon() {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val base = h - dp(26f)
        val ds = min(w * 0.46f / MODEL_W, (base - dp(8f)) / MODEL_H)
        sit(st, t)
        st.mouth = mouth
        st.ds = ds
        st.x = dp(10f) - (MODEL_CX - MODEL_W * 0.5f) * ds
        st.y = base
        model.mouthPos(st, tmp)
        mx = tmp[0]; my = tmp[1]
        icx = min(w - dp(8f), mx + w * 0.36f); icy = my + dp(8f); icSize = dp(34f)
    }

    private fun drawChargeScene(c: Canvas, w: Float, h: Float) {
        if (!geomReady) return
        layoutChargeDragon()
        val ds = st.ds
        // ground line under the dragon
        fill.color = Color.argb(200, 220, 230, 247)
        val gl = st.x + (MODEL_CX - MODEL_W * 0.5f) * ds
        c.drawRoundRect(gl, st.y, gl + MODEL_W * ds, st.y + dp(4f), dp(2f), dp(2f), fill)
        model.draw(c, st)
        drawChargeFx(c)
        // flames of the breath
        for (i in 0 until nf) {
            val a = fage[i] / flife[i]
            val sz = fsz[i] * (0.6f + a * 0.9f)
            sprite(c, (a * 5.99f).toInt(), fx[i], fy[i], sz, (1f - a).pow(0.7f))
        }
        if (mouth > 0.6f) {
            sprite(c, 1, mx, my, dp(13f) * mouth, 0.8f * mouth)
            sprite(c, 0, mx, my, dp(6f) * mouth, 0.9f * mouth)
        }
        textFill.textSize = dp(10.5f); textFill.color = Color.parseColor("#B8C6E4")
        textFill.textAlign = Paint.Align.CENTER
        textFill.setShadowLayer(dp(3f), 0f, dp(1f), Color.argb(200, 0, 0, 0))
        c.drawText(if (chargeS <= 0.05f) "charge-up: off" else "charge-up: " + String.format("%.1f", chargeS) + " s", w / 2f, h - dp(8f), textFill)
        textFill.setShadowLayer(0f, 0f, 0f, 0)
        textFill.textAlign = Paint.Align.CENTER
    }

    /** The same charge-up as on the home screen: spine wave, glowing spikes, orb with sparks and rings in the mouth, flash and ring at release. */
    private fun drawChargeFx(c: Canvas) {
        val off = chargeS
        if (off <= 0.05f || cyc < 0.3f) return
        val ft = cyc - (0.3f + off)                       // time since the breath started (negative while charging)
        if (ft >= 0.45f) return
        val u = ((cyc - 0.3f) / off).coerceIn(0f, 1f)
        val amt = if (ft < 0f) sm(0f, 0.1f, u) else 1f - sm(0.1f, 0.45f, ft)
        if (amt <= 0.01f) return
        val ds = st.ds
        val pw = 0.75f + 0.25f * (off / 1.5f).coerceIn(0f, 1.6f)
        val build = 0.5f + 0.5f * u
        val fw = (u / 0.7f).coerceIn(0f, 1f) * 0.97f
        val gt = ((u - 0.6f) / 0.4f).coerceIn(0f, 1f)
        val rate = 14f + 26f * u
        val n = model.chargePoints(st, cbx, cby, ctx, cty, cfr)
        for (k in 0 until n) {
            val d = fw - cfr[k] + 0.04f
            val lit = sm(0f, 0.08f, d)
            val flash = sm(0f, 0.05f, d) * (1f - sm(0.05f, 0.3f, d))
            val fl = 0.85f + 0.15f * sin(t * rate + k * 0.9f)
            chB[k] = min(1f, lit * 0.72f * build * fl + flash * 0.95f) * amt
        }
        var lastLit = 0f
        for (k in 0 until n) {
            val b = chB[k]
            if (b <= 0.01f) continue
            if (k == n - 1) lastLit = b
            val d = fw - cfr[k] + 0.04f
            val fk = 0.9f + 0.4f * sm(0f, 0.05f, d) * (1f - sm(0.05f, 0.3f, d))
            sprite(c, 2, cbx[k], cby[k], 17f * ds * fk, b * 0.38f)
            sprite(c, 2, cbx[k], cby[k], 9f * ds, b * 0.8f)
            sprite(c, 1, ctx[k], cty[k], 10.5f * ds * (0.7f + 0.3f * b), b * 0.95f)
            sprite(c, 0, ctx[k], cty[k], 5f * ds * (0.6f + 0.4f * b), b)
            if (k + 1 < n) {
                val mb = (b + chB[k + 1]) * 0.5f
                if (mb > 0.02f) {
                    val x2 = (cbx[k] + cbx[k + 1]) * 0.5f; val y2 = (cby[k] + cby[k + 1]) * 0.5f
                    sprite(c, 1, x2, y2, 7f * ds, mb * 0.55f)
                    sprite(c, 2, x2, y2, 12f * ds, mb * 0.35f)
                }
            }
        }
        if (fw > 0.01f && fw < 0.95f) {                                   // bright head of the wave
            var k = 0
            while (k + 1 < n - 1 && cfr[k + 1] < fw) k++
            val f = ((fw - cfr[k]) / max(0.01f, cfr[k + 1] - cfr[k])).coerceIn(0f, 1f)
            val wx = cbx[k] + (cbx[k + 1] - cbx[k]) * f; val wy = cby[k] + (cby[k + 1] - cby[k]) * f
            sprite(c, 1, wx, wy, 14f * ds, 0.9f * amt)
            sprite(c, 0, wx, wy, 6f * ds, amt)
        }
        model.mouthPoint(st, 28f, 3f, orbP)
        if (gt > 0f && ft < 0f && n > 1) {
            val g = sm(0f, 1f, gt)
            val ou = 0.15f + 0.85f * g
            val pulse = 0.88f + 0.12f * sin(t * rate)
            val ox = orbP[0] + sin(t * 61f) * 0.8f * ds * ou; val oy = orbP[1] + cos(t * 53f) * 0.8f * ds * ou
            for (j in 0 until 4) {                                          // streams from the last spike into the mouth
                val f = (gt * 1.6f + j * 0.25f) % 1f
                val px = ctx[n - 1] + (ox - ctx[n - 1]) * f; val py = cty[n - 1] + (oy - cty[n - 1]) * f
                sprite(c, 0, px, py, 3.2f * ds, (1f - f) * 0.95f * max(lastLit, 0.6f))
                sprite(c, 1, px, py, 6f * ds, (1f - f) * 0.5f * max(lastLit, 0.6f))
            }
            for (j in 0 until 12) {                                         // sparks pulled in from all around
                val a = j * 2.399963f + t * 0.7f
                val f = (gt * 2.2f + j * 0.37f) % 1f
                val r = (46f - 10f * (j % 3)) * ds * (1f - f)
                sprite(c, 0, ox + cos(a) * r, oy + sin(a) * r, 2.4f * ds + dp(0.8f), (0.3f + 0.7f * f) * g)
            }
            line.strokeWidth = max(dp(1f), 1.5f * ds)
            for (j in 0 until 3) {                                          // rings shrinking into the mouth
                val f = (gt * 1.8f + j / 3f) % 1f
                line.color = (ramp(0.18f) and 0x00FFFFFF) or ((sin(f * 3.14159f) * 0.65f * g * 255f).toInt().coerceIn(0, 255) shl 24)
                c.drawCircle(ox, oy, (40f - 34f * f) * ds * pw, line)
            }
            sprite(c, 2, ox, oy, 28f * ds * ou * pw * pulse, 0.55f * g)
            sprite(c, 1, ox, oy, 17f * ds * ou * pw * pulse, 0.85f * g)
            sprite(c, 0, ox, oy, 9f * ds * ou * pw, g)
        }
        if (ft in 0f..0.4f) {                                               // release: flash and a ring flying outwards
            val rp = ft / 0.4f; val rl = 1f - rp
            sprite(c, 0, orbP[0], orbP[1], (10f + 14f * rl) * ds * pw, rl)
            sprite(c, 1, orbP[0], orbP[1], (18f + 26f * rp) * ds * pw, rl * 0.9f)
            sprite(c, 2, orbP[0], orbP[1], (30f + 30f * rp) * ds * pw, rl * 0.5f)
            line.strokeWidth = max(dp(1.2f), 2.4f * ds * rl)
            line.color = (ramp(0f) and 0x00FFFFFF) or ((rl * 0.85f * 255f).toInt().coerceIn(0, 255) shl 24)
            c.drawCircle(orbP[0], orbP[1], (8f + 52f * rp) * ds * pw, line)
        }
    }

    private fun drawParticles(c: Canvas, w: Float, h: Float) {
        if (!geomReady) return
        c.save(); c.clipRect(rightRect)
        // dummy icon
        val half = icSize / 2f
        val bump = 1f + 0.05f * heat
        fill.color = Color.parseColor("#2B3C66")
        c.drawRoundRect(icx - half * bump, icy - half * bump, icx + half * bump, icy + half * bump, dp(9f), dp(9f), fill)
        line.color = Color.parseColor("#4A6199"); line.strokeWidth = dp(1.2f)
        c.drawRoundRect(icx - half * bump, icy - half * bump, icx + half * bump, icy + half * bump, dp(9f), dp(9f), line)
        fill.color = Color.parseColor("#6F86B8")
        c.drawCircle(icx, icy - half * 0.12f, half * 0.34f, fill)
        c.drawRoundRect(icx - half * 0.46f, icy + half * 0.30f, icx + half * 0.46f, icy + half * 0.50f, dp(3f), dp(3f), fill)
        // head
        headSt.mouth = mouth; headSt.time = t
        model.drawHeadOnly(c, headSt)
        // glow on the icon: white-hot, then cyan, then deep blue as it cools
        if (heat > 0.02f) {
            val gi = ((1f - heat) * 4f).toInt()
            sprite(c, gi, icx, icy, icSize * (1.1f + 0.5f * heat), heat)
            sprite(c, 0, icx, icy, icSize * 0.6f * heat, heat * 0.8f)
        }
        // flames
        for (i in 0 until nf) {
            val a = fage[i] / flife[i]
            val sz = fsz[i] * (0.6f + a * 0.9f)
            sprite(c, (a * 5.99f).toInt(), fx[i], fy[i], sz, (1f - a).pow(0.7f))
        }
        // mouth flare
        if (mouth > 0.05f) {
            sprite(c, 1, mx, my, dp(13f) * mouth, 0.8f * mouth)
            sprite(c, 0, mx, my, dp(6f) * mouth, 0.9f * mouth)
        }
        // sparks
        for (i in 0 until ns) {
            val a = sage[i] / slife[i]
            sprite(c, if (a < 0.4f) 0 else 1, sx[i], sy[i], dp(3.2f), 1f - a)
        }
        textFill.textSize = dp(10.5f); textFill.color = Color.parseColor("#B8C6E4")
        textFill.textAlign = Paint.Align.CENTER
        val capF = min(MAXF, (70 + 130 * pct / 100f).toInt())
        c.drawText("flames up to $capF", rightRect.centerX(), h - dp(8f), textFill)
        textFill.textAlign = Paint.Align.CENTER
        c.restore()
    }
}
