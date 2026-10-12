package com.ramim.homedragon

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.Choreographer
import android.view.View
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.math.sin
import kotlin.random.Random

private const val TAU = (2.0 * PI).toFloat()

/** Safety only: if the scan that the dragon waits for after a swipe or a return home never arrives, it fades back in after this long anyway. */
private const val SCAN_WAIT_MAX_MS = 2500L

/** The dragon appears this long after the scan has finished. */
private const val APPEAR_AFTER_SCAN_MS = 400L

/** The dragon next to the bug (v4.19), phases and their lengths in seconds. Normal run 4.3 s; when the bug fakes its death about 6.3 s; STALK (widgets only) comes first when the bug is far. */
private const val FP_NOTICE = 0
private const val FP_FLAME = 1
private const val FP_CHECK = 2
private const val FP_REVIVE = 3
private const val FP_CHASE = 4
private const val FP_PICK = 5
private const val FP_EAT = 6
private const val FP_STALK = 7              // widgets and big folders: the dragon runs along until the bug is within reach
private const val D_NOTICE = 0.3f           // the head turns to the bug (the 1 to 3 s wait before it is the bug timer)
private const val D_FLAME = 0.6f             // the small flame burns this long; the bug is hit 0.2 s in
private const val HIT_AT = 0.2f
private const val D_CHECK = 0.6f
private const val D_CHECK_FAKE = 0.3f        // a shorter check before the bug comes back to life
private const val D_REVIVE = 0.3f
private const val D_CHASE = 0.9f
private const val D_PICK = 0.8f
private const val PICK_BITE = 0.4f
private const val D_EAT = 2.0f
private const val EAT_LICK = 1.6f
private const val REVIVE_CHANCE = 0.30f
/**
 * The bug's ground is the top edge of an icon or widget, never the gap. Beside the dragon it keeps to the strip in front of the front feet:
 * BUG_FRONT model units from the dragon, never narrower than BUG_MIN_ROOM.
 */
private const val BUG_FRONT = 34f
private const val BUG_MIN_ROOM = 14f
private const val BUG_SPEED = 85f            // model units per second while darting
private const val BUG_FLEE_SPEED = 140f
private const val BUG_EDGE_MARGIN = 4f       // px kept from the end of the ground, at the very least
/** Footing (v4.21), measured on the phone: icon corner radius about 4% of the screen width (49 px of 1220), icon picture about 16% of it. */
private const val CORNER_FRAC = 0.04f
private const val PIC_FRAC = 0.16f
private const val FOOT_REACH = 44f           // the hind feet are the farthest from the origin (front feet 30 ahead, hind 44 behind), model units
private const val BUG_REACH = 80f            // on a widget the dragon runs until the bug is this close (model units)
private const val BUG_LAND_S = 0.14f         // the small squash when it lands
private const val BUG_REJUMP = 0.6f          // chance that the bug jumps on again when the dragon lands next to it
private const val BUG_SPAWN_MIN = 20f        // it appears at a random time, every 45 s on average (dragon awake and idle)
private const val BUG_SPAWN_MAX = 70f
private const val BUG_ALIVE = 0
private const val BUG_DEAD = 1
private const val BUG_FLIP = 2
private const val BUG_JUMP = 3

/** Body rise / sink with each wing flap, in model units (about 13% of the wing length), and the nose-up tilt on the upstroke in radians (about 4.5 degrees). */
private const val FLAP_BOB = 9f
private const val FLAP_TILT = 0.08f

/** Fade-in time of every appearance (eased in onDraw). */
private const val FADE_IN_S = 0.4f
private fun rnd(a: Float, b: Float) = a + Random.nextFloat() * (b - a)
private fun clampF(v: Float, a: Float, b: Float) = max(a, min(b, v))
private fun lerp3(a: Float, b: Float, t: Float) = a + (b - a) * t
private fun deg(r: Float) = r * 57.29578f

/**
 * Behaviour and effects. Icons and widgets are the only ground: the dragon perches on one, then either jumps
 * to a neighbouring icon, flies a free random path through the screen and lands on an icon, or
 * hovers and breathes blue fire at an icon (which gets scorched and slowly cools down). Now and then a bug
 * appears on an icon or widget, wanders and jumps about; the dragon notices it, follows it and burns it with a small flame.
 *
 * Frames come from Choreographer. pause() removes the callback and keeps everything in memory.
 */
class DragonView(context: Context) : View(context) {

    private enum class Mode { IDLE, CROUCH, FLY, FIRE, WALK, SLEEP, SCRATCH, FORAGE }

    private class Icon(val r: RectF, val row: Int) {
        var burn = 0f; var heat = 0f; var ember = 0f; var sm = 0f; var lk = 0f; var em = 0f
        var big = false          // widget or large folder: bigger ground the dragon can crawl on
        val scorch = Array(4) { floatArrayOf(rnd(-.28f, .28f), rnd(-.28f, .28f), rnd(.28f, .5f), rnd(0f, TAU)) }
        val cx: Float get() = r.centerX()
        val cy: Float get() = r.centerY()
    }

    private class FlightPath(val x: FloatArray, val y: FloatArray, val cum: FloatArray) {
        val len: Float get() = cum[cum.size - 1]
    }

    // ---------- icons ----------
    private var icons: List<Icon> = emptyList()
    private var size = 120f
    private var sc = 2f
    private var ds = 1.2f
    private var flapAmp = 1f                 // 1 flying, 0.6 breathing fire (smoothed)
    private var offX = 0
    private var offY = 0
    private var usingRegistry = false
    private var placed = false

