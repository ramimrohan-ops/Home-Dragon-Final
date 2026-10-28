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
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * The one preview box for every setting: a wide rectangle with the dragon on the left, a dummy icon grid behind it (so the see-through
 * effect shows) and room on the right for the fire. The dummy icons are drawn at the size of your real home screen icons (measured by the
 * icon finder), and the dragon has the same size next to them as on the home screen.
 *
 * - Sitting / Flying buttons (bottom left). Sitting: the dragon sits with its wings open and upright; the scale bar (the dragon's width at 100%)
 *   shows only here. Flying: wings flapping in place, still at the top of the flap.
 * - DEFAULT POSITION: sitting with open wings, or flying with the wings at the top of the flap, no fire. The box rests here.
 * - Touching a slider, or tapping a flame palette or the colour button, plays ONE loop (charge-up, breath, flapping) and goes back to the default position.
 * - Play button (top right): loops without end until it is pressed again (it then reads Stop) and the dragon goes back to the default position.
 * - Every value change is drawn at once. Nothing moves, and nothing is drawn, while the box rests.
 */
class MasterPreview(context: Context) : View(context) {

    private companion object {
        const val FLY_W = 256f               // flying pose: width in model units
        const val FLY_H = 250f               // flying pose: height in model units
        const val FLY_CX = -31.5f            // flying pose: horizontal centre
        const val FLY_CY = -66f              // flying pose: vertical centre
        const val MODEL_W = 200.5f           // sitting dragon: width in model units (tail tip to snout)
        const val MODEL_H = 201f             // sitting dragon: height in model units (ground to wing tip)
        const val MODEL_CX = -32.2f          // sitting dragon: horizontal centre
        const val CYCLE = 3.6f               // seconds per loop (plus the charge-up time)
        const val MAXF = 260
        const val TOP_PH = 5.2f              // wing phase at the top of the flap (default flying position)
        const val TWO_PI = 6.2832f
    }

    private val dm = context.resources.displayMetrics.density
    private fun dp(v: Float) = v * dm

    private val model = DragonModel()
    private val st = DragonModel.State()

    // values of the sliders
    private var qualityFps = Prefs.qualityFps(context)
    private var particlePct = Prefs.particlePct(context)
    private var sizePct = Prefs.scalePct(context)
    private var speedPct = Prefs.speedPct(context)
    private var chargeTenths = Prefs.chargeTenths(context)
    private var chargeQPct = Prefs.chargeQualityPct(context)
    private var thickPct = Prefs.chargeThickPct(context)
    private var bodyT = Prefs.transparencyPct(context)
    private var flyPose = Prefs.previewFly(context)

    // animation state
    private var running = false
    private var looping = false               // Play pressed: no end
    private var loopsLeft = 0                 // loops still to play after a touch (one loop)
    private var finishing = false             // the loop is over; the wings finish their last flap
    private var finishPh = 0f
    private var t = 1.2f
    private var cyc = 0f
    private var wingPh = TOP_PH
    private var blink = 0f
    private var nextBlink = 2f
    private var mouth = 0f
    private var heat = 0f
    private var acc = 0f
    private var lastNs = 0L
    private var dueNs = 0L
    private var seed = 12345L

    private val chargeS get() = chargeTenths / 10f

    private val hz: Float = max(30f, FpsStops.topHz(context).toFloat())

    // flame particles
    private val fx = FloatArray(MAXF); private val fy = FloatArray(MAXF)
    private val fvx = FloatArray(MAXF); private val fvy = FloatArray(MAXF)
    private val fage = FloatArray(MAXF); private val flife = FloatArray(MAXF); private val fsz = FloatArray(MAXF)
    private val fph = FloatArray(MAXF)
    private var nf = 0

    // geometry
    private var mx = 0f; private var my = 0f              // mouth tip
    private var icx = 0f; private var icy = 0f; private var icSize = 0f
    private var geomReady = false
    private val tmp = FloatArray(2)
    private var bgBitmap: Bitmap? = null
    private var lastArt = 0f                              // icon size the background was drawn with

    // flame colours
    private var flameCols: IntArray = FlameColors.BLUE.copyOf()
    private var sprites: Array<Bitmap> = Array(6) { makeSprite(ramp(it / 5f)) }
    private var gradInts = IntArray(8) { ramp(it / 7f * 0.85f) }

    /** Use the flame colours chosen in the app. */
    fun reloadFlame() {
        val cols = Prefs.flameColors(context)
        if (FlameColors.same(cols, flameCols)) return
        flameCols = cols
        sprites = Array(6) { makeSprite(ramp(it / 5f)) }
        gradInts = IntArray(8) { ramp(it / 7f * 0.85f) }
        if (FlameColors.isBlue(cols)) model.setFlameGlow(Color.rgb(220, 245, 255), Color.rgb(70, 170, 255), Color.rgb(30, 80, 255))
        else model.setFlameGlow(ramp(0f), ramp(0.18f), ramp(0.5f))
        invalidate()
    }

    init { reloadFlame() }

    private val spritePaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply { blendMode = BlendMode.PLUS }
    private val dst = RectF()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val textFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER }
    private val clip = Path()

    // ---------------------------------------------------------------- values from the sliders (drawn at once)

    fun setQuality(fps: Int) { qualityFps = fps; invalidate() }
    fun setParticles(v: Int) { particlePct = v; invalidate() }
    fun setSize(v: Int) { sizePct = v; invalidate() }
    fun setSpeed(v: Int) { speedPct = v; invalidate() }
    fun setChargeTime(tenths: Int) { chargeTenths = tenths; invalidate() }
    fun setChargeQuality(v: Int) { chargeQPct = v; invalidate() }
    fun setThickness(v: Int) { thickPct = v; invalidate() }
    fun setTransparency(body: Int) { bodyT = body; invalidate() }

    /** The dragon colours changed (or were reset): draw again. */
    fun reloadPalette() = invalidate()

    // ---------------------------------------------------------------- control

    /** A slider was touched (or a palette / the colour button tapped): play one loop, then back to the default position. */
    fun touched() {
        if (looping) return              // Play is already looping: the changes simply show
        startLoop(1)
    }

    private fun startLoop(n: Int) {
        if (!geomReady) { invalidate(); return }
        resetSim()
        loopsLeft = n
        beginAnim()
    }

    /** The Play / Stop button. */
    private fun togglePlay() {
        if (looping) {
            looping = false
            toDefault()
        } else {
            looping = true
            startLoop(-1)
        }
    }

    private fun setPose(fly: Boolean) {
        if (fly == flyPose) return
        flyPose = fly
        Prefs.setPreviewFly(context, fly)
        if (running) {                    // a loop is playing: carry on in the new pose
            finishing = false
            nf = 0
        }
        invalidate()
    }

    private fun resetSim() {
        nf = 0; acc = 0f; heat = 0f; mouth = 0f; cyc = 0f
        wingPh = TOP_PH; t = 1.2f; blink = 0f
        finishing = false; loopsLeft = 0
    }

    /** Stop everything and rest in the default position of the chosen pose. */
    private fun toDefault() {
        if (running) {
            running = false
            Choreographer.getInstance().removeFrameCallback(callback)
        }
        resetSim()
        invalidate()
    }

    /** Leaving the screen: stop and rest. */
    fun stop() {
        looping = false
        toDefault()
    }

    private fun beginAnim() {
        if (running) return
        running = true
        reloadFlame()
        lastNs = 0L; dueNs = 0L
        Choreographer.getInstance().postFrameCallback(callback)
    }

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
            if (!running) return
            invalidate()
            val want = (1e9f / rate()).toLong()
            dueNs = if (dueNs == 0L || frameTimeNanos - dueNs > want) frameTimeNanos + want else dueNs + want
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    /** What the real dragon would use for this Quality: a sitting dragon without fire is drawn at 30 fps at most. */
    private fun fullRate(): Float = max(5f, min(qualityFps.toFloat(), hz))

    private val firing get() = cyc in (0.45f + chargeS)..(2.1f + chargeS)

    private fun rate(): Float = if (flyPose || firing || cyc >= 0.3f) fullRate() else min(30f, fullRate())

    override fun onDetachedFromWindow() {
        looping = false
        toDefault()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        if (w > 0 && h > 0) {
            geomReady = true
            makeBackground(w, h)
        }
    }

    // ---------------------------------------------------------------- simulation

    private fun rnd(): Float {
        seed = (seed * 6364136223846793005L + 1442695040888963407L)
        return ((seed ushr 33).toInt() and 0xFFFFFF) / 16777216f
    }

    private fun advance(dt: Float) {
        val d = dt * speedPct / 100f
        t += d
        nextBlink -= d
        if (nextBlink <= 0f) { blink = 0.14f; nextBlink = 2f + rnd() * 3f }
        if (blink > 0f) blink -= d
        if (flyPose) wingPh += d * TWO_PI * 2.2f
        if (finishing) {
            // the loop is over: the wings finish the flap they are in and stop at the top
            if (!flyPose || wingPh >= finishPh) toDefault()
            return
        }
        step(d)
        if (loopsLeft == 0 && !looping) {
            // one loop done: back to the default position (a flying dragon first finishes its flap)
            if (flyPose) {
                finishing = true
                finishPh = TOP_PH + Math.ceil(((wingPh - TOP_PH) / TWO_PI).toDouble()).toFloat() * TWO_PI
                nf = 0; mouth = 0f; heat = 0f
            } else {
                toDefault()
            }
        }
    }

    private fun step(dt: Float) {
        val off = chargeS                           // the breath starts this much later (0 = no charge-up)
        cyc += dt
        while (cyc >= CYCLE + off) { cyc -= CYCLE + off; if (loopsLeft > 0) loopsLeft-- }
        if (loopsLeft == 0 && !looping) return      // the loop that was asked for is complete
        val fire = cyc in (0.45f + off)..(2.1f + off)
        val mouthGoal = if (cyc in 0.3f..(2.25f + off)) 1f else 0f
        mouth += (mouthGoal - mouth) * min(1f, dt * (if (mouthGoal < mouth && off > 0.05f) 4f else 12f))
        heat = if (fire && cyc > 0.7f + off) min(1f, heat + dt * 2.2f) else max(0f, heat - dt * 0.8f)

        val q = particlePct / 100f
        val capF = min(MAXF, (70 + 130 * q).toInt())
        if (fire) {
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
                nf--
                fx[i] = fx[nf]; fy[i] = fy[nf]; fvx[i] = fvx[nf]; fvy[i] = fvy[nf]
                fage[i] = fage[nf]; flife[i] = flife[nf]; fsz[i] = fsz[nf]; fph[i] = fph[nf]
                continue
            }
            fx[i] += fvx[i] * dt
            fy[i] += fvy[i] * dt + sin(fage[i] * 22f + fph[i]) * dp(14f) * dt
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

    // ---------------------------------------------------------------- drawing helpers

    private fun ramp(a: Float): Int {
        if (!FlameColors.isBlue(flameCols)) {
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

    private val tileCols = intArrayOf(
        Color.parseColor("#E8574A"), Color.parseColor("#F2B134"), Color.parseColor("#4CB86B"),
        Color.parseColor("#3D8BE8"), Color.parseColor("#A66BE0"), Color.parseColor("#EDEDED")
    )

    /** Width of a normal icon's picture on your home screen, in pixels: measured by the icon finder, or a close guess until it has run once. */
    private fun artPx(): Float {
        val saved = Prefs.iconArtPx(context)
        return if (saved >= dp(30f)) saved else 0.171f * resources.displayMetrics.widthPixels
    }

    /** The dragon's scale on the home screen at Dragon size 100% (the same formula as the real dragon uses, in the same pixels). */
    private fun dsHome100(): Float = artPx() / 58f * 0.55f

    /** The icon size changed (the icon finder measured it): draw the dummy icons again. */
    fun reloadIcons() {
        if (width > 0 && height > 0 && abs(artPx() - lastArt) >= 1f) { makeBackground(width, height); invalidate() }
    }

    /** The box background (gradient and a dummy icon grid at your real icon size) drawn once per size. */
    private fun makeBackground(w: Int, h: Int) {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), Color.parseColor("#1D2D55"), Color.parseColor("#0E162E"), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p); p.shader = null
        // dummy icons: the size and spacing of your home screen (columns across the screen width, rows one icon cell high)
        val tile = artPx()
        lastArt = tile
        val cols0 = Prefs.iconCols(context).takeIf { it in 3..7 } ?: 4
        val colPitch = resources.displayMetrics.widthPixels / cols0.toFloat()
        val rowPitch = tile / 0.72f
        val cols = max(2, (w / colPitch).toInt())                          // whole columns that fit in the box
        val left = (w - cols * colPitch) / 2f + (colPitch - tile) / 2f
        val topLimit = dp(24f)
        val avail = h - dp(46f) - topLimit                                  // between the top edge and the buttons
        val rows = (((avail - tile) / rowPitch).toInt() + 1).coerceIn(1, 3)
        val top = topLimit + max(0f, (avail - (tile + (rows - 1) * rowPitch)) / 2f)
        val r = tile * 0.24f
        for (ry in 0 until rows) for (cx in 0 until cols) {
            val x0 = left + cx * colPitch; val y0 = top + ry * rowPitch
            val k = (ry * cols + cx) % tileCols.size
            val col = tileCols[k]
            p.shader = LinearGradient(0f, y0, 0f, y0 + tile, (col and 0x00FFFFFF) or (0x7A shl 24),
                Color.argb(0x7A, (Color.red(col) * 0.62f).toInt(), (Color.green(col) * 0.62f).toInt(), (Color.blue(col) * 0.62f).toInt()), Shader.TileMode.CLAMP)
            c.drawRoundRect(x0, y0, x0 + tile, y0 + tile, r, r, p)
            p.shader = null
            p.color = Color.argb(120, 255, 255, 255)
            when (k % 3) {
                0 -> c.drawCircle(x0 + tile / 2f, y0 + tile / 2f, tile * 0.22f, p)
                1 -> c.drawRoundRect(x0 + tile * 0.28f, y0 + tile * 0.28f, x0 + tile * 0.72f, y0 + tile * 0.72f, tile * 0.08f, tile * 0.08f, p)
                else -> c.drawRoundRect(x0 + tile * 0.24f, y0 + tile * 0.42f, x0 + tile * 0.76f, y0 + tile * 0.58f, tile * 0.07f, tile * 0.07f, p)
            }
        }
        bgBitmap = b
        // the fire lands on the middle-row icon of the last column
        icSize = tile
        icx = left + (cols - 1) * colPitch + tile / 2f
        icy = top + (rows / 2) * rowPitch + tile / 2f
    }

    // ---------------------------------------------------------------- drawing

    private val poseRect = arrayOf(RectF(), RectF())
    private val playRect = RectF()

    /** Puts the dragon in the box for the chosen pose and size. The size grows and shrinks around a fixed point. */
    private fun layoutDragon(w: Float, h: Float) {
        st.face = 1f; st.pitch = 0f; st.mouth = mouth; st.bob = 0f; st.crouch = 0f; st.walk = 0f; st.gait = 0f
        st.time = t
        st.eye = if (blink > 0f) 0.1f else 1f
        // no fire in the mouth until the breath starts
        st.mouthGlow = if (chargeS > 0.05f) (if (cyc >= 0.3f + chargeS) mouth else 0f) else -1f
        val area = h - dp(46f)                        // above the buttons
        if (flyPose) {
            st.sp = 1f
            st.head = model.restHead(1f, 0f)
            st.wingPh = wingPh
            st.bob = (sin(wingPh) - sin(TOP_PH)) * 3f
            val ds150 = min(w * 0.46f / FLY_W, (area - dp(6f)) * 0.96f / FLY_H)     // the most the box can hold
            val ds = min(dsHome100() * sizePct / 100f, ds150)                       // the same size next to the icons as on the home screen
            val cx = dp(10f) + FLY_W * min(dsHome100(), ds150) * 0.5f
            st.ds = ds
            st.x = cx - FLY_CX * ds
            st.y = area / 2f - FLY_CY * ds
        } else {
            st.sp = 0f
            st.wingPh = 0f
            st.head = model.restHead(0f, 0f) + 0.04f * sin(t * 0.6f)
            val base = area - dp(8f)                  // ground line
            val ds150 = min(w * 0.42f / MODEL_W, (base - dp(6f)) / MODEL_H)
            val ds = min(dsHome100() * sizePct / 100f, ds150)
            val cx = dp(10f) + MODEL_W * min(dsHome100(), ds150) * 0.5f
            st.ds = ds
            st.x = cx - MODEL_CX * ds
            st.y = base
        }
        model.mouthPos(st, tmp)
        mx = tmp[0]; my = tmp[1]
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        clip.reset(); clip.addRoundRect(0f, 0f, w, h, dp(16f), dp(16f), Path.Direction.CW)
        c.save(); c.clipPath(clip)
        val bg = bgBitmap
        if (bg != null) c.drawBitmap(bg, 0f, 0f, null) else { fill.color = Color.parseColor("#12203F"); c.drawRect(0f, 0f, w, h, fill) }

        layoutDragon(w, h)

        // scale bar: sitting only; it is the dragon's width at 100%
        if (!flyPose) {
            val area = h - dp(46f)
            val base = area - dp(8f)
            val ds150 = min(w * 0.42f / MODEL_W, (base - dp(6f)) / MODEL_H)
            val ds100 = min(dsHome100(), ds150)
            val cx = dp(10f) + MODEL_W * ds100 * 0.5f
            val bw = MODEL_W * ds100
            fill.shader = null; fill.color = Color.argb(210, 220, 230, 247)
            c.drawRoundRect(cx - bw / 2f, base + dp(3f), cx + bw / 2f, base + dp(8f), dp(2.5f), dp(2.5f), fill)
            textFill.textSize = dp(9f); textFill.color = Color.parseColor("#8A9BBD"); textFill.textAlign = Paint.Align.LEFT
            c.drawText("width at 100%", cx + bw / 2f + dp(6f), base + dp(8f), textFill)
            textFill.textAlign = Paint.Align.CENTER
        }

        // The whole overlay window (dragon, fire, glow) is one layer: Android 12+ makes it 20% see-through, so the most solid it can be is 80%.
        // Drawn into a transparent layer first, the additive fire behaves as it does on the home screen, not as on this solid background.
        model.setTransparency(bodyT, Prefs.wingOf(bodyT))
        val capped = android.os.Build.VERSION.SDK_INT >= 31
        val sv = if (capped) c.saveLayerAlpha(0f, 0f, w, h, (0.8f * 255f).toInt()) else 0

        // the icon the fire lands on lights up with the heat
        if (heat > 0.02f) {
            val gi = ((1f - heat) * 4f).toInt()
            sprite(c, gi, icx, icy, icSize * (1.1f + 0.5f * heat), heat)
            sprite(c, 0, icx, icy, icSize * 0.6f * heat, heat * 0.8f)
        }

        model.draw(c, st)
        drawCharge(c)

        // flames of the breath
        for (i in 0 until nf) {
            val a = fage[i] / flife[i]
            val sz = fsz[i] * (0.6f + a * 0.9f)
            sprite(c, (a * 5.99f).toInt(), fx[i], fy[i], sz, (1f - a).pow(0.7f))
        }
        if (mouth > 0.05f && (chargeS <= 0.05f || cyc >= 0.3f + chargeS)) {       // the mouth flare only once the breath has started
            sprite(c, 1, mx, my, dp(13f) * mouth, 0.8f * mouth)
            sprite(c, 0, mx, my, dp(6f) * mouth, 0.9f * mouth)
        }
        if (capped) c.restoreToCount(sv)

        drawOverlay(c, w, h)
        c.restore()
        line.color = Color.parseColor("#2C3F6B"); line.strokeWidth = dp(1f)
        c.drawRoundRect(dp(0.5f), dp(0.5f), w - dp(0.5f), h - dp(0.5f), dp(16f), dp(16f), line)
    }

    // the charge-up effect itself is shared with the dragon on the home screen (ChargeFx)
    private var fxCanvas: Canvas? = null
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; blendMode = BlendMode.PLUS }
    private val fxPainter = object : ChargeFx.Painter {
        override fun glow(i: Int, x: Float, y: Float, half: Float, alpha: Float) { sprite(fxCanvas ?: return, (i * 5 + 3) / 7, x, y, half, alpha) }
        override fun halo(x: Float, y: Float, half: Float, alpha: Float) {}
        override fun lines(pts: FloatArray, count: Int, colorIdx: Int, width: Float, alpha: Float) {
            arcPaint.strokeWidth = width
            arcPaint.color = (gradInts[colorIdx.coerceIn(0, 7)] and 0x00FFFFFF) or ((alpha.coerceIn(0f, 1f) * 255f).toInt() shl 24)
            (fxCanvas ?: return).drawLines(pts, 0, count, arcPaint)
        }
    }
    private val chargeFx = ChargeFx(model, fxPainter)

    private fun drawCharge(c: Canvas) {
        val off = chargeS
        if (off <= 0.05f || cyc < 0.3f || (!running && !finishing)) return
        val ft = cyc - (0.3f + off)                       // time since the breath started (negative while charging)
        val u = ((cyc - 0.3f) / off).coerceIn(0f, 1f)
        val fireDur = 1.95f                               // the breath lasts from 0.3 + off to 2.25 + off; after it everything fades out in one second
        fxCanvas = c
        chargeFx.draw(st, st.ds, t, off, u, ft, ft - fireDur, fireDur, ft < 0f, chargeQPct / 100f, thickPct / 100f)
        fxCanvas = null
    }

    /** Frame rate (top left), Play / Stop (top right), Sitting / Flying (bottom left). */
    private fun drawOverlay(c: Canvas, w: Float, h: Float) {
        // frame rate: what the real dragon would use for this Quality
        val full = max(5, Math.round(min(qualityFps.toFloat(), hz)))
        val shown = if (flyPose) full else min(30, full)
        textFill.textAlign = Paint.Align.LEFT; textFill.textSize = dp(10.5f); textFill.color = Color.parseColor("#B8C6E4")
        textFill.setShadowLayer(dp(3f), 0f, dp(1f), Color.argb(200, 0, 0, 0))
        c.drawText("$shown fps", dp(12f), dp(20f), textFill)
        textFill.setShadowLayer(0f, 0f, 0f, 0)
        textFill.textAlign = Paint.Align.CENTER

        // Play / Stop
        val d = dp(34f)
        playRect.set(w - dp(10f) - d, dp(8f), w - dp(10f), dp(8f) + d)
        fill.shader = null
        fill.color = if (looping) Color.argb(240, 240, 99, 79) else Color.argb(235, 46, 196, 182)
        c.drawOval(playRect, fill)
        fill.color = Color.parseColor("#06222B")
        val pcx = playRect.centerX(); val pcy = playRect.centerY()
        if (looping) {
            c.drawRoundRect(pcx - dp(6f), pcy - dp(6f), pcx + dp(6f), pcy + dp(6f), dp(2f), dp(2f), fill)       // Stop
        } else {
            clip.reset(); clip.moveTo(pcx - dp(4.5f), pcy - dp(7f)); clip.lineTo(pcx + dp(7f), pcy); clip.lineTo(pcx - dp(4.5f), pcy + dp(7f)); clip.close()
            c.drawPath(clip, fill)                                                                                  // Play
        }

        // Sitting / Flying
        val pwid = dp(62f); val phgt = dp(26f); val pgap = dp(6f)
        textFill.textSize = dp(11f)
        for (i in 0..1) {
            val x0 = dp(10f) + i * (pwid + pgap); val y0 = h - dp(10f) - phgt
            poseRect[i].set(x0, y0, x0 + pwid, y0 + phgt)
            val on = (i == 1) == flyPose
            fill.color = if (on) Color.argb(235, 46, 196, 182) else Color.argb(150, 40, 56, 96)
            c.drawRoundRect(poseRect[i], phgt / 2f, phgt / 2f, fill)
            textFill.color = if (on) Color.parseColor("#06222B") else Color.parseColor("#B8C6E4")
            c.drawText(if (i == 0) "Sitting" else "Flying", x0 + pwid / 2f, y0 + phgt / 2f + dp(4f), textFill)
        }
    }

    private var downHit = -1                 // 0 sitting, 1 flying, 2 play

    private fun hit(x: Float, y: Float): Int =
        if (poseRect[0].contains(x, y)) 0 else if (poseRect[1].contains(x, y)) 1 else if (playRect.contains(x, y)) 2 else -1

    /** Only the three buttons take touches; the rest of the box lets the page scroll. */
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val h = hit(e.x, e.y)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downHit = h; return h >= 0 }
            MotionEvent.ACTION_UP -> {
                val h0 = downHit; downHit = -1
                if (h0 >= 0 && h0 == h) {
                    when (h0) { 0 -> setPose(false); 1 -> setPose(true); else -> togglePlay() }
                    return true
                }
                return h0 >= 0
            }
            MotionEvent.ACTION_CANCEL -> { downHit = -1; return false }
        }
        return downHit >= 0
    }
}
