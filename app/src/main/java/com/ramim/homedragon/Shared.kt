package com.ramim.homedragon

import android.content.Context
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.ColorFilter
import android.graphics.RectF
import android.hardware.display.DisplayManager
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Icon positions found by the accessibility service, shared with the overlay (same process). */
object IconRegistry {
    @Volatile var icons: List<RectF> = emptyList()      // screen pixels
    @Volatile var onHome: Boolean = true                // launcher is the foreground app
    @Volatile var serviceActive: Boolean = false        // accessibility service connected
    @Volatile var launcherPkg: String? = null
    var listener: (() -> Unit)? = null                  // always called on the main thread
    var swipeListener: (() -> Unit)? = null             // launcher page is scrolling sideways (main thread)
    @Volatile var homeWhy: String = ""                 // why the home screen was last judged hidden (for the health log)
    @Volatile var page: Int = 0                        // home screen page the page bar names (0 = no bar seen), set by the icon finder after each settled scan
    @Volatile var pages: Int = 0                       // number of home screen pages the page bar names
    @Volatile var scanNote: String = ""                // the last scan in words, for the health log (page, saved or full, items, time)
    @Volatile var idle: Boolean = false                // screen off or locked: set by the dragon service, the icon finder then runs in light mode
    @Volatile var light: Boolean = false               // the icon finder is in light mode (window changes only), shown in the health card
    var modeChanged: (() -> Unit)? = null               // asks the icon finder to pick light or full mode again (may be called from any thread)
    var recheck: (() -> Unit)? = null                   // asks the icon finder to look again at what is in front (the hidden-dragon poll)
}

/** Flame colours: up to 6, from the hot core to the cooled tip. The default is the Fire palette; the original blue flame is still one of the palettes. */
object FlameColors {
    const val MAX = 6
    /** The original blue flame (it has its own hand-tuned glow and sprites). */
    val BLUE = intArrayOf(Color.rgb(215, 240, 255), Color.rgb(120, 225, 255), Color.rgb(55, 130, 255), Color.rgb(78, 60, 230), Color.rgb(42, 24, 150))
    val FIRE = intArrayOf(Color.rgb(0xFF, 0xF2, 0xC2), Color.rgb(0xFF, 0xC5, 0x3D), Color.rgb(0xFF, 0x7A, 0x1A), Color.rgb(0xE0, 0x3A, 0x12), Color.rgb(0x7A, 0x1A, 0x0C))
    val DEFAULT = FIRE
    private val FIVE_POS = intArrayOf(0, 18, 45, 75, 100)

    fun same(a: IntArray, b: IntArray) = a.size == b.size && a.indices.all { (a[it] and 0xFFFFFF) == (b[it] and 0xFFFFFF) }
    fun isDefault(c: IntArray) = same(c, DEFAULT)
    fun isBlue(c: IntArray) = same(c, BLUE)

    /** Position of colour i of n along the flame, 0..100 (a five-colour palette keeps the original spacing). */
    fun position(n: Int, i: Int): Int = if (n == 5) FIVE_POS[i] else if (n <= 1) 0 else i * 100 / (n - 1)

    /** Colour at t (0 = hot core ... 100 = cooled tip). */
    fun at(cols: IntArray, t: Float): Int {
        val n = cols.size
        if (n == 1) return cols[0] or (0xFF shl 24)
        var j = 0
        while (j < n - 2 && t > position(n, j + 1)) j++
        val a = position(n, j).toFloat(); val b = position(n, j + 1).toFloat()
        val u = ((t - a) / (b - a)).coerceIn(0f, 1f)
        val c0 = cols[j]; val c1 = cols[j + 1]
        return Color.rgb(
            (Color.red(c0) + (Color.red(c1) - Color.red(c0)) * u).toInt(),
            (Color.green(c0) + (Color.green(c1) - Color.green(c0)) * u).toInt(),
            (Color.blue(c0) + (Color.blue(c1) - Color.blue(c0)) * u).toInt()
        )
    }

    /** Move a colour towards white (f = 0..1). */
    fun lighten(c: Int, f: Float) = Color.rgb(
        (Color.red(c) + (255 - Color.red(c)) * f).toInt(),
        (Color.green(c) + (255 - Color.green(c)) * f).toInt(),
        (Color.blue(c) + (255 - Color.blue(c)) * f).toInt()
    )
}

