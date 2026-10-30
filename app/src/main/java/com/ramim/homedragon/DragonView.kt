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
private const val BUG_FOLLOW_WAIT = 0.2f      // the dragon looks for this long, then takes off after a bug that jumped on
private const val BUG_REJUMP = 0.6f          // chance that the bug jumps on again when the dragon lands next to it
private const val BUG_SPAWN_MIN = 8f         // it appears at a random time, every 19 s on average (counted while the dragon is awake and idle)
private const val BUG_SPAWN_MAX = 30f
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
        var pad = 0f             // widget whose rectangle covers whole grid cells: the visible card is this much narrower on each side
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
    private val nest = NestLife()
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
    private var bugSpawnT = 10f            // seconds (awake and idle) until the next bug appears
    private var bugNoticeT = 2f            // idle seconds until the dragon notices the bug: 1 to 3
    private var bugJumpT = 3f              // seconds until the bug jumps to another icon or widget by itself
    private var huntOn = false             // the dragon is after the bug (flying to it, or next to it)
    private var stalkNow = false           // the dragon is running along a widget after the bug
    private var padX = 0f                  // the finder's rectangle of a widget or big folder is wider than the visible card: this much on each side (see updatePad)
    private var wbLo = 0f; private var wbHi = 0f                 // where the dragon may stand on a big surface, world x (see walkBounds)
    private var lastGroundLog = ""
    private val logExec by lazy { java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "dragon-log").apply { isDaemon = true } } }
    /** Writes a Log line on a background thread: Diag.log waits for the disk, and this is called from the frame loop. */
    private fun dlog(msg: String) { val c = context.applicationContext; logExec.execute { Diag.log(c, msg) } }
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
    private val bugHornP = Path()
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
            val hot = smooth(0.TRUNCATED_FOR_BREVITY_BUT_FULL_FILE_IS_IN_TMP
        }
    }
}