    // ---------- dragon ----------
    private val model = DragonModel()
    private val hl = FloatArray(2)
    private val st = DragonModel.State()
    private var mode = Mode.IDLE
    private var air = false
    private var iconIdx = 0
    private var faceT = 1f
    private var t = 0f
    private var dur = 1f
    private var path: FlightPath? = null
    private var then: (() -> Unit)? = null
    private var timer = 1.5f
    private var blinkT = 0f
    private var nextBlink = 2f
    private var look = 0f
    private var lookT = 0f
    private var nextLook = 2f
    private var target = 0
    private var acc = 0f
    private var walkTo = 0f
    private var fireDur = 1.7f
    private var chargeThick = 1f           // Charge-up wave thickness slider
    private var chargeQ = 1f            // Charge-up quality slider: scales the sparks, imploding particles and waves of the charge-up
    private var chargeT = 1.5f            // charge-up before every fire breath (seconds, "Charge-up time" slider, 0 = off): tail tip -> spine spikes -> neck -> orb in the mouth
    private var fireFt = -1f              // time since the breath itself started (negative while charging)
    private var sleepPhase = 0          // 0 lying down, 1 asleep, 2 getting up
    private var sleepDur = 4f           // seconds asleep (never more than 6)
    private var sleepAfterWalk = false  // a few steps first, then lie down
    private var scratchDur = 2.5f
    private var fgDir = 1f                 // found-something routine: facing (the way the bug is), the bug's spot, flame and crumb bookkeeping
    private var fgSpotX = 0f
    private var fgSpotY = 0f
    private var fgAcc = 0f
    private var fgPhase = 0
    private var fgT = 0f                   // clock of the current phase
    private var fgWillRevive = false
    private var fgRevived = false
    private var fgHit = false
    private var fgAimX = 0f                // where the small flame aims: the bug's position with a tiny lag
    private var fgAimY = 0f
    private var bugOn = false
    private var bugSt = BUG_ALIVE
    private var bugT = 0f                  // clock of the bug's state
    private var bugIcon = 0
    private var bugGoalX = 0f              // darting goal (world x), pause left, facing (+1 right), moving, fleeing, running away from the dragon
    private var bugPause = 0f
    private var bugDir = 1f
    private var bugMoving = false
    private var bugFlee = false
    private var bugFwd = true
    private var bugWx = 0f; private var bugWy = 0f               // world position on the ground
    private var bugAirX = 0f; private var bugAirY = 0f           // world position while it jumps
    private var bugJx0 = 0f; private var bugJy0 = 0f; private var bugJx1 = 0f; private var bugJy1 = 0f
    private var bugJumpIcon = 0
    private var bugJumpSide = 1f
    private var bugJumpDur = 0.35f
    private var bugSide = 1f               // which side of the dragon the bug keeps to while the dragon stands on its icon
    private var bugAge = 0f
    private var bugSpawnT = 20f            // seconds (awake and idle) until the next bug appears
    private var bugNoticeT = 2f            // idle seconds until the dragon notices the bug: 1 to 3
    private var bugJumpT = 3f              // seconds until the bug jumps to another icon or widget by itself
    private var huntOn = false             // the dragon is after the bug (flying to it, or next to it)
    private var stalkNow = false           // the dragon is running along a widget after the bug
    private var padX = 0f                  // the finder's rectangle of a widget or big folder is wider than the visible card: this much on each side (see updatePad)
    private var wbLo = 0f; private var wbHi = 0f                 // where the dragon may stand on a big surface, world x (see walkBounds)
    private var lastGroundLog = ""
    private var huntJumps = 0              // jumps the bug made since the dragon noticed it, and when it noticed (for the Log)
    private var huntT0 = 0L
    private var rgLo = 0f; private var rgHi = 0f                 // the bug's ground, world x (see bugRangeFor)
    private var bugSmoke = 0f
    private var bugH = 0f; private var bugS = 0.8f; private var bugV = 0.8f      // the bug's colour (hue, saturation, value): new every time it appears
    private val hsv = FloatArray(3)
    private val bugFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val bugEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 0.8f; strokeCap = Paint.Cap.ROUND }
    private val bugLeg = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 0.9f; strokeCap = Paint.Cap.ROUND }
    private val bugR = RectF()
    private val bugMark = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }                       // spots on the back
    private val bugShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.BLACK }
    private val bugEye = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.rgb(20, 16, 18) }
    private val bugGlint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.WHITE }
    private val bugWing = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.argb(95, 255, 255, 255) }
    private val bugShine = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.argb(110, 255, 255, 255) }
    private var bugPat = 0                 // pattern on its back: 0 plain, 1 stripes, 2 spots (new every time it appears)
    private var bugLandT = 0f              // squash after a landing, seconds left
    private var fgCrumb = 0
    private val hl2 = FloatArray(2)
    private val crX = FloatArray(8); private val crY = FloatArray(8); private val crVx = FloatArray(8); private val crVy = FloatArray(8); private val crAge = FloatArray(8)
    private var ncr = 0
    private val crumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.rgb(236, 226, 205) }
    private val spot = FloatArray(2)    // sleeping place: [facing, x]
    private var vx = 0f
    private var vy = 0f
    private var headGoal = 0.3f
    private var time = 0f
    private val mp = FloatArray(2)
    private val pp = FloatArray(2)

    // ---------- particles (parallel arrays) ----------
    private val maxF = 200
    private val fX = FloatArray(maxF); private val fY = FloatArray(maxF)
    private val fVx = FloatArray(maxF); private val fVy = FloatArray(maxF)
    private val fPx = FloatArray(maxF); private val fPy = FloatArray(maxF)
    private val fAge = FloatArray(maxF); private val fLife = FloatArray(maxF)
    private val fSize = FloatArray(maxF); private val fSeed = FloatArray(maxF); private val fKind = IntArray(maxF)
    private var nf = 0

    private val maxS = 60
    private val sX = FloatArray(maxS); private val sY = FloatArray(maxS)
    private val sVx = FloatArray(maxS); private val sVy = FloatArray(maxS)
    private val sAge = FloatArray(maxS); private val sLife = FloatArray(maxS); private val sSize = FloatArray(maxS)
    private val sRot = FloatArray(maxS); private val sRv = FloatArray(maxS); private val sLit = FloatArray(maxS)
    private val sSeed = FloatArray(maxS); private val sVar = IntArray(maxS)
    private var ns = 0

    private val maxP = 90
    private val kX = FloatArray(maxP); private val kY = FloatArray(maxP)
    private val kVx = FloatArray(maxP); private val kVy = FloatArray(maxP)
    private val kAge = FloatArray(maxP); private val kLife = FloatArray(maxP); private val kG = FloatArray(maxP)
    private var nk = 0

    // ---------- paints and sprites ----------
    private val spritePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val plusPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { blendMode = BlendMode.PLUS }
    private val sparkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; blendMode = BlendMode.PLUS }
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)     // normal blend: dark soft halo behind the charge-up glow
    private val dest = RectF()
    private val clip = Path()

    private fun glow(r: Int, g: Int, b: Int): Bitmap {
        val bmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = RadialGradient(
            32f, 32f, 32f,
            intArrayOf(Color.argb(255, r, g, b), Color.argb(199, r, g, b), Color.argb(71, r, g, b), Color.argb(0, r, g, b)),
            floatArrayOf(0f, 0.25f, 0.55f, 1f), Shader.TileMode.CLAMP
        )
        Canvas(bmp).drawRect(0f, 0f, 64f, 64f, p)
        return bmp
    }

    // ---- value-noise textures (built once, so drawing stays as cheap as before) ----
    private fun hashf(x: Int, y: Int, sd: Int): Float {
        var h = x * 374761393 + y * 668265263 + sd * 1442695041
        h = (h xor (h ushr 13)) * 1274126177
        h = h xor (h ushr 16)
        return (h and 0xFFFF) / 65535f
    }

    private fun vnoise(x: Float, y: Float, sd: Int): Float {
        val xi = floor(x).toInt(); val yi = floor(y).toInt()
        val xf = x - xi; val yf = y - yi
        val u = xf * xf * (3f - 2f * xf); val v = yf * yf * (3f - 2f * yf)
        val a = hashf(xi, yi, sd); val b = hashf(xi + 1, yi, sd)
        val c = hashf(xi, yi + 1, sd); val d = hashf(xi + 1, yi + 1, sd)
        return a + (b - a) * u + (c - a) * v + (a - b - c + d) * u * v
    }

    private fun fbm(x0: Float, y0: Float, sd: Int): Float {
        var x = x0; var y = y0; var t = 0f; var amp = 0.5f; var n = 0f
        for (o in 0 until 4) { t += vnoise(x, y, sd + o) * amp; n += amp; amp *= 0.5f; x = x * 2.03f + 17.1f; y = y * 2.03f + 9.7f }
        return t / n
    }

    private fun smooth(a: Float, b: Float, x: Float): Float {
        val t = clampF((x - a) / (b - a), 0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /** Wispy flame blob: soft falloff broken up by warped noise, hot core brightened. */
    private fun flameSprite(r: Int, g: Int, b: Int, sd: Int): Bitmap {
        val n = 96
        val px = IntArray(n * n)
        for (y in 0 until n) for (x in 0 until n) {
            val dx = (x + 0.5f) / n * 2f - 1f; val dy = (y + 0.5f) / n * 2f - 1f
            val wx = dx + (fbm(x / 18f, y / 18f, sd) - 0.5f) * 0.55f
            val wy = dy + (fbm(x / 18f + 31f, y / 18f + 7f, sd + 9) - 0.5f) * 0.55f
            val d = sqrt(wx * wx + wy * wy)
            val base = smooth(1f, 0f, d).pow(1.6f)
            val nn = fbm(x / 11f + sd, y / 11f, sd + 3)
            val a = clampF(base * (0.82f + 0.9f * (nn - 0.5f)), 0f, 1f)
            val hot = smooth(0.55f, 0f, d) * smooth(0.3f, 0.7f, nn)
            val cr = min(255f, r + (255 - r) * 0.35f * hot).toInt()
            val cg = min(255f, g + (255 - g) * 0.35f * hot).toInt()
            val cb = min(255f, b + (255 - b) * 0.35f * hot).toInt()
            px[y * n + x] = Color.argb((a * 255f).toInt(), cr, cg, cb)
        }
        val bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
        bmp.setPixels(px, 0, n, 0, 0, n, n)
        return bmp
    }

    /** Billowy smoke puff with a lit top-left edge and a darker underside. */
    private fun puff(r: Int, g: Int, b: Int, al: Float, sd: Int): Bitmap {
        val n = 128
        val dens = FloatArray(n * n)
        for (y in 0 until n) for (x in 0 until n) {
            val dx = (x + 0.5f) / n * 2f - 1f; val dy = (y + 0.5f) / n * 2f - 1f
            val wx = dx + (fbm(x / 26f, y / 26f, sd) - 0.5f) * 0.7f
            val wy = dy + (fbm(x / 26f + 5f, y / 26f + 3f, sd + 7) - 0.5f) * 0.7f
            val d = sqrt(wx * wx + wy * wy)
            val v = smooth(1f, 0.2f, d) * (0.45f + 0.75f * fbm(x / 14f, y / 14f, sd + 2))
            dens[y * n + x] = 1f - kotlin.math.exp(-1.6f * v)
        }
        val px = IntArray(n * n)
        for (y in 0 until n) for (x in 0 until n) {
            val dv = dens[y * n + x]
            val o = dens[max(0, y - 3) * n + max(0, x - 3)]
            val lit = clampF(1f + 2.2f * (dv - o), 0.7f, 1.6f)
            px[y * n + x] = Color.argb(
                (clampF(dv * al * 1.8f, 0f, 1f) * 255f).toInt(),
                min(255, (r * lit).toInt()), min(255, (g * lit).toInt()), min(255, (b * lit).toInt())
            )
        }
        val bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
        bmp.setPixels(px, 0, n, 0, 0, n, n)
        return bmp
    }

    // smooth glows: scorch marks, icon halos, muzzle flash
    private var sCore = glow(238, 249, 255)
    private var sCyan = glow(95, 214, 255)
    private var sBlue = glow(48, 110, 255)
    private var sHalo = glow(14, 10, 60)
    // gradient of the flame colours for the charge-up: index 0 = hot core .. 7 = cool tip
    private fun gradCol(cols: IntArray, i: Int): Int {
        val col = FlameColors.at(cols, i / 7f * 85f)
        return if (i == 0) FlameColors.lighten(col, 0.75f) else col
    }
    private var chCol = IntArray(8) { gradCol(FlameColors.BLUE, it) }
    private var sGrad = Array(8) { glow(Color.red(chCol[it]), Color.green(chCol[it]), Color.blue(chCol[it])) }
    // wispy flame particles, two noise variants per colour stage
    private var fCore = Array(2) { flameSprite(238, 249, 255, 11 + it * 5) }
    // one white flame sprite per variant, tinted along a smooth colour ramp with a colour filter
    private val fWhite = Array(2) { flameSprite(255, 255, 255, 11 + it * 5) }
    private val rampStops = arrayOf(
        intArrayOf(0, 215, 240, 255), intArrayOf(18, 120, 225, 255), intArrayOf(45, 55, 130, 255),
        intArrayOf(75, 78, 60, 230), intArrayOf(100, 42, 24, 150)
    )
    private var ramp = Array(16) { k ->
        val t = k / 15f * 100f
        var j = 0
        while (j < rampStops.size - 2 && t > rampStops[j + 1][0]) j++
        val a = rampStops[j]; val b = rampStops[j + 1]
        val u = clampF((t - a[0]) / (b[0] - a[0]).toFloat(), 0f, 1f)
        PorterDuffColorFilter(
            Color.rgb((a[1] + (b[1] - a[1]) * u).toInt(), (a[2] + (b[2] - a[2]) * u).toInt(), (a[3] + (b[3] - a[3]) * u).toInt()),
            PorterDuff.Mode.SRC_IN
        )
    }
    // soft white-hot patches (brightest in the middle, nothing at the edges): tinted by the cooling colour
    private fun heatTex(sd: Int): Array<Bitmap> {
        val n = 96
        fun layer(seed: Int): Bitmap {
            val px = IntArray(n * n)
            for (y in 0 until n) for (x in 0 until n) {
                val dx = (x + 0.5f) / n * 2f - 1f; val dy = (y + 0.5f) / n * 2f - 1f
                val centre = smooth(1.05f, 0.05f, max(abs(dx), abs(dy)) * 0.55f + sqrt(dx * dx + dy * dy) * 0.6f)
                val wx = x + (fbm(x / 22f, y / 22f, seed + 4) - 0.5f) * 16f
                val wy = y + (fbm(x / 22f + 9f, y / 22f, seed + 5) - 0.5f) * 16f
                val v = fbm(wx / 12f, wy / 12f, seed + 1)
                val al = clampF(centre * (0.35f + 1.1f * smooth(0.3f, 0.8f, v)), 0f, 1f)
                px[y * n + x] = Color.argb((al * 255f).toInt(), 255, 255, 255)
            }
            val b = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
            b.setPixels(px, 0, n, 0, 0, n, n)
            return b
        }
        return arrayOf(layer(sd), layer(sd + 40))
    }
    private val burnTexs = Array(3) { heatTex(5 + it * 7) }
    private val smkDark = Array(3) { puff(34, 36, 44, 0.34f, 21 + it * 4) }
    private val smkMid = Array(3) { puff(112, 118, 130, 0.3f, 21 + it * 4) }
    private var smkBlue = Array(2) { puff(70, 120, 200, 0.34f, 21 + it * 4) }

    // ---------- flame colours (picked in the app; the default is Fire, the original blue is one of the palettes) ----------
    private var flameApplied: IntArray = FlameColors.BLUE.copyOf()      // what the sprites below were built for (the blue flame, until reloadFlame runs)

    /** Rebuild the colour ramp and tinted glows when the colours chosen in the app changed. */
    fun reloadFlame() {
        val cols = Prefs.flameColors(context)
        if (FlameColors.same(cols, flameApplied)) return
        flameApplied = cols
        chCol = IntArray(8) { gradCol(cols, it) }
        sGrad = Array(8) { glow(Color.red(chCol[it]), Color.green(chCol[it]), Color.blue(chCol[it])) }
        ramp = Array(16) { k -> PorterDuffColorFilter(FlameColors.at(cols, k / 15f * 100f), PorterDuff.Mode.SRC_IN) }
        if (FlameColors.isBlue(cols)) {
            sCore = glow(238, 249, 255); sCyan = glow(95, 214, 255); sBlue = glow(48, 110, 255)
            sHalo = glow(14, 10, 60)
            model.setFlameGlow(Color.rgb(220, 245, 255), Color.rgb(70, 170, 255), Color.rgb(30, 80, 255))
            fCore = Array(2) { flameSprite(238, 249, 255, 11 + it * 5) }
            smkBlue = Array(2) { puff(70, 120, 200, 0.34f, 21 + it * 4) }
        } else {
            val core = FlameColors.lighten(FlameColors.at(cols, 0f), 0.75f)
            val near = FlameColors.at(cols, 18f); val mid = FlameColors.at(cols, 50f)
            sCore = glow(Color.red(core), Color.green(core), Color.blue(core))
            sCyan = glow(Color.red(near), Color.green(near), Color.blue(near))
            sBlue = glow(Color.red(mid), Color.green(mid), Color.blue(mid))
            val edge = FlameColors.at(cols, 100f)
            sHalo = glow(Color.red(edge) * 2 / 5, Color.green(edge) * 2 / 5, Color.blue(edge) * 2 / 5)
            model.setFlameGlow(core, near, mid)
            fCore = Array(2) { flameSprite(Color.red(core), Color.green(core), Color.blue(core), 11 + it * 5) }
            val sm = FlameColors.lighten(mid, 0.1f)
            smkBlue = Array(2) { puff(Color.red(sm) * 3 / 4, Color.green(sm) * 3 / 4, Color.blue(sm) * 3 / 4, 0.34f, 21 + it * 4) }
        }
        invalidate()
    }

    init { reloadFlame() }

    // ---------- page swipe fade ----------
    private var fade = 1f
    private var fadeGoal = 1f
    private var swipeState = 0       // 0 idle, 1 swiping, 2 stopped moving: waiting for the scan of the new page (see onScanDone)
    private var swipeLast = 0L       // the last moment the screen was moving (uptime ms)
    private var appearAt = 0L        // uptime ms when the dragon may fade in (first scan done + 0.4 s); 0 = no scan has finished yet

    private var hiddenByApp = false
    private var hiddenNotified = false
    /** Called once the dragon has faded out because an app (not the home screen) is in front. */
    var onFullyHidden: (() -> Unit)? = null

    /** false while another app is in front: fade out, then the service stops the frame loop. */
    fun setShown(shown: Boolean) {
        if (shown == !hiddenByApp) return
        hiddenByApp = !shown
        hiddenNotified = false
        if (shown) {
            // back on the home screen: wait for the first scan (0.5 s of quiet), take its icon layout, wait 0.4 s more, then fade in (see applyIcons, onScanDone)
            swipeState = 2; swipeLast = SystemClock.uptimeMillis(); fadeGoal = 0f; appearAt = 0L
        } else {
            appearAt = 0L
        }
    }

    /** The icon finder has finished a scan: if the dragon was waiting for it (after a swipe or a return home) it fades in now, with no further delay. */
    fun onScanDone() {
        if (swipeState == 2 && appearAt == 0L) appearAt = SystemClock.uptimeMillis() + APPEAR_AFTER_SCAN_MS      // only the first scan starts the wait
    }

    /** The launcher is scrolling sideways: fade out fast. */
    fun onSwipe() {
        swipeLast = SystemClock.uptimeMillis()
        appearAt = 0L                                      // a swipe cancels the wait
        if (swipeState != 1) { swipeState = 1; fadeGoal = 0f }
    }

    // ---------- frame loop ----------
    private var running = false
    private var lastNs = 0L
    private val prevBox = Rect()
    private val curBox = Rect()

    /** true when the last callback was posted for the very next vsync (so the spacing of the next two callbacks is one vsync) */
    private var chain = false
    private var dueNs = 0L          // when the next frame is due (nanoseconds, vsync clock)

    private val kick = Runnable {
        if (running) { chain = false; Choreographer.getInstance().postFrameCallback(frame) }
    }

    /** Wait for the next wanted frame. Long waits sleep on a timer, so the CPU is not woken on every vsync. */
    private fun scheduleNext(remaining: Float) {
        if (remaining > 2.5f * vsyncDt) {
            chain = false
            postDelayed(kick, ((remaining - 0.5f * vsyncDt) * 1000f).toLong())
        } else {
            chain = true
            Choreographer.getInstance().postFrameCallback(frame)
        }
    }

    private val frame: Choreographer.FrameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            // measure the real vsync spacing (only from callbacks that were posted for consecutive vsyncs)
            if (chain && lastVsyncNs != 0L) {
                val d = (frameTimeNanos - lastVsyncNs) / 1e9f
                if (d in 0.0035f..0.04f) vsyncDt += (d - vsyncDt) * 0.05f
            }
            lastVsyncNs = frameTimeNanos
            if (lastNs != 0L && frameTimeNanos < dueNs - 300_000L) {
                scheduleNext((dueNs - frameTimeNanos) / 1e9f)          // not due yet: nothing drawn
                return
            }
            val dt = if (lastNs == 0L) 0f else min(0.15f, (frameTimeNanos - lastNs) / 1e9f)
            lastNs = frameTimeNanos
            if (dt > 0f) {
                step(dt)
                invalidateDirty()
            }
            // completely sitting (sitting animation finished, no fire, glow or smoke) for 2 frames -> drop to 30 fps at any Quality;
            // any activity brings the full rate back at once
            if (idleNow) { if (idleFrames < 1000) idleFrames++ } else idleFrames = 0
            restCalm = idleFrames >= SIT_FRAMES
            rateNow = pickRate()
            val wantNs = (1e9f / rateNow).toLong()
            // keep the average rate exact: the next due time advances by one interval (a vsync is skipped now and then)
            dueNs = if (dueNs == 0L || frameTimeNanos - dueNs > wantNs) frameTimeNanos + wantNs else dueNs + wantNs
            scheduleNext((dueNs - frameTimeNanos) / 1e9f)
        }
    }

    fun resume() {
        if (running) return
        running = true
        lastNs = 0L
        lastVsyncNs = 0L
        dueNs = 0L; idleFrames = 0; restCalm = false
        try { display?.refreshRate?.let { if (it in 30f..165f) vsyncDt = 1f / it } } catch (e: Throwable) {}
        rateNow = pickRate()
        chain = false
        Choreographer.getInstance().postFrameCallback(frame)
    }

    fun pause() {
        running = false
        removeCallbacks(kick)
        Choreographer.getInstance().removeFrameCallback(frame)
    }

    // ---------- layout and icons ----------
    override fun onLayout(changed: Boolean, l: Int, top: Int, r: Int, b: Int) {
        super.onLayout(changed, l, top, r, b)
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        offX = loc[0]
        offY = loc[1]
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        if (!usingRegistry) applyIcons(fallbackRects())
    }

    fun reloadPrefs() {
        loadSettings()
        if (!usingRegistry) applyIcons(fallbackRects()) else applyScale()
    }

    /** Called with screen-pixel rectangles from the icon finder. Empty list means use the grid fallback. */
    fun setIcons(screenRects: List<RectF>) {
        if (screenRects.isEmpty()) {
            usingRegistry = false
            if (width > 0) applyIcons(fallbackRects())
            return
        }
        usingRegistry = true
        applyIcons(screenRects.map { RectF(it.left - offX, it.top - offY, it.right - offX, it.bottom - offY) })
    }

    private fun fallbackRects(): List<RectF> {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return emptyList()
        val cols = Prefs.cols(context)
        val rows = Prefs.rows(context)
        val top = h * 0.10f
        val bottom = h * 0.84f
        val cw = w / cols
        val ch = (bottom - top) / rows
        val s = min(cw * 0.62f, ch * 0.55f)
        val list = ArrayList<RectF>()
        for (r in 0 until rows) for (c in 0 until cols) {
            val cx = (c + 0.5f) * cw
            val yy = top + r * ch + (ch - s * 1.25f) / 2f
            list.add(RectF(cx - s / 2, yy, cx + s / 2, yy + s))
        }
        for (c in 0 until cols) {
            val cx = (c + 0.5f) * cw
            list.add(RectF(cx - s / 2, h * 0.89f, cx + s / 2, h * 0.89f + s))
        }
        return list
    }

    private fun medianWidth(list: List<Float>): Float {
        val s = list.sorted()
        return s[s.size / 2]
    }

    // ---------- user settings (percent sliders in the app) ----------
    private var pq = 1f             // particle quality 0.1..1 (fire, smoke, sparks), separate from the frame-rate Quality
    private var fpsWanted = 120     // Quality slider: frames per second
    private var fpsTop = 120        // the screen's top refresh rate (the Quality slider's last stop)
    private var spdMul = 1f         // dragon speed 0.5..1.5
    private var capF = 200
    private var capS = 60
    private var capP = 90
    // frame pacing: the same rate in every state = screen refresh rate x Quality (100% -> 120 fps, 50% -> 60 fps, 10% -> 12 fps)
    private var rateNow = 120f
    private var idleFrames = 0
    private var restCalm = false
    private val SIT_FRAMES = 2
    private var idleNow = false
    private var vsyncDt = 1f / 60f
    private var lastVsyncNs = 0L

    private fun loadSettings() {
        fpsWanted = Prefs.qualityFps(context); fpsTop = FpsStops.topHz(context)
        pq = clampF(Prefs.particlePct(context) / 100f, 0.1f, 1f)
        chargeT = Prefs.chargeTenths(context) / 10f
        chargeQ = clampF(Prefs.chargeQualityPct(context) / 100f, 0.1f, 1f)
        chargeThick = clampF(Prefs.chargeThickPct(context) / 100f, 0.5f, 2f)
        spdMul = clampF(Prefs.speedPct(context) / 100f, 0.5f, 1.5f)
        // transparency: 0 = solid .. 100 = barely visible; the model draws the whole dragon as one fading layer
        val body = Prefs.transparencyPct(context)
        model.setTransparency(body, Prefs.wingOf(body))      // the wing skin is 10 points more see-through
        capF = (70 + 130 * pq).toInt().coerceIn(70, maxF)
        capS = (20 + 40 * pq).toInt().coerceIn(20, maxS)
        capP = (30 + 60 * pq).toInt().coerceIn(30, maxP)
        rateNow = pickRate()
        reloadFlame()
    }

    /** Frames per second wanted right now: the Quality slider's rate (every vsync at its top stop), the same in every state. */
    private fun pickRate(): Float {
        val hz = clampF(1f / vsyncDt, 30f, 165f)
        val base = if (fpsWanted >= fpsTop || fpsWanted >= hz * 0.995f) 1000f else fpsWanted.toFloat()     // the top stop = every vsync, no throttling
        // walking never needs more than 60 fps; fly, jump and fire keep the full rate
        val moving = if (mode == Mode.WALK) min(base, 60f) else base
        return if (restCalm) min(moving, 30f) else moving
    }

    /** Quality or speed slider moved: nothing to re-layout. */
    fun reloadSettings() = loadSettings()

    // The dragon's size follows the normal app icon size and stays the same on every home screen page.
    private var lockSize = 0f
    private var lockStreak = 0
    private var lockW = 0
    private var lockH = 0

    /** Width of a normal app icon on this page: a low percentile, so widgets and big folders do not pull it up. */
    private fun iconWidth(list: List<Float>): Float {
        val s = list.sorted()
        return s[(s.size * 0.4f).toInt().coerceIn(0, s.size - 1)]
    }

    private var savedIconPx = 0f

    /** Remembers the normal icon size (and icons per row) for the preview box on the main screen: it draws its dummy icons that size. Written only when it changed. */
    private fun saveIconSize() {
        if (size <= 0f || width <= 0 || abs(size - savedIconPx) < 1f) return
        savedIconPx = size
        val cols = Math.round(width / (size / 0.73f)).coerceIn(3, 7)         // the icon cell is about 1/0.73 of the icon picture wide
        Prefs.setIconSize(context, size, cols)
    }

    private fun applyScale() {
        loadSettings()
        val m = iconWidth(icons.map { it.r.width() })
        if (lockSize <= 0f || width != lockW || height != lockH) {
            lockSize = m; lockStreak = 0; lockW = width; lockH = height            // first page, or the screen changed (rotation, resize)
        } else if (abs(m - lockSize) > lockSize * 0.08f) {
            if (++lockStreak >= 2) { lockSize = m; lockStreak = 0 }                 // two scans in a row differ: the icon size really is another (the first scan can come during an animation)
        } else lockStreak = 0
        size = lockSize
        saveIconSize()
        for (c in icons) c.big = c.r.width() > size * 1.5f
        updatePad()
        sc = size / 58f
        ds = sc * 0.55f * (Prefs.scalePct(context) / 100f)
        st.ds = ds
    }

    private fun applyIcons(rects: List<RectF>) {
        if (rects.isEmpty()) return
        if (swipeState == 1) return          // mid-swipe layouts are half off screen: ignore them
        val settling = swipeState == 2
        val sorted = rects.sortedWith(compareBy({ it.centerY() }, { it.centerX() }))
        // The icon finder reports the same layout many times, so skip identical lists.
        if (sorted.size == icons.size && sorted.indices.all {
                abs(sorted[it].left - icons[it].r.left) < 1.5f && abs(sorted[it].top - icons[it].r.top) < 1.5f
            }
        ) {
            return                                             // same page again: it fades back in where it was, as soon as the scan is done (onScanDone)
        }

        val avg = medianWidth(rects.map { it.width() })
        var row = 0
        var rowCy = sorted[0].centerY()
        val newIcons = ArrayList<Icon>()
        var matched = 0
        for (r in sorted) {
            if (r.centerY() - rowCy > avg * 0.5f) { row++; rowCy = r.centerY() }
            val ic = Icon(RectF(r), row)
            icons.firstOrNull { abs(it.cx - ic.cx) < avg * 0.3f && abs(it.cy - ic.cy) < avg * 0.3f }?.let {
                ic.burn = it.burn; ic.heat = it.heat; ic.ember = it.ember
                matched++
            }
            newIcons.add(ic)
        }
        icons = newIcons
        applyScale()
        val pageChanged = placed && icons.size >= 2 && matched < newIcons.size * 0.4f
        run {
            val ni0 = icons.firstOrNull { !it.big }; val wi0 = icons.firstOrNull { it.big }
            val gl = "Ground: icon " + (ni0?.let { "${it.r.width().toInt()}x${it.r.height().toInt()}" } ?: "-") + " px, widget " +
                (wi0?.let { "${it.r.width().toInt()}x${it.r.height().toInt()}" } ?: "-") + " px, screen $width px, dragon size ${size.toInt()} px, 1 unit = " + "%.2f".format(ds) + " px, corner " + (ni0?.let { cornerR(it).toInt() } ?: 0) + " px, card inset ${padX.toInt()} px"
            if (gl != lastGroundLog) { lastGroundLog = gl; Diag.log(context, gl) }
        }
        if (bugOn) {
            if (!placed || settling || pageChanged) endBug()                // another page: the bug stays behind
            else { bugIcon = nearestIcon(bugWx, bugWy); bugJumpIcon = nearestIcon(bugJx1, bugJy1) }
        }
        if (!placed) {
            placed = true
            land(Random.nextInt(icons.size), true)
        } else if (settling || pageChanged) {
            // A different home screen: the dragon appears on one of its icons with a fade-in.
            nf = 0; ns = 0; nk = 0
            land(Random.nextInt(icons.size), true)
            if (!settling) { fade = 0f; fadeGoal = 1f }         // launcher sent no scroll events: still hide the jump
        } else if (mode == Mode.IDLE || mode == Mode.CROUCH || mode == Mode.SLEEP || mode == Mode.SCRATCH || mode == Mode.FORAGE) {
            val ni = nearestIcon(st.x, st.y)
            val nc = ic(ni)
            if (nc.big) walkBounds(nc)
            land(ni, true, if (nc.big) clampF(st.x, wbLo, max(wbLo + 1f, wbHi)) else null)
        }
    }

    private fun nearestIcon(px: Float, py: Float): Int {
        var best = 0
        var bd = Float.MAX_VALUE
        for (i in icons.indices) {
            val r = icons[i].r
            val d = hypot(max(0f, max(r.left - px, px - r.right)), max(0f, max(r.top - py, py - r.bottom)))
            if (d < bd) { bd = d; best = i }
        }
        return best
    }

    private fun ic(i: Int) = icons[i.coerceIn(0, icons.size - 1)]
    private fun sitX(i: Int) = ic(i).cx
    // ---------- footing (v4.21): feet and bug on the flat part, not over the rounded corners ----------

    /** Left and right end of the ground of [c]: a big surface is its whole width; a normal icon is its picture (never wider than the picture is on this screen). */
    private fun groundL(c: Icon) = if (c.big) c.r.left + padX else c.cx - min(c.r.width(), width * PIC_FRAC) / 2f
    private fun groundR(c: Icon) = if (c.big) c.r.right - padX else c.cx + min(c.r.width(), width * PIC_FRAC) / 2f

    /**
     * A widget's rectangle covers whole grid cells (measured: 572 px for two cells, the visible card 484 px), and the card lines up with the icon pictures on both sides:
     * padX = (distance between two neighbouring icons - icon picture) / 2, about 45 px. The distance is measured on this page, or guessed from the screen width.
     */
    private fun updatePad() {
        val gaps = ArrayList<Float>()
        val norm = icons.filter { !it.big }.sortedWith(compareBy({ it.row }, { it.cx }))
        for (k in 1 until norm.size) {
            if (norm[k].row != norm[k - 1].row) continue
            val d = norm[k].cx - norm[k - 1].cx
            if (d > size * 0.8f && d < size * 2.2f) gaps.add(d)
        }
        val pitch = if (gaps.isNotEmpty()) gaps.sorted()[gaps.size / 2] else width * 0.2344f
        padX = clampF((pitch - min(size, width * PIC_FRAC)) / 2f, 0f, width * 0.06f)
    }

    /** Corner radius of [c]: the measured icon radius; a low wide widget is a pill (radius half its height). */
    private fun cornerR(c: Icon): Float {
        val r0 = max(size * 0.25f, width * CORNER_FRAC)
        val cardH = c.r.height() - 2.1f * padX                                  // the rectangle also covers the label below the card
        return if (c.big && cardH <= r0 * 4.2f) cardH * 0.5f else r0
    }

    /** How far in from each end a foot or the bug must stand: about half a corner radius (there the surface is only a few px lower). */
    private fun edgeIn(c: Icon) = cornerR(c) * 0.55f

    /** Where the dragon's origin may stand on big surface [c], world x (wbLo..wbHi): both feet on it, [edgeIn] from each end, whichever way it faces, and the dragon on screen. */
    private fun walkBounds(c: Icon) {
        val e = edgeIn(c); val reach = FOOT_REACH * ds
        wbLo = max(groundL(c) + e + reach, 90f * ds)
        wbHi = min(groundR(c) - e - reach, width - 90f * ds)
    }

    /** Where on an icon the dragon lands: the middle of a normal icon, anywhere along a big one. */
    private fun landX(i: Int): Float {
        val c = ic(i)
        if (!c.big) return c.cx
        walkBounds(c); val lo = wbLo; val hi = wbHi
        return if (hi > lo) rnd(lo, hi) else c.cx
    }
    private fun sitY(i: Int) = ic(i).r.top + size * 0.06f

    // ---------- flight paths (Catmull-Rom through waypoints, then distance lookup) ----------
    private fun buildPath(px: List<Float>, py: List<Float>): FlightPath {
        val n = px.size
        val xs = ArrayList<Float>(); val ys = ArrayList<Float>()
        for (i in 0 until n - 1) {
            val i0 = max(i - 1, 0); val i3 = min(i + 2, n - 1)
            for (j in 0 until 16) {
                val tt = j / 16f; val t2 = tt * tt; val t3 = t2 * tt
                xs.add(0.5f * ((2 * px[i]) + (-px[i0] + px[i + 1]) * tt + (2 * px[i0] - 5 * px[i] + 4 * px[i + 1] - px[i3]) * t2 + (-px[i0] + 3 * px[i] - 3 * px[i + 1] + px[i3]) * t3))
                ys.add(0.5f * ((2 * py[i]) + (-py[i0] + py[i + 1]) * tt + (2 * py[i0] - 5 * py[i] + 4 * py[i + 1] - py[i3]) * t2 + (-py[i0] + 3 * py[i] - 3 * py[i + 1] + py[i3]) * t3))
            }
        }
        xs.add(px[n - 1]); ys.add(py[n - 1])
        val cum = FloatArray(xs.size)
        for (i in 1 until xs.size) cum[i] = cum[i - 1] + hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1])
        return FlightPath(xs.toFloatArray(), ys.toFloatArray(), cum)
    }

    private fun pathAt(p: FlightPath, s0: Float, out: FloatArray) {
        val s = clampF(s0, 0f, p.len)
        var i = 1
        while (i < p.cum.size - 1 && p.cum[i] < s) i++
        val a = p.cum[i - 1]; val b = p.cum[i]; val u = if (b > a) (s - a) / (b - a) else 0f
        out[0] = p.x[i - 1] + (p.x[i] - p.x[i - 1]) * u
        out[1] = p.y[i - 1] + (p.y[i] - p.y[i - 1]) * u
    }

    private fun waypoint(prevX: Float, prevY: Float): FloatArray {
        val mx = 158f * ds
        val top = height * 0.12f + 150f * ds
        val bot = height * 0.86f
        var best = floatArrayOf(width / 2f, height / 2f)
        var bd = -1f
        for (k in 0 until 8) {
            val p = floatArrayOf(rnd(mx, max(mx + 1f, width - mx)), rnd(top, max(top + 1f, bot)))
            val d = hypot(p[0] - prevX, p[1] - prevY)
            if (d > height * 0.28f) return p
            if (d > bd) { bd = d; best = p }
        }
        return best
    }

    // ---------- behaviour ----------
    private fun land(i: Int, silent: Boolean, x: Float? = null) {
        iconIdx = i.coerceIn(0, icons.size - 1)
        st.x = x ?: (if (silent && ic(iconIdx).big) landX(iconIdx) else sitX(iconIdx)); st.y = sitY(iconIdx)
        air = false; mode = Mode.IDLE; then = null; path = null; huntOn = false; stalkNow = false
        st.rest = 0f; st.scratch = 0f; sleepAfterWalk = false
        timer = rnd(1.4f, 3.6f)
        if (bugOn && bugSt != BUG_JUMP && bugIcon == iconIdx) setBugSide()      // the bug moves aside to the strip in front of the dragon
        // face the screen edge on outer columns so the tail stays on screen
        val cx = if (ic(iconIdx).big) st.x else ic(iconIdx).cx
        val tailM = 160f * ds
        faceT = if (ic(iconIdx).big) {
            if (cx < tailM) -1f else if (cx > width - tailM) 1f else (if (Random.nextBoolean()) 1f else -1f)
        } else if (cx < width * 0.3f) -1f else if (cx > width * 0.7f) 1f else (if (Random.nextBoolean()) 1f else -1f)
        if (silent) st.face = faceT else st.crouch = 0.9f
    }

    private fun launch(ex: Float, ey: Float, after: () -> Unit, hop: Boolean, wander: Boolean) {
        val sx = st.x; val sy = st.y
        val ceil = min(height * 0.12f + 105f * ds, min(sy, ey))
        val px = ArrayList<Float>(); val py = ArrayList<Float>()
        px.add(sx); py.add(sy)
        if (hop) {
            px.add((sx + ex) / 2f); py.add(max(ceil, min(sy, ey) - size * 1.05f))
        } else if (wander) {
            var qx = sx; var qy = sy
            val cnt = 1 + Random.nextInt(3)
            for (i in 0 until cnt) { val w = waypoint(qx, qy); px.add(w[0]); py.add(w[1]); qx = w[0]; qy = w[1] }
        } else {
            px.add((sx + ex) / 2f + rnd(-size, size)); py.add(max(ceil, min(sy, ey) - size * rnd(0.8f, 1.8f)))
        }
        px.add(ex); py.add(ey)
        val p = buildPath(px, py)
        path = p
        val speed = (if (hop) 265f else rnd(200f, 272f)) * sc
        dur = max(if (hop) 0.7f else 1.15f, p.len / speed)
        t = 0f; mode = Mode.FLY; air = true; then = after; st.wingPh = -(PI / 2).toFloat()
    }

    private fun takeOff(ex: Float, ey: Float, after: () -> Unit, hop: Boolean, wander: Boolean) {
        if (air) launch(ex, ey, after, hop, wander)
        else { mode = Mode.CROUCH; t = 0f; dur = 0.22f; then = { launch(ex, ey, after, hop, wander) } }
    }

    private fun neighbors(i: Int): List<Int> {
        val c = ic(i)
        fun gap(o: RectF) = hypot(max(0f, max(o.left - c.r.right, c.r.left - o.right)), max(0f, max(o.top - c.r.bottom, c.r.top - o.bottom)))
        return icons.indices.filter { it != i && gap(icons[it].r) < size * 1.7f }
    }

    private fun flyToIcon(j: Int, hop: Boolean) { val lx = landX(j); takeOff(lx, sitY(j), { land(j, false, lx) }, hop, !hop) }

    private fun startFire(j: Int, after: () -> Unit) {
        mode = Mode.FIRE; target = j; t = 0f; acc = 0f; fireDur = 1.7f; fireFt = -1f
        faceT = if (ic(j).cx >= st.x) 1f else -1f
        then = after
    }

    private fun randIcon(except: Int): Int {
        if (icons.size < 2) return 0
        var j: Int
        do { j = Random.nextInt(icons.size) } while (j == except)
        return j
    }

    private fun afterAirFire(j: Int): () -> Unit = { flyToIcon(if (Random.nextFloat() < 0.55f) j else randIcon(j), false) }

    private fun fireAt(j: Int) {
        val c = ic(j)
        val near = !air && max(0f, max(c.r.left - st.x, st.x - c.r.right)) < size * 3.3f && c.cy - st.y > -size * 1.3f && c.cy - st.y < size * 3.2f
        if (near) {
            startFire(j) { mode = Mode.IDLE; timer = rnd(1.4f, 3f) }
        } else {
            val side = if (c.cx > width / 2f) -1f else 1f
            val f = -side
            val tailM = 160f * ds; val headM = 100f * ds
            val lo = if (f > 0) tailM else headM; val hi = width - (if (f > 0) headM else tailM)
            val hx = clampF(c.cx + side * size * 2.5f, lo, max(lo + 1f, hi))
            val hy = clampF(c.cy - size * 1.15f, height * 0.12f + 150f * ds, height * 0.86f)
            takeOff(hx, hy, { startFire(j, afterAirFire(j)) }, false, true)
        }
    }

    private fun startWalk(): Boolean {
        val c = ic(iconIdx)
        walkBounds(c); val lo = wbLo; val hi = wbHi
        if (!(hi - lo > 80f * ds)) return false
        var tx = st.x; var tries = 0
        while (abs(tx - st.x) < 60f * ds && tries++ < 8) tx = rnd(lo, hi)
        walkTo = clampF(tx, lo, hi); faceT = if (walkTo > st.x) 1f else -1f; mode = Mode.WALK
        return true
    }

    /**
     * Where a lying dragon fits on the current large surface: head and body on it, only the tail may hang over the edge
     * (and the whole dragon stays on screen). Fills spot = [facing, x] and returns false when it does not fit or is too far to reach in a few steps.
     */
    private fun sleepSpot(): Boolean {
        val c = ic(iconIdx)
        if (!c.big || air) return false
        val head = 70f * ds + 4f + edgeIn(c); val tailEdge = 110f * ds; val tailScreen = 146f * ds + 4f
        val faces = floatArrayOf(faceT, -faceT)
        for (f in faces) {
            val lo: Float; val hi: Float
            if (f > 0f) { lo = max(groundL(c) + tailEdge, tailScreen); hi = min(groundR(c) - head, width - head) }
            else { lo = max(groundL(c) + head, head); hi = min(groundR(c) - tailEdge, width - tailScreen) }
            if (lo > hi) continue
            val x = clampF(st.x, lo, hi)
            if (abs(x - st.x) > 26f * sc * 3.4f) continue            // too far to walk there in about three seconds
            spot[0] = f; spot[1] = x
            return true
        }
        return false
    }

    /** Lie down on the spot found by [sleepSpot]; the dragon must already stand there. */
    private fun beginSleep(): Boolean {
        if (!sleepSpot() || abs(spot[1] - st.x) > 2f) return false
        faceT = spot[0]; mode = Mode.SLEEP; sleepPhase = 0; t = 0f; sleepDur = rnd(3f, 6f)
        return true
    }

    private fun startSleep() {
        if (!sleepSpot()) { doAction("fly"); return }
        val c = ic(iconIdx)
        var tx = spot[1]
        // sometimes a few extra steps before lying down (only when there is room)
        if (abs(tx - st.x) < 20f * ds && Random.nextFloat() < 0.5f) {
            walkBounds(c); val lo = wbLo; val hi = wbHi
            val cand = clampF(st.x + rnd(-110f, 110f) * ds, lo, hi)
            val keepFace = faceT; val keepX = st.x
            st.x = cand; val ok = sleepSpot() && abs(spot[1] - cand) < 2f; st.x = keepX; faceT = keepFace
            if (ok) tx = cand
        }
        sleepSpot()
        if (abs(tx - st.x) < 2f) { if (!beginSleep()) doAction("fly"); return }
        walkTo = tx; faceT = if (tx > st.x) 1f else -1f; sleepAfterWalk = true; mode = Mode.WALK
    }

    private fun startScratch() {
        mode = Mode.SCRATCH; t = 0f; scratchDur = rnd(2.4f, 3.8f); st.scratchPh = 0f
    }

    // ---------- the bug (v4.19): a creature of its own ----------

    /** The side (+1 right, -1 left) of the dragon with more room on icon [i]; a coin toss when it stands in the middle. */
    private fun roomSide(i: Int): Float {
        val r = ic(i).r
        val a = r.right - st.x; val b = st.x - r.left
        return if (abs(a - b) < 6f * ds) (if (Random.nextBoolean()) 1f else -1f) else if (a > b) 1f else -1f
    }

    /** The dragon is on the bug's icon: the bug keeps to the side it is on (or the roomier side if it is right under the dragon). */
    private fun setBugSide() {
        val dx = bugWx - st.x
        bugSide = if (abs(dx) > 4f * ds) (if (dx > 0f) 1f else -1f) else roomSide(bugIcon)
    }

    /**
     * Where the bug may stand on icon [i], world x (rgLo..rgHi): the whole top edge, never the gap. While the dragon stands on that icon the bug
     * keeps to [side] of it, in front of its front feet (never a narrower strip than BUG_MIN_ROOM).
     */
    private fun bugRangeFor(i: Int, side: Float) {
        val c = ic(i)
        val m = max(BUG_EDGE_MARGIN, edgeIn(c) + 3f)
        var lo = groundL(c) + m; var hi = groundR(c) - m
        if (!air && iconIdx == i) {
            if (side > 0f) { lo = max(lo, st.x + BUG_FRONT * ds); lo = min(lo, hi - BUG_MIN_ROOM * ds); lo = max(lo, st.x + 24f * ds) }
            else { hi = min(hi, st.x - BUG_FRONT * ds); hi = max(hi, lo + BUG_MIN_ROOM * ds); hi = min(hi, st.x - 24f * ds) }
        }
        if (hi < lo) { val m = (lo + hi) / 2f; lo = m; hi = m }
        rgLo = lo; rgHi = hi
    }

    /** A new bug, in a new random colour, on a random icon or widget of this page. */
    private fun spawnBug() {
        val i = Random.nextInt(icons.size)
        bugIcon = i
        bugOn = true; bugSt = BUG_ALIVE; bugT = 0f; bugAge = 0f
        bugFlee = false; bugFwd = true; bugMoving = false; bugSmoke = 0f
        bugH = rnd(0f, 360f); bugS = rnd(0.65f, 0.9f); bugV = rnd(0.6f, 0.95f); bugPat = Random.nextInt(3); bugLandT = 0f
        bugSide = if (!air && iconIdx == i) roomSide(i) else 1f
        bugRangeFor(i, bugSide)
        bugWx = rgLo + (rgHi - rgLo) * rnd(0.1f, 0.9f); bugWy = sitY(i)
        bugGoalX = bugWx; bugPause = rnd(0.1f, 0.3f); bugDir = if (Random.nextBoolean()) 1f else -1f
        bugNoticeT = rnd(1f, 3f); bugJumpT = rnd(2.5f, 5f)
        huntOn = false
        fgSpotX = bugWx; fgSpotY = bugWy; fgAimX = bugWx; fgAimY = bugWy
        Diag.log(context, "Bug: appeared on " + (if (ic(i).big) "a widget" else "an icon"))
    }

    /** The bug is gone (eaten, or left behind on another page); the next one comes after a random while. */
    private fun endBug() {
        bugOn = false; huntOn = false; stalkNow = false
        bugSpawnT = rnd(BUG_SPAWN_MIN, BUG_SPAWN_MAX)
    }

    private fun newBugGoal(lo: Float, hi: Float) {
        val minStep = min(12f * ds, (hi - lo) * 0.35f)
        var n = 0
        do { bugGoalX = lo + (hi - lo) * Random.nextFloat() } while (abs(bugGoalX - bugWx) < minStep && ++n < 12)
    }

    private fun killBug() { bugSt = BUG_DEAD; bugT = 0f; bugMoving = false; bugSmoke = 0f; fgHit = true; Diag.log(context, "Bug: burnt (" + huntJumps + " jumps, " + "%.1f".format((SystemClock.uptimeMillis() - huntT0) / 1000f) + " s after it was noticed)") }
    private fun reviveBug() { bugSt = BUG_FLIP; bugT = 0f; bugMoving = false }

    /** The icon the bug is on, or is jumping to; and the x it is at or will land on. */
    private fun bugDest() = if (bugSt == BUG_JUMP) bugJumpIcon else bugIcon
    private fun bugDestX() = if (bugSt == BUG_JUMP) bugJx1 else bugWx

    /** Runs from the frame loop while the bug is on. */
    private fun updateBug(dt: Float) {
        bugT += dt; bugAge += dt
        if (bugLandT > 0f) bugLandT -= dt
        when (bugSt) {
            BUG_ALIVE -> aliveBug(dt)
            BUG_FLIP -> if (bugT >= D_REVIVE) { bugSt = BUG_ALIVE; bugT = 0f; bugFlee = true; bugFwd = true; bugPause = 0f }
            BUG_JUMP -> {
                val u = min(1f, bugT / bugJumpDur)
                bugAirX = bugJx0 + (bugJx1 - bugJx0) * u
                bugAirY = bugJy0 + (bugJy1 - bugJy0) * u - 4f * u * (1f - u) * size * 0.5f
                if (u >= 1f) {
                    bugIcon = bugJumpIcon; bugWx = bugJx1; bugWy = bugJy1
                    bugSt = BUG_ALIVE; bugT = 0f; bugFlee = false; bugFwd = true; bugPause = rnd(0.2f, 0.4f); bugGoalX = bugWx; bugLandT = BUG_LAND_S
                    if (!air && iconIdx == bugIcon) setBugSide() else bugSide = bugJumpSide
                }
            }
            else -> {
                // dead: thin smoke in the fire's colour until it is picked up
                bugSmoke += dt * 6f * (1f - 0.7f * smooth(0f, 1.6f, bugT)) * (0.4f + 0.6f * pq)
                while (bugSmoke >= 1f) { bugSmoke -= 1f; addSmoke(bugWx + rnd(-1f, 1f) * 1.5f * sc, bugWy - 4f * sc, -1f, 0.28f) }
            }
        }
    }

    /**
     * Alive: darts in short runs with pauses on its ground, and now and then jumps to a nearby icon or widget (any row) by itself, as often as it likes,
     * until the dragon is after it. Fleeing (it faked its death): runs for the far end of its strip and jumps away if there is a neighbour, else turns back.
     * Never off the top edge of an icon or widget.
     */
    private fun aliveBug(dt: Float) {
        bugRangeFor(bugIcon, bugSide)
        val lo = rgLo; val hi = rgHi
        if (bugWx < lo - 0.5f || bugWx > hi + 0.5f) {
            // in the dragon's way (it just landed on the bug, or walks up to it): runs out to its strip
            val goal = if (bugWx < lo) lo else hi
            val d = goal - bugWx; val stp = min(abs(d), BUG_FLEE_SPEED * ds * dt)
            bugWx += if (d > 0f) stp else -stp
            bugDir = if (d > 0f) 1f else -1f; bugMoving = true
        } else if (bugFlee) {
            val away = if (bugFwd) bugSide else -bugSide
            val goal = if (away > 0f) hi else lo
            val d = goal - bugWx; val stp = min(abs(d), BUG_FLEE_SPEED * ds * dt)
            bugWx += if (d > 0f) stp else -stp
            if (stp > 0f) bugDir = if (d > 0f) 1f else -1f
            bugMoving = true
            if (abs(goal - bugWx) < 0.5f) {
                if (bugFwd) {
                    val j = pickJumpTarget(bugSide)
                    if (j >= 0 && beginBugJump(j)) { startHunt(); return }      // the dragon follows
                }
                bugFwd = !bugFwd
            }
        } else {
            if (!huntOn) {
                bugJumpT -= dt
                if (bugJumpT <= 0f) {
                    bugJumpT = rnd(2.5f, 5f)
                    val j = pickJumpTarget(0f)
                    if (j >= 0 && beginBugJump(j)) return
                }
            }
            if (bugPause > 0f) {
                bugPause -= dt; bugMoving = false
            } else {
                val d = bugGoalX - bugWx; val stp = min(abs(d), BUG_SPEED * ds * dt)
                bugWx += if (d > 0f) stp else -stp
                if (stp > 0f) bugDir = if (d > 0f) 1f else -1f
                bugMoving = true
                if (abs(bugGoalX - bugWx) < 0.5f * ds) { bugPause = rnd(0.12f, 0.35f); newBugGoal(lo, hi) }
            }
        }
        bugWy = sitY(bugIcon)
    }

    /** A nearby icon or widget to jump to (any row; [pref] = +1 or -1 favours that side), or -1 when there is none. */
    private fun pickJumpTarget(pref: Float): Int {
        val nb = neighbors(bugIcon)
        if (nb.isEmpty()) return -1
        if (pref != 0f) {
            val cx = ic(bugIcon).cx
            val f = nb.filter { (ic(it).cx - cx) * pref > 0f }
            if (f.isNotEmpty()) return f.random()
        }
        return nb.random()
    }

    /** The bug leaps to icon [j]: a short arc with a spin, landing on its top edge (on the roomier side of the dragon, if the dragon stands there). */
    private fun beginBugJump(j: Int): Boolean {
        if (bugSt != BUG_ALIVE || j == bugIcon || j !in icons.indices) return false
        val side = if (!air && iconIdx == j) roomSide(j) else bugSide
        bugRangeFor(j, side)
        val lx = rgLo + (rgHi - rgLo) * rnd(0.2f, 0.9f)
        val ly = sitY(j)
        bugJx0 = bugWx; bugJy0 = bugWy; bugJx1 = lx; bugJy1 = ly
        bugJumpIcon = j; bugJumpSide = side
        bugJumpDur = clampF(0.3f + hypot(lx - bugWx, ly - bugWy) / (size * 12f), 0.35f, 0.55f)
        bugSt = BUG_JUMP; bugT = 0f; bugMoving = false; bugFlee = false
        bugDir = if (lx >= bugWx) 1f else -1f
        bugAirX = bugWx; bugAirY = bugWy
        if (huntOn) huntJumps++
        return true
    }

    // ---------- the dragon after the bug ----------

    /** Where on a widget the dragon lands: [BUG_REACH] model units beside the bug, on the side that fits and is nearer to where it comes from. */
    private fun huntLandX(i: Int, bx: Float): Float {
        val c = ic(i)
        walkBounds(c); val lo = wbLo; val hi = wbHi
        if (hi <= lo) return c.cx
        val a = bx - 72f * ds; val b = bx + 72f * ds
        val okA = a in lo..hi; val okB = b in lo..hi
        return if (okA && okB) (if (abs(a - st.x) <= abs(b - st.x)) a else b) else if (okA) a else if (okB) b else clampF(a, lo, hi)
    }

    /** The dragon has noticed the bug: it is on the bug's icon (bow, turn, flame) or it takes off after it (hop to a near one, a flight to a far one). */
    private fun startHunt() {
        if (!bugOn || icons.isEmpty()) return
        huntOn = true
        val dest = bugDest()
        if (!air && dest == iconIdx) { beginForage(); return }
        val c = ic(dest)
        val lx = if (c.big) huntLandX(dest, bugDestX()) else c.cx
        val hop = neighbors(iconIdx).contains(dest)
        takeOff(lx, sitY(dest), { land(dest, false, lx); arriveHunt() }, hop, false)
    }

    /** The dragon landed on the bug's icon. The bug may jump on again (60%, as often as it likes); otherwise it stays and the dragon deals with it. */
    private fun arriveHunt() {
        if (!bugOn) { huntOn = false; return }
        huntOn = true
        val dest = bugDest()
        if (dest != iconIdx) { bugNoticeT = 0.4f; return }                          // it got away meanwhile: a moment, then after it again
        if (bugSt == BUG_ALIVE && Random.nextFloat() < BUG_REJUMP) {
            val j = pickJumpTarget(0f)
            if (j >= 0 && beginBugJump(j)) { bugNoticeT = 0.45f; return }
        }
        beginForage()
    }

    /** Next to the bug on the same icon: turn to it, bow, flame. */
    private fun beginForage() {
        val bx = bugDestX()
        val dx = bx - st.x
        bugSide = if (abs(dx) > 4f * ds) (if (dx > 0f) 1f else -1f) else roomSide(iconIdx)
        fgDir = bugSide; faceT = fgDir
        mode = Mode.FORAGE; t = 0f; fgAcc = 0f; fgCrumb = 0; ncr = 0
        fgHit = false
        huntOn = true; sleepAfterWalk = false; stalkNow = false
        fgSpotX = bx; fgSpotY = sitY(bugDest()); fgAimX = fgSpotX; fgAimY = fgSpotY
        fgPhase = if (ic(iconIdx).big && abs(dx) / ds > BUG_REACH) FP_STALK else FP_NOTICE
        fgT = 0f
    }

    /** On a widget the dragon runs along (at a quick walk) until the bug is within reach; true while it still has to. */
    private fun stalkStep(dt: Float): Boolean {
        val c = ic(iconIdx)
        if (!c.big) return false
        if ((fgSpotX - st.x) * fgDir / ds <= BUG_REACH) return false
        walkBounds(c); val lo = wbLo; val hi = wbHi
        val nx = clampF(st.x + fgDir * 26f * sc * 2.4f * st.walk * st.walk * dt, lo, max(lo, hi))
        val moved = abs(nx - st.x)
        if (moved < 0.01f && st.walk > 0.9f) return false                           // the end of the widget
        st.x = nx; st.gait += moved / ds / 46.7f
        stalkNow = true
        return true
    }

    private fun doAction(kind: String) {
        when (kind) {
            "sleep" -> startSleep()
            "scratch" -> startScratch()
            "walk" -> { if (!startWalk()) timer = rnd(1f, 2f) }
            "jump" -> {
                val nb = neighbors(iconIdx)
                if (nb.isEmpty()) return doAction("fly")
                flyToIcon(nb.random(), true)
            }
            "fly" -> flyToIcon(randIcon(iconIdx), false)
            else -> fireAt(randIcon(iconIdx))
        }
    }

    private fun chooseNext() {
        // each time it is bored it picks a mood at random; moods that do not fit here are left out
        val kinds = ArrayList<String>(); val w = ArrayList<Float>()
        if (ic(iconIdx).big) { kinds.add("walk"); w.add(0.26f) }
        if (sleepSpot()) { kinds.add("sleep"); w.add(0.2f) }
        kinds.add("scratch"); w.add(0.15f)
        kinds.add("jump"); w.add(0.13f)
        kinds.add("fly"); w.add(0.13f)
        kinds.add("fire"); w.add(0.22f)
        var r = Random.nextFloat() * w.sum()
        for (i in kinds.indices) { r -= w[i]; if (r <= 0f || i == kinds.size - 1) { doAction(kinds[i]); return } }
    }

    // ---------- effects ----------
    private fun emitFlame(mx: Float, my: Float, tx: Float, ty: Float, sizeMul: Float = 1f, minSpeed: Float = 260f) {
        if (nf >= capF) return
        val kind = if (Random.nextFloat() < 0.35f) 0 else 1
        val life = if (kind == 0) rnd(0.3f, 0.46f) else rnd(0.45f, 0.8f)
        // the particle should reach the icon centre near the end of its life: solve the launch speed
        // against the air drag (0.4 per second) and compensate the upward drift
        val arrive = life * rnd(0.74f, 0.86f)
        val lift = 0.5f * (90f + (if (kind == 1) 100f else 20f)) * sc * arrive * arrive
        val dx = tx - mx; val dy = ty + lift - my; val d = max(1f, hypot(dx, dy)); val ux = dx / d; val uy = dy / d
        val sp = clampF(d * 0.916f / (1f - 0.4f.pow(arrive)), minSpeed * sc, 1500f * sc)
        val a = (Random.nextFloat() - 0.5f) * (if (kind == 0) 0.05f else 0.16f)
        val cs = cos(a); val sn = sin(a)
        fX[nf] = mx; fY[nf] = my
        fVx[nf] = (ux * cs - uy * sn) * sp; fVy[nf] = (ux * sn + uy * cs) * sp
        fPx[nf] = -uy; fPy[nf] = ux
        fAge[nf] = 0f; fLife[nf] = life
        fSize[nf] = (if (kind == 0) rnd(8f, 12f) else rnd(13f, 22f)) * ds * 1.35f * sizeMul
        fSeed[nf] = rnd(0f, TAU); fKind[nf] = kind
        nf++
    }

    private fun addSplash(x: Float, y: Float) {
        if (nf >= capF) return
        val a = rnd(0f, TAU); val sp = rnd(70f, 210f) * sc
        fX[nf] = x; fY[nf] = y
        fVx[nf] = cos(a) * sp; fVy[nf] = sin(a) * sp * 0.8f - rnd(0f, 50f) * sc
        fPx[nf] = 0f; fPy[nf] = 1f
        fAge[nf] = 0f; fLife[nf] = rnd(0.22f, 0.4f); fSize[nf] = rnd(7f, 12f) * ds * 1.3f
        fSeed[nf] = rnd(0f, TAU); fKind[nf] = 2
        nf++
    }

    /** Small flame tongue rising off an icon that was just set alight. */
    private fun addLick(x: Float, y: Float, small: Boolean) {
        if (nf >= capF - 20) return
        fX[nf] = x; fY[nf] = y
        fVx[nf] = rnd(-14f, 14f) * sc; fVy[nf] = -rnd(70f, 150f) * sc * (if (small) 0.6f else 1f)
        fPx[nf] = 1f; fPy[nf] = 0f
        fAge[nf] = 0f; fLife[nf] = rnd(0.35f, 0.6f); fSize[nf] = rnd(7f, 11f) * ds * 1.3f * (if (small) 0.7f else 1f)
        fSeed[nf] = rnd(0f, TAU); fKind[nf] = 3
        nf++
    }

    private fun addSmoke(x: Float, y: Float, lit: Float, sizeMul: Float = 1f) {
        if (ns >= capS) return
        sX[ns] = x; sY[ns] = y
        sVx[ns] = rnd(-10f, 10f) * sc; sVy[ns] = -rnd(28f, 56f) * sc
        sAge[ns] = 0f; sLife[ns] = rnd(1.3f, 2.4f); sSize[ns] = rnd(10f, 17f) * sc * sizeMul
        sRot[ns] = rnd(0f, TAU); sRv[ns] = rnd(-0.7f, 0.7f); sLit[ns] = lit
        sSeed[ns] = rnd(0f, TAU); sVar[ns] = Random.nextInt(3)
        ns++
    }

    private fun addSpark(x: Float, y: Float, ang: Float, spd: Float) {
        if (nk >= capP) return
        kX[nk] = x; kY[nk] = y; kVx[nk] = cos(ang) * spd; kVy[nk] = sin(ang) * spd
        kAge[nk] = 0f; kLife[nk] = rnd(0.3f, 0.75f); kG[nk] = 1f
        nk++
    }

    /** Slow glowing ash that floats up off a burnt icon (negative kG marks a floater). */
    private fun addEmber(x: Float, y: Float) {
        if (nk >= capP - 10) return
        kX[nk] = x; kY[nk] = y; kVx[nk] = rnd(-14f, 14f) * sc; kVy[nk] = -rnd(22f, 60f) * sc
        kAge[nk] = 0f; kLife[nk] = rnd(1.2f, 2.8f); kG[nk] = -rnd(0.02f, 0.1f)
        nk++
    }

    // ---------- per-frame update ----------
    private fun step(rdt: Float) {
        val now = SystemClock.uptimeMillis()
        if (swipeState == 1 && now - swipeLast > 120L) { swipeState = 2 }
        else if (swipeState == 2 && appearAt != 0L && now >= appearAt) { swipeState = 0; appearAt = 0L; fadeGoal = 1f }
        else if (swipeState == 2 && now - swipeLast >= SCAN_WAIT_MAX_MS) { swipeState = 0; appearAt = 0L; fadeGoal = 1f }      // safety: the scan never came
        val fadeTarget = if (hiddenByApp) 0f else fadeGoal
        // No fade-out: the dragon just disappears. Fade-in: FADE_IN_S (0.4 s), eased in onDraw.
        fade = if (fadeTarget < fade) fadeTarget else clampF(fade + rdt / FADE_IN_S, 0f, fadeTarget)
        if (hiddenByApp && fade <= 0.01f && !hiddenNotified) { hiddenNotified = true; post { onFullyHidden?.invoke() } }
        if (icons.isEmpty()) return
        time += rdt
        for (c in icons) {
            c.heat = max(0f, c.heat - rdt * 1.4f)
            c.ember = max(0f, c.ember - rdt * 0.1f)
            if (c.heat < 0.05f) c.burn = max(0f, c.burn - rdt * 0.03f)
            if (c.heat > 0.35f) {
                c.lk += rdt * 12f * c.heat
                while (c.lk >= 1f) { c.lk -= 1f; addLick(c.cx + rnd(-.35f, .35f) * c.r.width(), c.cy + rnd(-.2f, .3f) * c.r.height(), false) }
            }
            else if (c.ember > 0.45f && c.burn > 0.3f) {
                // small blue pilot flames keep flickering while the icon is still white hot
                c.lk += rdt * (c.ember - 0.4f) * 8f
                while (c.lk >= 1f) { c.lk -= 1f; addLick(c.cx + rnd(-.3f, .3f) * c.r.width(), c.cy + rnd(-.1f, .3f) * c.r.height(), true) }
            }
            if (c.ember > 0.12f && c.burn > 0.15f) {
                // glowing ash drifting up off the charred icon
                c.em += rdt * 5f * c.ember * pq
                while (c.em >= 1f) { c.em -= 1f; addEmber(c.r.left + rnd(.1f, .9f) * c.r.width(), c.r.top + rnd(.2f, .9f) * c.r.height()) }
            }
            if (c.burn > 0.2f) {
                c.sm += rdt * (0.4f + 3.2f * c.heat + 1.4f * c.ember) * c.burn * (0.3f + 0.7f * pq)
                while (c.sm >= 1f) { c.sm -= 1f; addSmoke(c.r.left + rnd(.2f, .8f) * c.r.width(), c.r.top + rnd(.05f, .4f) * c.r.height(), c.heat) }
            }
        }
        val dt = rdt * spdMul           // the dragon's own clock: slower or faster than real time
        nextBlink -= dt
        if (nextBlink < 0f) { blinkT = 0.14f; nextBlink = rnd(2f, 5f) }
        if (blinkT > 0f) blinkT -= dt
        st.eye = if (blinkT > 0f) 0.1f else if (st.scratch > 0.3f) 0.45f else 1f
        nextLook -= dt
        if (nextLook < 0f) { lookT = rnd(-0.14f, 0.12f); nextLook = rnd(1.2f, 3.2f) }
        look += (lookT - look) * min(1f, dt * 3f)

        st.sp += ((if (air) 1f else 0f) - st.sp) * min(1f, dt * 7f)
        st.walk += ((if (mode == Mode.WALK || (mode == Mode.FORAGE && stalkNow)) 1f else 0f) - st.walk) * min(1f, dt * 6f)
        val flapHz = if (air) 2.2f + 0.7f * sin(time * 0.8f) + (if (mode == Mode.FIRE) 0.5f else 0f) else 0.5f
        st.wingPh += dt * TAU * flapHz
        st.crouch = if (mode == Mode.CROUCH) clampF(t / dur * 1.15f, 0f, 1f) else max(0f, st.crouch - dt * (if (air) 6f else 4.5f))

        if (bugOn) updateBug(dt)
        else if ((mode == Mode.IDLE || mode == Mode.WALK || mode == Mode.SCRATCH) && fade >= 0.99f && !hiddenByApp && swipeState == 0 && icons.size >= 2) {
            bugSpawnT -= dt                                             // a bug appears now and then, while the dragon is awake and not busy
            if (bugSpawnT <= 0f) spawnBug()
        }
        if (bugOn && bugSt == BUG_DEAD && mode != Mode.FORAGE) endBug()          // the routine was interrupted: the dead bug goes
        if (bugOn && (mode == Mode.IDLE || (mode == Mode.WALK && !sleepAfterWalk)) && (bugSt == BUG_ALIVE || bugSt == BUG_JUMP) && fade >= 0.99f && !hiddenByApp) {
            bugNoticeT -= dt                                            // counted only while the dragon is awake and idle
            if (bugNoticeT <= 0f) {
                if (!huntOn) { fgWillRevive = Random.nextFloat() < REVIVE_CHANCE; fgRevived = false; huntJumps = 0; huntT0 = SystemClock.uptimeMillis(); Diag.log(context, "Bug: the dragon noticed it") }
                startHunt()
            }
        }
        val ox = st.x; val oy = st.y
        var firing = false
        when (mode) {
            Mode.IDLE -> { timer -= dt; if (timer <= 0f) chooseNext() }
            Mode.WALK -> {
                val spd = 26f * sc * st.walk * st.walk
                val dx = walkTo - st.x; val stp = min(abs(dx), spd * dt)
                st.x += (if (dx > 0f) stp else -stp); st.gait += stp / ds / 46.7f
                if (abs(walkTo - st.x) < 0.5f) {
                    mode = Mode.IDLE; timer = rnd(1.4f, 3.2f)
                    if (sleepAfterWalk) { sleepAfterWalk = false; if (!beginSleep()) timer = rnd(0.4f, 1f) }
                }
            }
            Mode.SLEEP -> {
                t += dt
                when (sleepPhase) {
                    0 -> if (abs(st.face - faceT) < 0.15f) {
                        st.rest = min(1f, st.rest + dt / 1.3f)
                        if (st.rest >= 1f) { sleepPhase = 1; t = 0f }
                    }
                    1 -> if (t >= sleepDur) { sleepPhase = 2; t = 0f }
                    else -> {
                        st.rest = max(0f, st.rest - dt / 1.2f)
                        if (st.rest <= 0f) { mode = Mode.IDLE; timer = rnd(0.6f, 1.6f) }
                    }
                }
            }
            Mode.SCRATCH -> {
                t += dt
                val raise = smooth(0f, 0.45f, min(t, scratchDur - t))
                st.scratch += (raise - st.scratch) * min(1f, dt * 12f)
                st.scratchPh += dt * TAU * 3f * smooth(0.3f, 0.8f, raise)
                if (t >= scratchDur) { mode = Mode.IDLE; timer = rnd(1f, 2.4f) }
            }
            Mode.CROUCH -> {
                t += dt
                if (t >= dur) { val cb = then; then = null; cb?.invoke() }
            }
            Mode.FORAGE -> {
                fgT += dt
                stalkNow = false
                if (bugSt == BUG_JUMP) { fgSpotX = bugAirX; fgSpotY = bugAirY } else if (bugOn) { fgSpotX = bugWx; fgSpotY = bugWy }
                fgAimX += (fgSpotX - fgAimX) * min(1f, dt * 20f); fgAimY = fgSpotY
                // the neck bows so that the mouth reaches the bug wherever it is on the icon, even right under the chin
                val bm = clampF((fgSpotX - st.x) * fgDir / ds, -20f, 140f)
                st.lowerDx = clampF(bm - 56.5f, -14f, 50f)
                var lowT = 0f; var mouthT = 0f; var glow = 0f; var nod = 0f
                when (fgPhase) {
                    FP_NOTICE -> {
                        lowT = 0.55f * smooth(0f, 0.35f, fgT)
                        nod = 0.03f * sin(fgT * 10f)                                // a small sniffing nod
                        if (fgT >= D_NOTICE && bugSt != BUG_JUMP) { fgPhase = FP_FLAME; fgT = 0f; fgHit = false }
                    }
                    FP_STALK -> {
                        if (!stalkStep(dt)) { fgPhase = FP_NOTICE; fgT = 0f }       // close enough (or at the end of the widget): look, then flame
                    }
                    FP_FLAME -> {
                        lowT = 0.55f
                        mouthT = smooth(0f, 0.12f, fgT) * 0.7f; glow = -1f          // flame glow in the mouth only while the small flame burns
                        if (!fgHit && fgT >= HIT_AT && bugSt == BUG_ALIVE) killBug()                      // the flame reaches the bug: it stops and dies where it is
                        fgAcc += dt * 90f * (0.45f + 0.55f * pq)
                        if (fgAcc >= 1f) model.mouthPos(st, mp)
                        while (fgAcc >= 1f) { fgAcc -= 1f; emitFlame(mp[0], mp[1], fgAimX, fgAimY, 0.75f, 60f) }     // small: no charge-up, no scorching; aims at the bug
                        if (fgT >= D_FLAME) { fgPhase = FP_CHECK; fgT = 0f }
                    }
                    FP_CHECK -> {
                        val fake = fgWillRevive && !fgRevived
                        val dur = if (fake) D_CHECK_FAKE else D_CHECK
                        lowT = lerp3(0.55f, 0.75f, smooth(0f, dur, fgT))
                        mouthT = 0.7f * (1f - smooth(0f, 0.1f, fgT)); glow = if (fgT < 0.1f) -1f else 0f
                        nod = 0.04f * sin(fgT * 14f)                                // checks it is dead
                        if (fgT >= dur) { if (fake) { reviveBug(); fgPhase = FP_REVIVE } else fgPhase = FP_PICK; fgT = 0f }
                    }
                    FP_REVIVE -> {
                        lowT = lerp3(0.75f, 0.55f, smooth(0f, D_REVIVE, fgT))
                        if (fgT >= D_REVIVE) { fgRevived = true; fgPhase = FP_CHASE; fgT = 0f }
                    }
                    FP_CHASE -> {
                        stalkStep(dt)                                               // on a widget the dragon runs after the bug
                        val snapAt = 0.5f                                           // one snap at the bug, which gets away
                        val u = (fgT - (snapAt - 0.2f)) / 0.35f
                        lowT = 0.55f + 0.45f * (if (u > 0f && u < 1f) sin(PI.toFloat() * u) else 0f)
                        mouthT = 0.8f * smooth(snapAt - 0.1f, snapAt - 0.02f, fgT) * (1f - smooth(snapAt, snapAt + 0.06f, fgT))
                        if (fgT >= D_CHASE) { fgPhase = FP_FLAME; fgT = 0f; fgHit = false }
                    }
                    FP_PICK -> {
                        lowT = if (fgT < PICK_BITE) lerp3(0.75f, 1f, smooth(0f, PICK_BITE, fgT)) else 1f - 0.85f * smooth(PICK_BITE + 0.1f, D_PICK, fgT)
                        mouthT = 0.6f * smooth(0.1f, 0.3f, fgT) * (1f - smooth(PICK_BITE, PICK_BITE + 0.1f, fgT))
                        if (bugOn && fgT >= PICK_BITE + 0.05f) { endBug(); Diag.log(context, "Bug: eaten") }        // bitten: the bug is gone
                        if (fgT >= D_PICK) { fgPhase = FP_EAT; fgT = 0f; fgCrumb = 0 }
                    }
                    else -> {
                        lowT = 0.15f * (1f - smooth(0f, 1f, fgT))
                        mouthT = if (fgT < EAT_LICK) (0.05f + 0.18f * (0.5f - 0.5f * cos(fgT * TAU * 3.3f))) * (1f - smooth(EAT_LICK - 0.15f, EAT_LICK, fgT))
                            else 0.4f * sin(PI.toFloat() * (fgT - EAT_LICK) / (D_EAT - EAT_LICK))
                        // crumbs fall from the mouth while it chews
                        while (fgCrumb < 5 && fgT >= 0.15f + 0.3f * fgCrumb) {
                            fgCrumb++
                            if (ncr < crX.size) {
                                model.mouthPos(st, mp)
                                crX[ncr] = mp[0]; crY[ncr] = mp[1]; crVx[ncr] = fgDir * rnd(10f, 45f) * sc; crVy[ncr] = -rnd(20f, 70f) * sc; crAge[ncr] = 0f
                                ncr++
                            }
                        }
                        if (fgT >= D_EAT) { mode = Mode.IDLE; timer = rnd(1.4f, 3f) }
                    }
                }
                st.lower += (lowT - st.lower) * min(1f, dt * 14f)          // smoothed, so the bow does not pop at the start or after the hop
                model.headLocal(st.sp, st.walk, hl2, st.lower, st.lowerDx)
                val bx = st.x + fgDir * hl2[0] * ds; val by = st.y + (hl2[1] + st.bob) * ds
                val toSpot = clampF(atan2(fgSpotY - by, max(8f, abs(fgSpotX - bx))), -0.6f, 1.2f)
                headGoal = when (fgPhase) {
                    FP_PICK -> if (fgT < PICK_BITE + 0.1f) toSpot else lerp3(toSpot, 0.2f, smooth(PICK_BITE + 0.1f, D_PICK, fgT))
                    FP_EAT -> if (fgT < EAT_LICK) 0.2f else 0.2f - 0.25f * sin(PI.toFloat() * (fgT - EAT_LICK) / (D_EAT - EAT_LICK))
                    else -> toSpot + nod
                }
                st.mouth = mouthT; st.mouthGlow = glow
            }
            Mode.FLY -> {
                t += dt
                val e = min(1f, t / dur); val s = e * e * (3f - 2f * e)
                path?.let { pathAt(it, s * it.len, pp); st.x = pp[0]; st.y = pp[1] }
                if (t >= dur) { val cb = then; then = null; cb?.invoke() }
            }
            Mode.FIRE -> {
                firing = true
                t += if (t < chargeT) rdt else dt       // the charge-up runs in real seconds, whatever the dragon speed
                val c = ic(target)
                // charge-up first (see drawCharge), then the breath; with a charge-up the mouth is fully open for all of it
                val ft = t - chargeT
                fireFt = ft
                st.mouth = if (ft < 0f) smooth(0f, min(0.25f, chargeT), t)
                    else if (chargeT > 0.05f) 1f                                       // fully open for the whole breath, closes slowly afterwards
                    else clampF(min(0.5f + ft / 0.5f, (fireDur - ft) / 0.25f), 0f, 1f)
                st.mouthGlow = if (ft < 0f && chargeT > 0.05f) 0f else -1f          // no fire in the mouth until the breath starts
                model.headLocal(st.sp, st.walk, hl); val hx = hl[0]; val hy = hl[1]
                val ang = atan2(c.cy - (st.y + (hy + st.bob) * ds), max(8f, abs(c.cx - (st.x + st.face * hx * ds))))
                headGoal = clampF(ang, -0.6f, 1.0f)
                if (ft > 0.3f && ft < fireDur - 0.25f) {
                    model.mouthPos(st, mp)
                    acc += dt * 110f * (0.45f + 0.55f * pq)
                    while (acc >= 1f) { acc -= 1f; emitFlame(mp[0], mp[1], c.cx, c.cy) }
                    if (Random.nextFloat() < dt * 14f) addSpark(mp[0], mp[1], atan2(c.cy - mp[1], c.cx - mp[0]) + rnd(-0.5f, 0.5f), rnd(180f, 420f) * sc)
                    c.burn = min(1f, c.burn + dt * 0.62f * (size / max(size, c.r.width() * 0.5f))); c.heat = min(1f, c.heat + dt * 4f); c.ember = 1f
                }
                if (ft >= fireDur) { if (chargeT <= 0.05f) st.mouth = 0f; fireFt = -1f; val cb = then; then = null; cb?.invoke() }
            }
        }
        if (!firing && mode != Mode.FORAGE) { st.mouth = max(0f, st.mouth - dt * (if (chargeT > 0.05f) 1.3f else 6f)); st.mouthGlow = -1f }   // closes slowly after a charged breath
        // electric arcs on the spine: full strength during the whole breath, then fading to zero in about a second
        if (firing && fireFt >= 0f && chargeT > 0.05f) chSpark = 1f else if (chSpark > 0f) chSpark = max(0f, chSpark - rdt)
        if (mode != Mode.SCRATCH) st.scratch = max(0f, st.scratch - dt * 4f)
        if (mode != Mode.FORAGE) st.lower = max(0f, st.lower - dt * 3f)
        val hg = if (firing || mode == Mode.FORAGE) headGoal
        else if (st.scratch > 0.02f) lerp3(model.restHead(st.sp, st.walk) + look * (1f - st.sp), 0.45f + 0.05f * sin(st.scratchPh * 0.5f), st.scratch)
        else model.restHead(st.sp, st.walk) + look * (1f - st.sp)
        st.head += (hg - st.head) * min(1f, dt * (if (mode == Mode.FORAGE && (fgPhase <= FP_CHASE || fgPhase == FP_STALK)) 22f else 12f))     // 22 per second: the head stays in step with the darting bug

        val cvx = (st.x - ox) / dt; val cvy = (st.y - oy) / dt
        vx += (cvx - vx) * min(1f, dt * 10f); vy += (cvy - vy) * min(1f, dt * 10f)
        if (mode == Mode.FLY && abs(vx) > 30f * sc) faceT = if (vx > 0f) 1f else -1f
        st.face += (faceT - st.face) * min(1f, dt * 11f)
        val wp = if (mode == Mode.FLY) clampF(atan2(vy, max(90f * sc, abs(vx))) * 0.6f, -0.55f, 0.55f) else 0f
        st.pitch += (wp - st.pitch) * min(1f, dt * 7f)
        // Flap-synced motion (v4.15): the body rises on the downstroke and sinks on the upstroke; the nose lifts a little on the upstroke (peak mid-upstroke, level mid-downstroke).
        // Both use the wing phase, so they stay in step when the flap rate changes. Blended by sp, and weaker (smoothly) while breathing fire so the aim stays steady.
        flapAmp += ((if (mode == Mode.FIRE) 0.6f else 1f) - flapAmp) * min(1f, dt * 6f)
        val idleBob = sin(time * 2.3f) * 0.5f + sin(st.gait * TAU * 2f) * 1.3f * st.walk
        st.bob = (-sin(st.wingPh) * FLAP_BOB * flapAmp - 4f) * st.sp + idleBob * (1f - st.sp)
        st.flapTilt = -FLAP_TILT * flapAmp * 0.5f * (1f - cos(st.wingPh)) * st.sp
        st.time = time; st.ds = ds
        // "completely sitting": the landing / crouch / wing-fold animation has finished too
        val settled = st.sp < 0.02f && st.walk < 0.02f && st.crouch < 0.02f && st.mouth < 0.02f && st.lower < 0.02f &&
            abs(st.pitch) < 0.01f && abs(st.face - faceT) < 0.02f
        val asleep = mode == Mode.SLEEP && sleepPhase == 1 && st.rest >= 0.999f
        idleNow = !bugOn && settled && (mode == Mode.IDLE || mode == Mode.SCRATCH || asleep) && nf == 0 && ns == 0 && nk == 0 && fade >= 0.99f && fadeGoal >= 0.99f &&
            !hiddenByApp && icons.none { it.heat > 0.02f || it.ember > 0.02f }

        // crumbs
        var q = ncr - 1
        while (q >= 0) {
            crAge[q] += rdt
            if (crAge[q] > 0.9f) {
                ncr--
                crX[q] = crX[ncr]; crY[q] = crY[ncr]; crVx[q] = crVx[ncr]; crVy[q] = crVy[ncr]; crAge[q] = crAge[ncr]
            } else {
                crVy[q] += 520f * sc * rdt
                crX[q] += crVx[q] * rdt; crY[q] += crVy[q] * rdt
            }
            q--
        }

        // flame particles
        val tgt = if (mode == Mode.FIRE) ic(target) else null
        var i = nf - 1
        while (i >= 0) {
            fAge[i] += rdt
            if (tgt != null && fKind[i] < 2) {
                // the stream lands in the middle of the icon: pool, splash and spray from there
                val ddx = fX[i] - tgt.cx; val ddy = fY[i] - tgt.cy
                val rr = min(size * 0.34f, min(tgt.r.width(), tgt.r.height()) * 0.3f)
                if (ddx * ddx + ddy * ddy < rr * rr) {
                    fAge[i] += rdt * 3.2f
                    val dmp = max(0f, 1f - rdt * 9f); fVx[i] *= dmp; fVy[i] *= dmp
                    if (Random.nextFloat() < 0.2f) {
                        addSplash(fX[i], fY[i])
                        if (Random.nextFloat() < 0.4f) addSpark(fX[i], fY[i], rnd(0f, TAU), rnd(120f, 360f) * sc)
                    }
                }
            }
            if (fAge[i] >= fLife[i]) {
                if (fKind[i] == 1 && Random.nextFloat() < 0.2f) addSmoke(fX[i], fY[i], 1f)
                nf--
                fX[i] = fX[nf]; fY[i] = fY[nf]; fVx[i] = fVx[nf]; fVy[i] = fVy[nf]; fPx[i] = fPx[nf]; fPy[i] = fPy[nf]
                fAge[i] = fAge[nf]; fLife[i] = fLife[nf]; fSize[i] = fSize[nf]; fSeed[i] = fSeed[nf]; fKind[i] = fKind[nf]
            } else {
                val tt = fAge[i] / fLife[i]
                val wob = sin(fAge[i] * 26f + fSeed[i]) * 300f * sc * tt * (if (fKind[i] == 1) 1f else 0.4f)
                fVx[i] += fPx[i] * wob * rdt; fVy[i] += fPy[i] * wob * rdt - (90f + (if (fKind[i] == 1) 200f * tt else 40f * tt)) * sc * rdt
                fX[i] += fVx[i] * rdt; fY[i] += fVy[i] * rdt
                val k = 0.4f.pow(rdt); fVx[i] *= k; fVy[i] *= k
            }
            i--
        }
        i = nk - 1
        while (i >= 0) {
            kAge[i] += rdt
            if (kAge[i] >= kLife[i]) {
                nk--
                kX[i] = kX[nk]; kY[i] = kY[nk]; kVx[i] = kVx[nk]; kVy[i] = kVy[nk]; kAge[i] = kAge[nk]; kLife[i] = kLife[nk]; kG[i] = kG[nk]
            } else {
                kVy[i] += 520f * sc * kG[i] * rdt
                if (kG[i] < 0f) {                      // floating ash: drag plus a lazy sideways drift
                    val dr = max(0f, 1f - rdt * 1.1f)
                    kVx[i] = kVx[i] * dr + sin(kAge[i] * 3.1f + kY[i] * 0.05f) * 40f * sc * rdt
                    kVy[i] *= dr
                }
                kX[i] += kVx[i] * rdt; kY[i] += kVy[i] * rdt
            }
            i--
        }
        i = ns - 1
        while (i >= 0) {
            sAge[i] += rdt
            if (sAge[i] >= sLife[i]) {
                ns--
                sX[i] = sX[ns]; sY[i] = sY[ns]; sVx[i] = sVx[ns]; sVy[i] = sVy[ns]; sAge[i] = sAge[ns]; sLife[i] = sLife[ns]
                sSize[i] = sSize[ns]; sRot[i] = sRot[ns]; sRv[i] = sRv[ns]; sLit[i] = sLit[ns]; sSeed[i] = sSeed[ns]; sVar[i] = sVar[ns]
            } else {
                val tt = sAge[i] / sLife[i]
                sVx[i] += (sin(sAge[i] * 2.3f + sSeed[i]) * 38f + sin(time * 0.7f + sY[i] * 0.012f) * 30f) * sc * rdt * (0.4f + tt)
                sVy[i] += (-(34f * sc) * (1f - tt * 0.5f) - sVy[i]) * min(1f, rdt * 1.2f)
                sX[i] += sVx[i] * rdt; sY[i] += sVy[i] * rdt; sRot[i] += sRv[i] * rdt
            }
            i--
        }
    }

    /** Redraw only the area that changed. */
    private fun invalidateDirty() {
        val pad = (175f * ds).toInt()
        curBox.set((st.x - pad).toInt(), (st.y - pad).toInt(), (st.x + pad).toInt(), (st.y + pad / 3).toInt())
        for (i in 0 until nf) { val s = (fSize[i] * 3.5f).toInt(); curBox.union((fX[i] - s).toInt(), (fY[i] - s).toInt(), (fX[i] + s).toInt(), (fY[i] + s).toInt()) }
        for (i in 0 until ns) { val s = (sSize[i] * 4.4f).toInt(); curBox.union((sX[i] - s).toInt(), (sY[i] - s).toInt(), (sX[i] + s).toInt(), (sY[i] + s).toInt()) }
        for (i in 0 until nk) curBox.union((kX[i] - 20).toInt(), (kY[i] - 20).toInt(), (kX[i] + 20).toInt(), (kY[i] + 20).toInt())
        if (bugOn) {
            val bx = (if (bugSt == BUG_JUMP) bugAirX else bugWx).toInt(); val by = (if (bugSt == BUG_JUMP) bugAirY else bugWy).toInt(); val g = (size * 0.45f).toInt()
            curBox.union(bx - g, by - g, bx + g, by + g / 3)
        }
        for (c in icons) {
            if (c.burn > 0.01f || c.heat > 0.02f) {
                val g = (size * 0.7f).toInt()
                curBox.union(c.r.left.toInt() - g, c.r.top.toInt() - g, c.r.right.toInt() + g, c.r.bottom.toInt() + g)
            }
        }
        val inv = Rect(curBox)
        inv.union(prevBox)
        prevBox.set(curBox)
        invalidate(inv)
    }

    // ---------- drawing ----------
    override fun onDraw(c: Canvas) {
        if (icons.isEmpty() || fade <= 0.01f) return
        val fe = fade * fade * (3f - 2f * fade)          // smooth start and end of the 0.4 s fade-in
        val layer = fe < 0.995f
        val saved = if (layer) c.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(), (fe * 255f).toInt()) else 0
        for (ic in icons) if (ic.burn > 0.01f || ic.heat > 0.02f) drawBurn(c, ic)
        drawSmoke(c)
        model.draw(c, st)
        drawForage(c)
        drawFlame(c)
        if (layer) c.restoreToCount(saved)
    }

    /** The bug's colours: its random colour, or the dimmed one with a pale belly when it is dead. */
    private fun bugColors(dead: Boolean) {
        if (dead) {
            hsv[0] = bugH; hsv[1] = bugS * 0.45f; hsv[2] = min(1f, bugV * 0.85f); bugFill.color = Color.HSVToColor(hsv)
            hsv[1] = bugS; hsv[2] = bugV * 0.5f; bugEdge.color = Color.HSVToColor(hsv); bugLeg.color = bugEdge.color
        } else {
            hsv[0] = bugH; hsv[1] = bugS; hsv[2] = bugV; bugFill.color = Color.HSVToColor(hsv)
            hsv[1] = bugS * 0.35f; hsv[2] = min(1f, 0.6f + 0.4f * bugV); bugEdge.color = Color.HSVToColor(hsv)
            hsv[1] = bugS; hsv[2] = bugV * 0.45f; bugLeg.color = Color.HSVToColor(hsv)
        }
        bugMark.color = bugLeg.color
    }

    /** A soft shadow on the ground under the bug; smaller and fainter while it is in the air. */
    private fun bugShade(c: Canvas, x: Float, y: Float, lift: Float, k: Float) {
        bugShadow.alpha = (70f * k * (1f - 0.6f * lift)).toInt().coerceIn(0, 255)
        val hw = 6.5f * sc * (1f - 0.3f * lift) * k
        bugR.set(x - hw, y - 0.9f * sc * k, x + hw, y + 0.9f * sc * k)
        c.drawOval(bugR, bugShadow)
    }

    /**
     * The bug seen from above, head to the right: abdomen, thorax and head with a pinch between them, a pattern on the back (plain, stripes or spots,
     * in a darker shade of its colour), a split line for the wing cases, a shine, two eyes, antennae with tips, six legs with knees.
     * Jumping: no spin, the body stays level, the legs are tucked and thin wings open. A small squash when it lands.
     */
    private fun drawBug(c: Canvas) {
        if (bugSt == BUG_DEAD || (bugSt == BUG_FLIP && bugT < D_REVIVE * 0.5f)) { drawDeadBug(c, bugSt == BUG_FLIP); return }
        bugColors(false)
        val jumping = bugSt == BUG_JUMP
        val flipping = bugSt == BUG_FLIP
        val u = if (jumping) min(1f, bugT / bugJumpDur) else 0f
        val px = if (jumping) bugAirX else bugWx
        val py = if (jumping) bugAirY else bugWy
        val face = bugDir
        val pop = max(0.05f, smooth(0f, 0.25f, bugAge))          // pops up when it appears
        val hop = if (flipping) sin(PI.toFloat() * min(1f, bugT / D_REVIVE)) * 4f else if (bugMoving) abs(sin(time * 18f)) * 1.1f else 0f
        val amp = if (jumping) 0f else if (flipping) 1f else if (bugMoving) 0.8f else 0.15f
        val tuck = if (jumping) 1f else 0f
        val sq = if (bugLandT > 0f) sin(PI.toFloat() * (1f - bugLandT / BUG_LAND_S)) else 0f
        bugShade(c, px, if (jumping) bugJy0 + (bugJy1 - bugJy0) * u else py, if (jumping) 4f * u * (1f - u) else 0f, pop)
        c.save()
        c.translate(px, py)
        if (sq > 0f) c.scale(1f + 0.18f * sq, 1f - 0.28f * sq)               // squashes against the ground
        c.translate(0f, -(3.6f + hop) * sc)
        val flipY = if (flipping) -1f + 2f * smooth(D_REVIVE * 0.5f, D_REVIVE, bugT) else 1f      // flips back onto its legs
        c.scale(face * sc * pop, flipY * sc * pop)
        for (k in 0 until 3) {
            val lx = -2.6f + 2.5f * k
            val w = amp * sin(time * 60f + k * 2.1f)
            for (q in 0 until 2) {
                val sg = if (q == 0) -1f else 1f
                val kx = lx - 0.9f + w * 0.8f - 0.8f * tuck; val ky = sg * (3.0f - 0.5f * tuck)
                val fx = lx - 0.3f + w * 1.6f - 1.4f * tuck; val fy = sg * (3.9f - 1.0f * tuck)
                c.drawLine(lx, sg * 1.5f, kx, ky, bugLeg); c.drawLine(kx, ky, fx, fy, bugLeg)
            }
        }
        bugR.set(-6.4f, -2.7f, -0.6f, 2.7f); c.drawOval(bugR, bugFill); c.drawOval(bugR, bugEdge)          // abdomen
        bugR.set(-1.6f, -2.1f, 2.9f, 2.1f); c.drawOval(bugR, bugFill); c.drawOval(bugR, bugEdge)           // thorax
        c.drawCircle(4.1f, 0f, 1.7f, bugFill); c.drawCircle(4.1f, 0f, 1.7f, bugEdge)                       // head
        if (bugPat == 1) {
            c.drawLine(-5.2f, -1.5f, -5.2f, 1.5f, bugLeg); c.drawLine(-3.5f, -2.2f, -3.5f, 2.2f, bugLeg); c.drawLine(-1.8f, -1.5f, -1.8f, 1.5f, bugLeg)
        } else if (bugPat == 2) {
            c.drawCircle(-4.7f, -1.1f, 0.65f, bugMark); c.drawCircle(-4.7f, 1.1f, 0.65f, bugMark); c.drawCircle(-2.8f, 0f, 0.75f, bugMark)
            c.drawCircle(-3.3f, -1.5f, 0.45f, bugMark); c.drawCircle(-3.3f, 1.5f, 0.45f, bugMark)
        }
        c.drawLine(-1.5f, 0f, -5.9f, 0f, bugEdge)                                                          // split between the wing cases
        bugR.set(-4.9f, -2.1f, -2.3f, -1.1f); c.drawOval(bugR, bugShine)
        c.drawCircle(4.7f, -0.9f, 0.55f, bugEye); c.drawCircle(4.7f, 0.9f, 0.55f, bugEye)
        c.drawCircle(4.85f, -1.05f, 0.2f, bugGlint); c.drawCircle(4.85f, 0.75f, 0.2f, bugGlint)
        val fw = sin(time * 22f) * (if (bugMoving || jumping) 0.9f else 0.35f)
        c.drawLine(5.2f, -0.7f, 7.8f, -2.4f + fw, bugLeg); c.drawLine(5.2f, 0.7f, 7.8f, 2.4f - fw, bugLeg)
        c.drawCircle(7.8f, -2.4f + fw, 0.4f, bugMark); c.drawCircle(7.8f, 2.4f - fw, 0.4f, bugMark)
        if (jumping) {
            val fl = sin(time * 70f) * 10f
            for (q in 0 until 2) {
                val sg = if (q == 0) -1f else 1f
                c.save(); c.translate(-0.2f, sg * 1.6f); c.rotate(-sg * (38f + fl))
                bugR.set(-6.2f, -0.9f, 0.2f, 0.9f); c.drawOval(bugR, bugWing); c.drawOval(bugR, bugEdge)
                c.restore()
            }
        }
        c.restore()
    }

    /** Dead (or just waking up): on its back, a pale belly with segment lines, six bent legs up, x eyes, limp antennae; the legs twitch for a moment. */
    private fun drawDeadBug(c: Canvas, waking: Boolean) {
        bugColors(true)
        val face = bugDir
        val tw = if (waking) 1f else max(0f, 1f - bugT / 0.2f)
        bugShade(c, bugWx, bugWy, 0f, 1f)
        c.save()
        c.translate(bugWx, bugWy - 2.4f * sc)
        c.scale(face * sc, sc)
        for (k in 0 until 6) {
            val x0 = -4.2f + 1.45f * k
            val sgn = if (k < 3) -1f else 1f
            val w = tw * sin(time * 50f + k * 1.7f) * 1.1f
            val x1 = x0 + sgn * 0.9f + w
            val x2 = x1 + sgn * 1.5f + w * 0.6f
            c.drawLine(x0, -1.8f, x1, -4.6f, bugLeg)
            c.drawLine(x1, -4.6f, x2, -6.4f, bugLeg)
        }
        bugR.set(-6.4f, -2.5f, -0.6f, 2.7f); c.drawOval(bugR, bugFill); c.drawOval(bugR, bugEdge)
        bugR.set(-1.6f, -2.0f, 2.9f, 2.2f); c.drawOval(bugR, bugFill); c.drawOval(bugR, bugEdge)
        c.drawLine(-5.2f, -1.4f, -5.2f, 1.6f, bugEdge); c.drawLine(-3.6f, -2.0f, -3.6f, 2.2f, bugEdge); c.drawLine(-2.0f, -1.4f, -2.0f, 1.6f, bugEdge)     // belly segments
        c.drawCircle(4.1f, 0.4f, 1.6f, bugFill); c.drawCircle(4.1f, 0.4f, 1.6f, bugEdge)
        for (q in 0 until 2) {
            val ey = if (q == 0) -0.5f else 1.3f
            c.drawLine(4.4f, ey - 0.35f, 5.1f, ey + 0.35f, bugEye); c.drawLine(4.4f, ey + 0.35f, 5.1f, ey - 0.35f, bugEye)             // x eyes
        }
        c.drawLine(5.3f, 0.1f, 7.2f, 1.4f, bugLeg); c.drawLine(5.1f, 0.9f, 6.9f, 2.3f, bugLeg)
        c.drawCircle(7.2f, 1.4f, 0.35f, bugMark); c.drawCircle(6.9f, 2.3f, 0.35f, bugMark)
        c.restore()
    }

    /** The bug (whenever it exists: on its icon or widget, in the air while it jumps, dead until the bite), then crumbs falling from the chewing mouth. */
    private fun drawForage(c: Canvas) {
        if (bugOn) drawBug(c)
        for (k in 0 until ncr) {
            crumbPaint.alpha = (255f * (1f - crAge[k] / 0.9f)).toInt().coerceIn(0, 255)
            c.drawCircle(crX[k], crY[k], 2f * sc, crumbPaint)
        }
    }

    private fun sprite(c: Canvas, b: Bitmap, cx: Float, cy: Float, half: Float, paint: Paint) {
        dest.set(cx - half, cy - half, cx + half, cy + half)
        c.drawBitmap(b, null, dest, paint)
    }

    private fun drawBurn(c: Canvas, ic: Icon) {
        val r = ic.r
        // how hot the icon still is: 1 right after the fire, easing to 0 over about 10 s
        val gl = ic.ember * min(1f, ic.burn * 2f)
        // soft bloom around the icon: strong while the fire hits, a gentle breathing afterglow after
        val bloom = max(ic.heat * 0.8f, gl * 0.26f)
        if (bloom > 0.02f && pq > 0.25f) {
            plusPaint.alpha = (bloom * (0.9f + 0.1f * sin(time * 9f + ic.cx)) * 255f).toInt()
            dest.set(r.left - size * 0.55f, r.top - size * 0.55f, r.right + size * 0.55f, r.bottom + size * 0.55f)
            c.drawBitmap(sBlue, null, dest, plusPaint)
        }
        if (gl <= 0.02f && ic.heat <= 0.02f) return
        val tex = burnTexs[(ic.scorch[0][3] * 0.47f).toInt().coerceIn(0, 2)]
        val flip = if (ic.scorch[1][3] > PI) -1f else 1f
        c.save()
        clip.reset()
        clip.addRoundRect(r, size * 0.24f, size * 0.24f, Path.Direction.CW)
        c.clipPath(clip)
        // white-hot at first, then cyan, then deep blue as it cools; only ever adds light, never darkens
        val g1 = gl * gl * (3f - 2f * gl)
        if (gl > 0.02f) {
            val k = 0.5f + 0.5f * sin(time * 2.6f + ic.cx * 0.01f)
            plusPaint.colorFilter = ramp[((1f - g1) * 11f).toInt().coerceIn(0, 11)]
            c.save()
            c.scale(flip, 1f, ic.cx, ic.cy)
            dest.set(r.left - r.width() * 0.04f, r.top - r.height() * 0.04f, r.right + r.width() * 0.04f, r.bottom + r.height() * 0.04f)
            if (pq > 0.5f) {
                plusPaint.alpha = (g1 * (0.3f + 0.4f * k) * 255f).toInt()
                c.drawBitmap(tex[0], null, dest, plusPaint)
                plusPaint.alpha = (g1 * (0.7f - 0.4f * k) * 255f).toInt()
                c.drawBitmap(tex[1], null, dest, plusPaint)
            } else {
                plusPaint.alpha = (g1 * 0.5f * 255f).toInt()
                c.drawBitmap(tex[0], null, dest, plusPaint)
            }
            c.restore()
            plusPaint.colorFilter = null
            // warm heart of the icon
            val fl = 0.85f + 0.15f * sin(time * 17f + ic.cy * 0.03f)
            plusPaint.alpha = (g1 * 0.5f * fl * 255f).toInt()
            sprite(c, sCyan, ic.cx, ic.cy, size * 0.62f, plusPaint)
        }
        if (ic.heat > 0.02f) {
            // impact: bright blue-white spot where the stream lands
            plusPaint.alpha = (ic.heat * 0.9f * 255f).toInt()
            sprite(c, sCore, ic.cx, ic.cy, size * 0.5f * (0.7f + 0.3f * ic.heat), plusPaint)
        }
        c.restore()
    }

    private fun drawSmoke(c: Canvas) {
        for (i in 0 until ns) {
            val tt = sAge[i] / sLife[i]
            val s = sSize[i] * (1f + tt * 3.2f)
            val env = min(1f, tt * 7f) * (1f - tt).pow(1.5f)
            c.save(); c.translate(sX[i], sY[i]); c.rotate(deg(sRot[i]))
            if (sLit[i] < 0f) {
                // fire-coloured wisp (the dead bug): the flame colours you chose, bright at first, darker as it rises
                spritePaint.colorFilter = ramp[(3f + 8f * tt).toInt().coerceIn(0, 15)]
                spritePaint.alpha = (env * 0.55f * 255f).toInt()
                dest.set(-s, -s, s, s); c.drawBitmap(smkMid[sVar[i]], null, dest, spritePaint)
                spritePaint.colorFilter = null
                c.restore(); continue
            }
            spritePaint.alpha = (env * 0.3f * (1f - tt * 0.7f) * 255f).toInt()
            dest.set(-s, -s, s, s); c.drawBitmap(smkDark[sVar[i]], null, dest, spritePaint)
            if (tt > 0.2f) {
                spritePaint.alpha = (env * 0.6f * tt * 255f).toInt()
                c.drawBitmap(smkMid[sVar[i]], null, dest, spritePaint)
            }
            val lit = sLit[i] * max(0f, 1f - sAge[i] / 0.45f)
            if (lit > 0.15f && pq > 0.4f) {
                plusPaint.alpha = (env * lit * 0.55f * 255f).toInt()
                c.drawBitmap(smkBlue[sVar[i] % 2], null, dest, plusPaint)
            }
            c.restore()
        }
    }

    private var fxCanvas: Canvas? = null
    private var chSpark = 0f                // strength of the electric arcs on the spine: builds while charging, full during the breath, then fades out in 1 s
    private val fxPainter = object : ChargeFx.Painter {
        override fun glow(i: Int, x: Float, y: Float, half: Float, alpha: Float) {
            plusPaint.alpha = a255(alpha); sprite(fxCanvas ?: return, sGrad[i.coerceIn(0, 7)], x, y, half, plusPaint)
        }
        override fun halo(x: Float, y: Float, half: Float, alpha: Float) {
            haloPaint.alpha = a255(alpha); sprite(fxCanvas ?: return, sHalo, x, y, half, haloPaint)
        }
        override fun lines(pts: FloatArray, count: Int, colorIdx: Int, width: Float, alpha: Float) {
            sparkPaint.strokeWidth = width
            sparkPaint.color = (chCol[colorIdx.coerceIn(0, 7)] and 0x00FFFFFF) or (a255(alpha) shl 24)
            (fxCanvas ?: return).drawLines(pts, 0, count, sparkPaint)
        }
    }
    private val chargeFx = ChargeFx(model, fxPainter)

    private fun a255(a: Float) = (clampF(a, 0f, 1f) * 255f).toInt()

    /** Fire charge-up (see ChargeFx): from the tail tip and the 8 wing claw tips along the bones to the shoulders, up the neck into the mouth. */
    private fun drawCharge(c: Canvas) {
        if (chargeT <= 0.05f) return
        val firing = mode == Mode.FIRE
        fxCanvas = c
        // ft = seconds since the breath began, fe = seconds since it ended (after the breath chSpark runs 1 -> 0 over one second)
        val fe = if (firing) fireFt - fireDur else 1f - chSpark
        val ft = if (firing) fireFt else fireDur + fe
        chargeFx.draw(st, ds, time, chargeT, if (firing) clampF(t / chargeT, 0f, 1f) else 1f, ft, fe, fireDur,
            firing && fireFt < 0f, chargeQ, chargeThick)
        fxCanvas = null
    }

    private fun drawFlame(c: Canvas) {
        for (i in 0 until nf) {
            val tt = fAge[i] / fLife[i]
            val kind = fKind[i]
            // grow quickly, then thin out at the end
            val s = fSize[i] * (0.5f + tt * (if (kind == 0) 1.1f else 1.7f)) * (1f - 0.4f * tt * tt)
            val spd = hypot(fVx[i], fVy[i])
            val stretch = 1f + min(1.6f, spd / (520f * sc)) * (if (kind == 2) 0.2f else 1f)
            val v = if (fSeed[i] > PI) 1 else 0
            val fl = 0.86f + 0.14f * sin(fAge[i] * 40f + fSeed[i])
            c.save(); c.translate(fX[i], fY[i]); c.rotate(deg(atan2(fVy[i], fVx[i]))); c.scale(stretch, 1f)
            if (kind == 1 && pq > 0.35f && (i and 1) == 0 && tt < 0.6f) {
                plusPaint.alpha = ((1f - tt) * 0.4f * 255f).toInt()
                sprite(c, sBlue, 0f, 0f, s * 1.7f, plusPaint)
            }
            // body: white sprite tinted along the colour ramp (white-blue, cyan, blue, indigo, dark violet)
            plusPaint.colorFilter = ramp[(tt * 15f).toInt().coerceIn(0, 15)]
            plusPaint.alpha = (min(1f, (1f - tt) * 1.1f) * 0.9f * fl * 255f).toInt()
            sprite(c, fWhite[v], 0f, 0f, s, plusPaint)
            plusPaint.colorFilter = null
            // hot core while the particle is young
            if (tt < 0.38f) {
                plusPaint.alpha = ((1f - tt / 0.38f) * 0.85f * fl * 255f).toInt()
                sprite(c, fCore[v], 0f, 0f, s * 0.55f, plusPaint)
            }
            c.restore()
        }
        sparkPaint.strokeWidth = max(1f, 1.6f * sc)
        for (i in 0 until nk) {
            val a = 1f - kAge[i] / kLife[i]
            if (kG[i] < 0f) {
                // floating ash: a small flickering glow
                val fl = 0.55f + 0.45f * sin(kAge[i] * 14f + kX[i])
                plusPaint.alpha = (a * fl * 0.9f * 255f).toInt()
                sprite(c, sCyan, kX[i], kY[i], 3.2f * sc * (0.6f + 0.4f * a), plusPaint)
            } else {
                sparkPaint.color = Color.argb((a * 255f).toInt(), 190, 230, 255)
                c.drawLine(kX[i], kY[i], kX[i] - kVx[i] * 0.04f, kY[i] - kVy[i] * 0.04f, sparkPaint)
                plusPaint.alpha = (a * 0.8f * 255f).toInt()
                sprite(c, sCore, kX[i], kY[i], 2.6f * sc, plusPaint)
            }
        }
        if (mode == Mode.FIRE || chSpark > 0.01f) drawCharge(c)
        if (mode == Mode.FIRE && fireFt >= 0f && st.mouth > 0.05f) {
            model.mouthPos(st, mp)
            val fl = 0.85f + 0.15f * sin(time * 40f)
            val mf = min(1f, st.mouth * 2.2f)                 // the flame glow keeps its size while the mouth is only 40% open
            val s = size * 1.1f * mf * fl
            plusPaint.alpha = (0.7f * mf * 255f).toInt(); sprite(c, sCyan, mp[0], mp[1], s, plusPaint)
            plusPaint.alpha = (0.9f * mf * 255f).toInt(); sprite(c, sCore, mp[0], mp[1], s * 0.4f, plusPaint)
        }
    }
}