object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("dragon", Context.MODE_PRIVATE)

    /** Every slider moves in steps of 10%: older saved values snap to the nearest step. */
    private fun snap(v: Int, lo: Int, hi: Int) = (((v + 5) / 10) * 10).coerceIn(lo, hi)

    fun chargeTenths(c: Context) = (((sp(c).getInt("charge", 15) + 2) / 5) * 5).coerceIn(0, 30)   // fire charge-up time in tenths of a second, 0 = off
    fun chargeQualityPct(c: Context) = snap(sp(c).getInt("charge_quality", 100), 10, 100)   // charge-up sparks, particles and waves, 10..100
    fun chargeThickPct(c: Context) = snap(sp(c).getInt("charge_thick", 100), 50, 200)       // thickness of the charge-up wave, 50..200
    fun previewFly(c: Context) = sp(c).getBoolean("charge_fly_pose", false)                  // the preview box shows the flying pose (false = sitting)
    fun dragonHue(c: Context) = sp(c).getInt("dragon_hue", 0).coerceIn(0, 359)               // dragon colours: hue turn in degrees, 0 = the original look
    fun dragonSat(c: Context) = sp(c).getInt("dragon_sat", 100).coerceIn(40, 160)            // dragon colours: saturation in percent, 100 = the original look
    fun scalePct(c: Context) = snap(sp(c).getInt("scale", 100), 50, 150)          // dragon size, 50..150
    fun speedPct(c: Context) = snap(sp(c).getInt("speed", 100), 50, 150)          // dragon speed, 50..150
    fun qualityFps(c: Context): Int {                                                // frame rate in real fps, one of the Quality slider's stops (default: the screen's top rate = every vsync)
        val top = FpsStops.topHz(c)
        val v = sp(c).getInt("quality_fps", 0)
        return FpsStops.stops(top).firstOrNull { it.fps == v }?.fps ?: top
    }
    fun particlePct(c: Context) = snap(sp(c).getInt("particles", 100), 10, 100)   // fire, smoke and sparks, 10..100
    fun transparencyPct(c: Context) = snap(sp(c).getInt("transparency", 50), 0, 100)     // whole dragon, 0 = solid .. 100 = barely visible
    /** The wing skin is always 10 points more see-through than the body (same scale, 100 at most): there is no separate wing slider. */
    fun wingOf(body: Int) = min(100, body + 10)
    fun iconArtPx(c: Context) = sp(c).getFloat("icon_art_px", 0f)                  // width of a normal icon's picture on the home screen in pixels, measured by the icon finder (0 = not measured yet)
    fun iconCols(c: Context) = sp(c).getInt("icon_cols", 0)                         // icons per row on the home screen, measured with it
    fun a11yConsent(c: Context) = sp(c).getBoolean("a11y_consent", false)          // user agreed to the Icon finder disclosure
    fun cols(c: Context) = sp(c).getInt("cols", 4)
    fun rows(c: Context) = sp(c).getInt("rows", 6)
    fun enabled(c: Context) = sp(c).getBoolean("enabled", false)
    fun welcomeSeen(c: Context) = sp(c).getBoolean("welcome_seen", false)         // the first-run "what the app asks for" page was closed once

    fun setChargeTenths(c: Context, v: Int) = sp(c).edit().putInt("charge", v).apply()
    fun setChargeQualityPct(c: Context, v: Int) = sp(c).edit().putInt("charge_quality", v).apply()
    fun setChargeThickPct(c: Context, v: Int) = sp(c).edit().putInt("charge_thick", v).apply()
    fun setPreviewFly(c: Context, v: Boolean) = sp(c).edit().putBoolean("charge_fly_pose", v).apply()
    fun setDragonColors(c: Context, hue: Int, sat: Int) {
        val e = sp(c).edit()
        if (hue % 360 == 0 && sat == 100) { e.remove("dragon_hue"); e.remove("dragon_sat") } else { e.putInt("dragon_hue", ((hue % 360) + 360) % 360); e.putInt("dragon_sat", sat) }
        e.apply()
    }
    fun setScalePct(c: Context, v: Int) = sp(c).edit().putInt("scale", v).apply()
    fun setSpeedPct(c: Context, v: Int) = sp(c).edit().putInt("speed", v).apply()
    fun setQualityFps(c: Context, v: Int) = sp(c).edit().putInt("quality_fps", v).apply()
    fun setIconSize(c: Context, artPx: Float, cols: Int) = sp(c).edit().putFloat("icon_art_px", artPx).putInt("icon_cols", cols).apply()
    /** Settings that older versions saved and no longer exist (frame rate in percent, the separate wing transparency). */
    fun dropOldKeys(c: Context) = sp(c).edit().remove("quality").remove("wing_transparency").apply()
    fun setParticlePct(c: Context, v: Int) = sp(c).edit().putInt("particles", v).apply()
    fun setTransparencyPct(c: Context, v: Int) = sp(c).edit().putInt("transparency", v).apply()
    fun setA11yConsent(c: Context, v: Boolean) = sp(c).edit().putBoolean("a11y_consent", v).apply()
    fun setEnabled(c: Context, v: Boolean) = sp(c).edit().putBoolean("enabled", v).apply()
    fun setWelcomeSeen(c: Context, v: Boolean) = sp(c).edit().putBoolean("welcome_seen", v).apply()

    /** Saved flame colours (hex list), the original blue ramp when nothing was saved. */
    fun flameColors(c: Context): IntArray {
        val raw = sp(c).getString("flame", null) ?: return FlameColors.DEFAULT.copyOf()
        val out = ArrayList<Int>()
        for (part in raw.split(',')) {
            try { out.add(Color.parseColor("#" + part.trim())) } catch (_: Throwable) {}
        }
        return if (out.isEmpty() || out.size > FlameColors.MAX) FlameColors.DEFAULT.copyOf() else out.toIntArray()
    }
    fun setFlameColors(c: Context, cols: IntArray) {
        val e = sp(c).edit()
        if (FlameColors.isDefault(cols)) e.remove("flame") else e.putString("flame", cols.joinToString(",") { String.format("%06X", it and 0xFFFFFF) })
        e.apply()
    }
}

/**
 * Colours of the dragon's body. One colour filter (a hue turn plus a saturation change) is laid over everything the dragon model draws,
 * so every gradient follows; the eye, the mouth inside and the flame glow are left out and keep their colours. hue 0 and saturation 100
 * is the original look (no filter at all). Shared by the dragon on the home screen and the preview box (same process).
 */
object DragonPalette {
    @Volatile var filter: ColorFilter? = null
        private set
    @Volatile var hue = 0
        private set
    @Volatile var sat = 100
        private set

    /** Reads the saved colours; call once when the process starts. */
    fun load(c: Context) { use(Prefs.dragonHue(c), Prefs.dragonSat(c)) }

    /** Takes a palette into use (not saved). */
    fun use(h: Int, s: Int) {
        hue = ((h % 360) + 360) % 360; sat = s.coerceIn(40, 160)
        filter = if (hue == 0 && sat == 100) null else ColorMatrixColorFilter(matrix(hue, sat))
    }

    private const val HISTORY = 10
    private val history = ArrayDeque<IntArray>()          // the colours before each change: (hue, saturation), newest last, in memory only

    /** True when the Back button has an earlier colour to go to. */
    fun canBack() = history.isNotEmpty()

    private fun remember() {
        history.addLast(intArrayOf(hue, sat))
        while (history.size > HISTORY) history.removeFirst()
    }

    /** A new random palette: hue turned 40..320 degrees, saturation 85..125%. Saved. The colour it replaces goes into the Back list. */
    fun randomize(c: Context) {
        val h = 40 + (Math.random() * 280).toInt()
        val s = 85 + (Math.random() * 40).toInt()
        remember()
        use(h, s); Prefs.setDragonColors(c, hue, sat)
    }

    /** One step back through the colours before the last changes (up to 10). Saved. Returns false when there is nothing to go back to. */
    fun back(c: Context): Boolean {
        val prev = history.removeLastOrNull() ?: return false
        use(prev[0], prev[1]); Prefs.setDragonColors(c, hue, sat)
        return true
    }

    /** Back to the original colours. Saved. The colour it replaces goes into the Back list. */
    fun reset(c: Context) {
        if (hue != 0 || sat != 100) remember()
        use(0, 100); Prefs.setDragonColors(c, 0, 100)
    }

    private fun matrix(h: Int, s: Int): ColorMatrix {
        val a = Math.toRadians(h.toDouble()); val cs = cos(a).toFloat(); val sn = sin(a).toFloat()
        val lr = 0.213f; val lg = 0.715f; val lb = 0.072f
        val rot = ColorMatrix(floatArrayOf(
            lr + cs * (1 - lr) + sn * -lr, lg + cs * -lg + sn * -lg, lb + cs * -lb + sn * (1 - lb), 0f, 0f,
            lr + cs * -lr + sn * 0.143f, lg + cs * (1 - lg) + sn * 0.140f, lb + cs * -lb + sn * -0.283f, 0f, 0f,
            lr + cs * -lr + sn * -(1 - lr), lg + cs * -lg + sn * lg, lb + cs * (1 - lb) + sn * lb, 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        ))
        val satM = ColorMatrix(); satM.setSaturation(s / 100f)
        rot.postConcat(satM)
        return rot
    }
}

/**
 * The Quality slider's stops, in real frames per second. "Even" stops divide the screen's refresh rate exactly (every 1st, 2nd, 3rd ... vsync),
 * so the frames come at an even pace. The in-between stops do not divide evenly: some frames last longer than others and the motion can judder.
 */
object FpsStops {
    class Stop(val fps: Int, val even: Boolean)

    /** The screen's top refresh rate at its current resolution (the same on every call, unlike the rate the screen runs at this moment). */
    fun topHz(c: Context): Int = try {
        val d = (c.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).getDisplay(0)
        val cur = d.mode
        val best = d.supportedModes.filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight }
            .maxOfOrNull { it.refreshRate } ?: d.refreshRate
        Math.round(best).coerceIn(30, 240)
    } catch (_: Throwable) {
        60
    }

    /** All stops for a screen of [hz], lowest first. */
    fun stops(hz: Int): List<Stop> {
        val even = ArrayList<Int>()
        for (n in 1..10) if (hz % n == 0 && hz / n >= 9) even.add(hz / n)       // every n-th vsync, not below 9 fps
        val grey = when (hz) {
            120 -> listOf(80, 90, 100)
            90 -> listOf(60, 75)
            60 -> listOf(40, 50)
            else -> listOf(hz * 2 to 3, hz * 5 to 6).mapNotNull { (n, d) -> if (n % d == 0) n / d else null }   // two thirds and five sixths, when whole
        }.filter { it !in even }
        return (even.map { Stop(it, true) } + grey.map { Stop(it, false) }).sortedBy { it.fps }
    }
}
