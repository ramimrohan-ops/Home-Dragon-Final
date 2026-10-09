package com.ramim.homedragon

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.util.Log
import android.graphics.Typeface
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.io.File

/**
 * Simple dark home screen for the app: a big start/stop button, five sliders (quality, particles, size and charge-up have preview boxes)
 * (quality, dragon size, dragon speed) that change the dragon live, a flame colour picker, and three setup rows.
 */
class MainActivity : Activity() {

    private companion object {
        val BG = Color.parseColor("#0A0F1E")
        val CARD = Color.parseColor("#121A30")
        val STROKE = Color.parseColor("#223152")
        val TRACK = Color.parseColor("#26365A")
        val FG = Color.parseColor("#EAF2FF")
        val MUTED = Color.parseColor("#8A9BBD")
        val TEAL = Color.parseColor("#2DD4BF")
        val BLUE = Color.parseColor("#3B82F6")
        val ORANGE = Color.parseColor("#FF9A3C")
        val RED = Color.parseColor("#F0634F")
        val GREEN = Color.parseColor("#3DDC97")
    }

    private lateinit var pill: TextView
    private lateinit var toggle: TextView
    private lateinit var healthText: TextView
    private val setupRows = ArrayList<SetupRow>()
    private val sliders = ArrayList<Slider>()
    private var flamePreview: PreviewView? = null
    private var logTab = false
    private lateinit var scrollView: ScrollView
    private lateinit var pageMainView: LinearLayout
    private lateinit var setupHint: TextView
    private var setupCard: LinearLayout? = null
    private var selectTabFn: ((Boolean) -> Unit)? = null
    private var hlAnim: android.animation.ValueAnimator? = null
    private var welcome: android.app.Dialog? = null
    private var setupDone = false

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun shape(fill: Int, radius: Int, stroke: Int = 0) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radius).toFloat()
        if (stroke != 0) setStroke(dp(1), stroke)
    }

    private fun gradient(a: Int, b: Int, radius: Int) =
        GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(a, b)).apply {
            cornerRadius = dp(radius).toFloat()
        }

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = shape(CARD, 18, STROKE)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(12) }
    }

    private fun divider() = View(this).apply {
        setBackgroundColor(STROKE)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
            .apply { topMargin = dp(12); bottomMargin = dp(12) }
    }

    // ---------------------------------------------------------------- slider row

    private inner class Slider(
        title: String, hint: String, val min: Int, val max: Int, start: Int,
        private val save: (Int) -> Unit, private val live: () -> Unit, private val step: Int = 1,
        private val previews: List<PreviewView> = emptyList(), previewDp: Int = 150,
        private val def: Int = 100, private val showPreviews: Boolean = true,
        private val fmt: (Int) -> String = { "$it%" }
    ) {
        val value = text(fmt(start), 15f, TEAL, true)
        val seek = SeekBar(this@MainActivity)
        val view = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }

        init {
            val head = LinearLayout(this@MainActivity).apply { gravity = Gravity.CENTER_VERTICAL }
            head.addView(text(title, 15f, FG, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            head.addView(value)
            view.addView(head)
            view.addView(text(hint, 11f, MUTED))

            if (!showPreviews) {
                // the preview box sits elsewhere in the layout
            } else if (previews.size > 1) {
                // several boxes sit side by side
                val row = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
                previews.forEachIndexed { i, p ->
                    p.setValue(start)
                    row.addView(p, LinearLayout.LayoutParams(0, dp(previewDp), 1f).apply { if (i > 0) leftMargin = dp(8) })
                }
                view.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = dp(10) })
            } else {
                for (p in previews) {
                    p.setValue(start)
                    view.addView(p, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(previewDp))
                        .apply { topMargin = dp(10) })
                }
            }

            seek.max = (max - min) / step
            try {
                val track = GradientDrawable().apply { setColor(TRACK); cornerRadius = dp(4).toFloat(); setSize(0, dp(6)) }
                val fill = ClipDrawable(
                    gradient(TEAL, BLUE, 3).apply { setSize(0, dp(6)) }, Gravity.START, ClipDrawable.HORIZONTAL
                )
                val layers = LayerDrawable(arrayOf<android.graphics.drawable.Drawable>(track, fill))
                layers.setId(0, android.R.id.background)
                layers.setId(1, android.R.id.progress)
                val knob = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.WHITE)
                    setStroke(dp(4), TEAL)
                    setSize(dp(24), dp(24))
                }
                seek.progressDrawable = layers
                seek.thumb = knob
                seek.setPadding(dp(12), dp(8), dp(12), dp(8))
            } catch (_: Throwable) {
                // stock slider look is fine as a fallback
            }
            seek.progress = ((start - min + step / 2) / step).coerceIn(0, (max - min) / step)
            seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                    val v = p * step + min
                    value.text = fmt(v)
                    previews.forEach { it.setValue(v) }
                    if (fromUser) { save(v); live() }
                }
                override fun onStartTrackingTouch(s: SeekBar) {
                    s.parent?.requestDisallowInterceptTouchEvent(true)
                    previews.forEach { it.start() }               // only the preview boxes move
                }
                override fun onStopTrackingTouch(s: SeekBar) {
                    previews.forEach { it.stop() }
                    live()
                }
            })
            view.addView(seek, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(4) })
        }

        fun reset() { seek.progress = (def - min) / step; save(def); live() }

        fun release() { previews.forEach { it.stop() } }
    }

    // ---------------------------------------------------------------- setup row

    private inner class SetupRow(title: String, desc: String, private val onClick: () -> Unit) {
        val state = text("", 13f, MUTED, true)
        val view = LinearLayout(this@MainActivity).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(7), 0, dp(7))
            isClickable = true
            setOnClickListener { stopHighlight(); onClick() }
        }

        init {
            val col = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
            col.addView(text(title, 14f, FG, true))
            col.addView(text(desc, 11f, MUTED))
            view.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            view.addView(state)
        }

        fun update(ok: Boolean) {
            state.text = if (ok) "●  On" else "Set up  ›"
            state.setTextColor(if (ok) GREEN else ORANGE)
        }
    }

    // ---------------------------------------------------------------- screen

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // If the app crashed last time, show the error instead of the normal screen (App.kt saved it).
        val saved = File(filesDir, "crash.txt")
        if (saved.exists()) {
            val trace = try { saved.readText() } catch (_: Throwable) { "unreadable crash file" }
            try { saved.delete() } catch (_: Throwable) {}
            showError("The app crashed last time:", trace)
            return
        }
        try {
            buildUi()
        } catch (t: Throwable) {
            showError("The screen could not be built:", Log.getStackTraceString(t))
        }
    }

    /** Plain, dependency-free error page so a crash can be read and screenshotted. */
    private fun showError(title: String, trace: String) {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(40), dp(16), dp(24))
            setBackgroundColor(Color.parseColor("#0A0F1E"))
        }
        col.addView(TextView(this).apply {
            text = "Home Dragon\n$title"
            textSize = 18f
            setTextColor(Color.WHITE)
        })
        col.addView(TextView(this).apply {
            text = trace.take(3500)
            textSize = 11f
            setTextColor(Color.parseColor("#FFB4A8"))
            setTextIsSelectable(true)
            setPadding(0, dp(12), 0, dp(12))
        })
        col.addView(TextView(this).apply {
            text = "Open the app anyway"
            textSize = 16f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#2B6CFF"))
            setPadding(0, dp(14), 0, dp(14))
            setOnClickListener { recreate() }
        })
        setContentView(ScrollView(this).apply { setBackgroundColor(Color.parseColor("#0A0F1E")); addView(col) })
    }

    @Suppress("DEPRECATION")
    private fun buildUi() {
        window.statusBarColor = BG
        window.navigationBarColor = BG
        requestHighRefresh()

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(24))
        }

        // header: badge, title and status in one row
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val badge = TextView(this).apply {
            text = "🐉"
            textSize = 22f
            gravity = Gravity.CENTER
            background = gradient(TEAL, BLUE, 14)
        }
        header.addView(badge, LinearLayout.LayoutParams(dp(44), dp(44)))
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0) }
        titles.addView(text("Home Dragon", 21f, FG, true))
        titles.addView(text("A live pet dragon for your home screen", 11.5f, MUTED))
        header.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        pill = text("", 12f, MUTED, true).apply { setPadding(dp(12), dp(5), dp(12), dp(5)) }
        header.addView(pill)
        col.addView(header)

        toggle = text("", 16f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            isClickable = true
            setOnClickListener { toggleService() }
        }
        col.addView(toggle, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50))
            .apply { topMargin = dp(14) })

        // settings
        val settings = card()
        val sHead = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        sHead.addView(text("Dragon settings", 12f, MUTED, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val reset = text("Reset all", 12f, ORANGE, true).apply {
            setPadding(dp(8), dp(4), 0, dp(4))
            isClickable = true
            setOnClickListener { sliders.forEach { it.reset() } }
        }
        sHead.addView(reset)
        settings.addView(sHead)
        settings.addView(View(this), LinearLayout.LayoutParams(1, dp(8)))

        val quality = Slider(
            "Quality (frame rate)", "Steps of 10%. 100% = full refresh rate. Lower = less battery.",
            10, 100, Prefs.qualityPct(this), { Prefs.setQualityPct(this, it) }, { DragonService.instance?.view?.reloadSettings() },
            step = 10, previews = listOf(PreviewView(this, PreviewView.QUALITY), PreviewView(this, PreviewView.FLYING)), previewDp = 150
        )
        val particles = Slider(
            "Particles", "Fire, smoke and sparks. Separate from Quality.",
            10, 100, Prefs.particlePct(this), { Prefs.setParticlePct(this, it) }, { DragonService.instance?.view?.reloadSettings() },
            step = 10, previews = listOf(PreviewView(this, PreviewView.PARTICLES)), previewDp = 150
        )
        val size = Slider(
            "Dragon size", "How big the dragon is on your icons.",
            50, 150, Prefs.scalePct(this), { Prefs.setScalePct(this, it) }, { DragonService.instance?.view?.reloadPrefs() },
            step = 10, previews = listOf(PreviewView(this, PreviewView.SIZE)), previewDp = 170
        )
        val speed = Slider(
            "Dragon speed", "How fast it flies, walks and breathes fire.",
            50, 150, Prefs.speedPct(this), { Prefs.setSpeedPct(this, it) }, { DragonService.instance?.view?.reloadSettings() },
            step = 10
        )
        val charge = Slider(
            "Charge-up time", "Glow that builds before every fire breath, in your flame colours. Off = no charge-up.",
            0, 30, Prefs.chargeTenths(this), { Prefs.setChargeTenths(this, it) }, { DragonService.instance?.view?.reloadSettings() },
            step = 5, previews = listOf(PreviewView(this, PreviewView.CHARGE)), previewDp = 150, def = 15,
            fmt = { if (it == 0) "Off" else String.format("%.1f s", it / 10f) }
        )
        sliders.addAll(listOf(quality, particles, size, speed, charge))
        settings.addView(quality.view)
        settings.addView(divider()); settings.addView(particles.view)
        settings.addView(divider()); settings.addView(size.view)
        settings.addView(divider()); settings.addView(speed.view)
        settings.addView(divider()); settings.addView(charge.view)
        col.addView(settings)

        // visual: flame colours (live preview + picker, blue is the default) and the two transparency sliders
        val flame = card()
        flame.addView(text("Visual", 12f, MUTED, true))
        flame.addView(View(this), LinearLayout.LayoutParams(1, dp(8)))
        val fHead = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        fHead.addView(text("Flame colours", 15f, FG, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        flame.addView(fHead)
        flame.addView(text("Up to 6 colours, blended from the hot core to the cooled tip. Blue is the default.", 11f, MUTED))
        val fPrev = PreviewView(this, PreviewView.PARTICLES)
        flamePreview = fPrev
        flame.addView(fPrev, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(150)).apply { topMargin = dp(10) })
        val picker = FlamePicker(this) { cols, done ->
            Prefs.setFlameColors(this, cols)
            fPrev.reloadFlame()
            if (done) DragonService.instance?.view?.reloadFlame()
        }
        val fReset = text("Reset to blue", 12f, ORANGE, true).apply {
            setPadding(dp(8), dp(4), 0, dp(4))
            isClickable = true
            setOnClickListener { picker.reset() }
        }
        fHead.addView(fReset)
        flame.addView(picker.view)

        val seePrev = PreviewView(this, PreviewView.SEETHROUGH)
        seePrev.setTransparency(Prefs.transparencyPct(this), Prefs.wingTransPct(this))
        val seeLive = {
            seePrev.setTransparency(Prefs.transparencyPct(this), Prefs.wingTransPct(this))
            DragonService.instance?.view?.reloadSettings()
            Unit
        }
        val transp = Slider(
            "Transparency", "Body, bones and claws. 0% solid, 100% barely visible.",
            0, 100, Prefs.transparencyPct(this), { Prefs.setTransparencyPct(this, it) }, seeLive,
            step = 10, previews = listOf(seePrev), def = 50, showPreviews = false
        )
        val wingTransp = Slider(
            "Wing transparency", "Only the thin wing skin. 0% solid.",
            0, 100, Prefs.wingTransPct(this), { Prefs.setWingTransPct(this, it) }, seeLive,
            step = 10, previews = listOf(seePrev), def = 65, showPreviews = false
        )
        sliders.addAll(listOf(transp, wingTransp))
        // sliders on the left, one preview box on the right
        val seeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val seeLeft = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        seeLeft.addView(transp.view); seeLeft.addView(divider()); seeLeft.addView(wingTransp.view)
        seeRow.addView(seeLeft, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.1f))
        seeRow.addView(seePrev, LinearLayout.LayoutParams(0, dp(220), 0.9f).apply { leftMargin = dp(12) })
        flame.addView(divider()); flame.addView(seeRow)
        col.addView(flame)

        // setup rows
        val setup = card()
        setupCard = setup
        val setupHead = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        setupHead.addView(text("Setup", 12f, MUTED, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        setupHead.addView(text("What does it ask for?", 12f, TEAL, true).apply {
            setPadding(dp(8), dp(4), 0, dp(4))
            isClickable = true
            setOnClickListener { stopHighlight(); showWelcome() }
        })
        setup.addView(setupHead)
        val r1 = SetupRow("Draw over other apps", "Lets the dragon appear on your home screen") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        val r2 = SetupRow("Icon finder", "Lets the dragon see where your icons are") { onIconFinderTapped() }
        val r3 = SetupRow("Background running", "Keeps the dragon alive when the screen is off") { openBackgroundSettings() }
        setupRows.addAll(listOf(r1, r2, r3))
        setup.addView(r1.view); setup.addView(r2.view); setup.addView(r3.view)
        setupHint = text("\uD83D\uDC47  Start here: tap each row to turn it on", 13f, ORANGE, true).apply {
            visibility = View.GONE
            setPadding(dp(4), dp(12), 0, 0)
        }
        col.addView(setupHint)
        col.addView(setup)

        // health: what the dragon service and the icon finder are doing, and what happened around the last lock (helps on Samsung)
        val health = card()
        val hHead = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        hHead.addView(text("Dragon health", 12f, MUTED, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        hHead.addView(text("Copy log", 12f, TEAL, true).apply {
            setPadding(dp(8), dp(4), dp(8), dp(4))
            isClickable = true
            setOnClickListener {
                try {
                    val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("Home Dragon log", "Home Dragon " + (try { packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Throwable) { "" }) + "\n" + healthText.text))
                    Toast.makeText(this@MainActivity, "Log copied", Toast.LENGTH_SHORT).show()
                } catch (_: Throwable) {
                    Toast.makeText(this@MainActivity, "Could not copy", Toast.LENGTH_SHORT).show()
                }
            }
        })
        hHead.addView(text("Clear log", 12f, ORANGE, true).apply {
            setPadding(dp(8), dp(4), 0, dp(4))
            isClickable = true
            setOnClickListener { Diag.clear(this@MainActivity); refresh() }
        })
        health.addView(hHead)
        healthText = text("", 11.5f, FG).apply {
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(0, dp(6), 0, 0)
        }
        health.addView(healthText)
        col.addView(health)

        // two tabs under the header: "Dragon" (everything above) and "Log" (the health card), so the main screen stays short
        val pageMain = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val pageLog = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        val kids = ArrayList<View>()
        for (i in 1 until col.childCount) kids.add(col.getChildAt(i))
        col.removeViews(1, col.childCount - 1)
        for (k in kids) if (k === health) pageLog.addView(k) else pageMain.addView(k)
        val tabDragon = text("Dragon", 14f, FG, true).apply { gravity = Gravity.CENTER; isClickable = true }
        val tabLog = text("Log", 14f, MUTED, true).apply { gravity = Gravity.CENTER; isClickable = true }
        val tabBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = shape(CARD, 14, STROKE)
            setPadding(dp(4), dp(4), dp(4), dp(4))
            addView(tabDragon, LinearLayout.LayoutParams(0, dp(38), 1f))
            addView(tabLog, LinearLayout.LayoutParams(0, dp(38), 1f))
        }
        var tabsReady = false
        fun selectTab(log: Boolean) {
            logTab = log
            pageMain.visibility = if (log) View.GONE else View.VISIBLE
            pageLog.visibility = if (log) View.VISIBLE else View.GONE
            tabDragon.background = if (log) null else shape(STROKE, 10)
            tabLog.background = if (log) shape(STROKE, 10) else null
            tabDragon.setTextColor(if (log) MUTED else FG)
            tabLog.setTextColor(if (log) FG else MUTED)
            try {
                if (!tabsReady) return
                if (log) { sliders.forEach { it.release() }; flamePreview?.stop(); refresh() } else flamePreview?.start()
            } catch (_: Throwable) {
            }
        }
        selectTabFn = { l -> selectTab(l) }
        pageMainView = pageMain
        tabDragon.setOnClickListener { selectTab(false) }
        tabLog.setOnClickListener { selectTab(true) }
        selectTab(false)
        tabsReady = true
        col.addView(tabBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) })
        col.addView(pageMain)
        col.addView(pageLog)

        val scroll = ScrollView(this).apply {
            setBackgroundColor(BG)
            isFillViewport = true
            addView(col)
            // Android 15 draws apps edge to edge: keep the content clear of the status and navigation bars
            setOnApplyWindowInsetsListener { v, ins ->
                if (Build.VERSION.SDK_INT >= 30) {
                    val b = ins.getInsets(WindowInsets.Type.systemBars())
                    v.setPadding(b.left, b.top, b.right, b.bottom)
                } else {
                    v.setPadding(ins.systemWindowInsetLeft, ins.systemWindowInsetTop, ins.systemWindowInsetRight, ins.systemWindowInsetBottom)
                }
                ins
            }
        }
        scrollView = scroll
        setContentView(scroll)
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                window.insetsController?.setSystemBarsAppearance(
                    0, android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                        android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                )
            } catch (_: Throwable) {
            }
        }

        // first-run page (or the notification question) once the window is up
        window.decorView.post { startFlow() }
    }

    private fun askNotifications() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    /** First open after installing: show the "what the app asks for" page. Skipped when setup is already finished or the icon finder disclosure is due. */
    private fun startFlow() {
        try {
            if (Prefs.welcomeSeen(this) || intent?.getBooleanExtra("a11y_disclosure", false) == true) {
                askNotifications()
                return
            }
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (Settings.canDrawOverlays(this) && a11yEnabled() && pm.isIgnoringBatteryOptimizations(packageName)) {
                Prefs.setWelcomeSeen(this, true)       // updated from an older version with everything set up: nothing to explain
                askNotifications()
            } else {
                showWelcome()
            }
        } catch (_: Throwable) {
            askNotifications()
        }
    }

    // ---------------------------------------------------------------- welcome page and setup highlight

    private fun showWelcome() {
        if (welcome != null) return
        Diag.log(this, "Welcome page shown")
        val dlg = android.app.Dialog(this, android.R.style.Theme_DeviceDefault_NoActionBar)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            setOnApplyWindowInsetsListener { v, ins ->
                if (Build.VERSION.SDK_INT >= 30) {
                    val b = ins.getInsets(WindowInsets.Type.systemBars())
                    v.setPadding(b.left, b.top, b.right, b.bottom)
                } else {
                    v.setPadding(ins.systemWindowInsetLeft, ins.systemWindowInsetTop, ins.systemWindowInsetRight, ins.systemWindowInsetBottom)
                }
                ins
            }
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(22), dp(18), dp(8))
        }
        val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(TextView(this).apply {
            text = "\uD83D\uDC09"
            textSize = 26f
            gravity = Gravity.CENTER
            background = gradient(TEAL, BLUE, 16)
        }, LinearLayout.LayoutParams(dp(54), dp(54)))
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, 0, 0) }
        titles.addView(text("Before you start", 22f, FG, true))
        titles.addView(text("Home Dragon needs a few things from your phone. Here is what, and why.", 12.5f, MUTED))
        head.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        body.addView(head)

        fun item(emoji: String, tint: String, title: String, desc: String) {
            val card = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                background = shape(CARD, 16, STROKE)
                setPadding(dp(12), dp(11), dp(14), dp(11))
            }
            card.addView(TextView(this).apply {
                text = emoji
                textSize = 18f
                gravity = Gravity.CENTER
                background = shape(Color.parseColor(tint), 20)
            }, LinearLayout.LayoutParams(dp(40), dp(40)))
            val t = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0) }
            t.addView(text(title, 14.5f, FG, true))
            t.addView(text(desc, 12f, MUTED).apply { setPadding(0, dp(2), 0, 0) })
            card.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            body.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        }
        item("\uD83E\uDE9F", "#1B3A5F", "Draw over other apps", "A pop-up window permission. It lets the dragon appear on top of your home screen.")
        item("\uD83D\uDD0D", "#3A2D5F", "Icon finder (Accessibility)", "Finds where your icons are. Android may call it a restricted setting; the app shows you the steps. It reads positions only, never your text.")
        item("\uD83D\uDD0B", "#1E4A3A", "Background running", "Keeps the dragon alive when the screen is off. You will be asked to let it run without battery limits.")
        item("\uD83D\uDD14", "#5A3F1B", "Notification", "Android requires a small notification while the dragon is running.")
        item("\uD83D\uDD04", "#4A2A3A", "One restart", "At the end, restarting once helps the phone pick up the settings.")
        body.addView(text("\uD83D\uDD12  No internet access. No data collected. Nothing leaves your phone.", 12f, TEAL, true).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, dp(4))
        })

        val scroller = ScrollView(this).apply { addView(body) }
        root.addView(scroller, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(text("Got it, start setup", 16f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            isClickable = true
            background = gradient(TEAL, BLUE, 18)
            setOnClickListener { dlg.dismiss() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply {
            setMargins(dp(18), dp(8), dp(18), dp(16))
        })

        dlg.setContentView(root)
        dlg.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(BG))
        dlg.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        dlg.setCancelable(true)
        dlg.setOnDismissListener { welcome = null; onWelcomeClosed() }       // button and back button both count as closing
        welcome = dlg
        dlg.show()
    }

    private fun onWelcomeClosed() {
        Prefs.setWelcomeSeen(this, true)
        Diag.log(this, "Welcome page closed")
        selectTabFn?.invoke(false)
        try { refresh() } catch (_: Throwable) {}
        highlightSetup(true)
        window.decorView.postDelayed({ askNotifications() }, 700)
    }

    /** Pulsing orange outline around the whole Setup card, with a label above it; optionally scrolls it into view first. */
    private fun highlightSetup(scrollTo: Boolean) {
        val card = setupCard ?: return
        stopHighlight()
        setupHint.visibility = View.VISIBLE
        val bg = GradientDrawable().apply { setColor(CARD); cornerRadius = dp(18).toFloat() }
        card.background = bg
        val a = android.animation.ValueAnimator.ofInt(70, 255).apply {
            duration = 600
            repeatMode = android.animation.ValueAnimator.REVERSE
            repeatCount = 7                                   // about 5 seconds
            addUpdateListener { v -> bg.setStroke(dp(3), Color.argb(v.animatedValue as Int, 255, 154, 60)) }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) { stopHighlight() }
            })
        }
        hlAnim = a
        a.start()
        if (scrollTo) {
            scrollView.post {
                val y = (pageMainView.top + setupHint.top - dp(6)).coerceAtLeast(0)
                scrollView.smoothScrollTo(0, y)
            }
        }
    }

    private fun stopHighlight() {
        hlAnim?.removeAllListeners()
        hlAnim?.cancel()
        hlAnim = null
        setupCard?.background = shape(CARD, 18, STROKE)
        if (::setupHint.isInitialized) setupHint.visibility = View.GONE
    }

    override fun onPause() {
        stopHighlight()
        // leaving the app: stop the previews and let the home-screen dragon come back
        try {
            sliders.forEach { it.release() }
            flamePreview?.stop()
            DragonService.appOpen = false
            DragonService.instance?.refreshHold()
        } catch (_: Throwable) {
        }
        super.onPause()
    }

    /** Ask for the display's highest refresh rate for this screen, so the previews can run at full speed. */
    @Suppress("DEPRECATION")
    private fun requestHighRefresh() {
        try {
            val d = windowManager.defaultDisplay
            val cur = d.mode
            val best = d.supportedModes
                .filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight }
                .maxByOrNull { it.refreshRate }
            if (best != null) {
                val lp = window.attributes
                lp.preferredDisplayModeId = best.modeId
                window.attributes = lp
            }
        } catch (_: Throwable) {
        }
    }

    override fun onResume() {
        super.onResume()
        // while this screen is open the home-screen dragon stays hidden: only the preview boxes show a dragon
        Diag.log(this, "Dragon app opened; home screen was " + when {
            !IconRegistry.serviceActive -> "unknown (icon finder not connected)"
            IconRegistry.onHome -> "detected"
            else -> "NOT detected (" + IconRegistry.homeWhy.ifEmpty { "no reason noted" } + ")"
        })
        DragonService.appOpen = true
        DragonService.instance?.refreshHold()
        try { if (!logTab) flamePreview?.start() } catch (_: Throwable) {}
        // the icon finder was switched on in Android's settings before the user agreed here: show the disclosure now
        if (intent?.getBooleanExtra("a11y_disclosure", false) == true) {
            intent.removeExtra("a11y_disclosure")
            if (!Prefs.a11yConsent(this)) showA11yDisclosure()
        }
        try {
            if (setupRows.size == 3) refresh()
        } catch (t: Throwable) {
            showError("The status refresh failed:", Log.getStackTraceString(t))
        }
        // setup not finished yet: point at the Setup card again (short pulse, no scrolling)
        if (!setupDone && Prefs.welcomeSeen(this) && welcome == null && a11yHelp == null && !logTab && setupRows.size == 3) highlightSetup(false)
    }

    override fun onNewIntent(newIntent: Intent) {
        super.onNewIntent(newIntent)
        setIntent(newIntent)
    }

    /** Prominent disclosure first (Google Play requirement); Android's Accessibility settings open only after the user agrees. */
    private fun onIconFinderTapped() {
        if (Prefs.a11yConsent(this)) openAccessibilitySettings() else showA11yDisclosure()
    }

    private var a11yHelp: android.app.AlertDialog? = null

    /** Already on: just open Android's Accessibility screen (to switch it off). Not on yet: show the three steps first. */
    private fun openAccessibilitySettings() {
        if (a11yEnabled()) launchAccessibility() else showA11yHelp()
    }

    private fun launchAccessibility() {
        try { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) } catch (_: Throwable) {}
    }

    /**
     * Android blocks accessibility services of apps installed outside an app store ("restricted setting"). The menu item
     * "Allow restricted settings" only appears in App info after the switch was tapped once, so the order of the steps matters.
     */
    private fun showA11yHelp() {
        a11yHelp?.dismiss()
        Diag.log(this, "Icon finder help shown")
        val where = if (isSamsung()) "Accessibility > Installed apps" else "Accessibility"
        val msg = "1. Tap Open Accessibility. In $where tap \"Home Dragon icon finder\", even if it is greyed out. " +
            "Android may say it is a restricted setting.\n\n" +
            "2. Come back here and tap Open App info. Tap the \u22EE menu (top right) > Allow restricted settings. " +
            "The menu item only appears after step 1.\n\n" +
            "3. Tap Open Accessibility again, tap \"Home Dragon icon finder\" and switch it on.\n\n" +
            "If the switch is not greyed out, just do step 3."
        val d = android.app.AlertDialog.Builder(this)
            .setTitle("Turn on the icon finder")
            .setMessage(msg)
            .setPositiveButton("Open Accessibility", null)
            .setNeutralButton("Open App info", null)
            .setNegativeButton("Close", null)
            .create()
        d.setOnDismissListener { if (a11yHelp === d) a11yHelp = null }
        d.show()
        // set after show() so the buttons do not close the dialog: the steps stay on screen when you come back
        d.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener { launchAccessibility() }
        d.getButton(android.content.DialogInterface.BUTTON_NEUTRAL).setOnClickListener {
            try { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) } catch (_: Throwable) {}
        }
        d.getButton(android.content.DialogInterface.BUTTON_NEGATIVE).setOnClickListener { d.dismiss() }
        a11yHelp = d
    }

    private var disclosureShowing = false

    private fun showA11yDisclosure() {
        if (disclosureShowing) return
        disclosureShowing = true
        val msg = "Home Dragon uses Android's Accessibility service for one thing: to find where the icons on your home screen are, " +
            "and to know when the home screen is in front, so the dragon can sit on the icons, breathe fire at them and hide when you open another app.\n\n" +
            "What it looks at:\n" +
            "\u2022 the position and size of home-screen icons\n" +
            "\u2022 whether an icon has a label (not the label itself)\n" +
            "\u2022 the name of the app that is in front, and technical screen names of your launcher (to tell home from recent apps)\n\n" +
            "What it does not do:\n" +
            "\u2022 it does not read or store text, messages, passwords or anything you type\n" +
            "\u2022 it does not tap, type or control anything\n" +
            "\u2022 nothing leaves your phone: the app has no internet access and shares no data\n\n" +
            "You can switch it off any time in Android Settings > Accessibility."
        android.app.AlertDialog.Builder(this)
            .setTitle("Allow the icon finder?")
            .setMessage(msg)
            .setCancelable(true)
            .setPositiveButton("Agree and continue") { _, _ ->
                Prefs.setA11yConsent(this, true)
                openAccessibilitySettings()
            }
            .setNegativeButton("No thanks", null)
            .setOnDismissListener { disclosureShowing = false }
            .show()
    }

    /** Shown once, when all three setup rows are On. An app cannot restart a phone itself, so "Restart now" opens the power menu. */
    private fun showRestartAdvice() {
        Prefs.setRestartAsked(this, true)
        Diag.log(this, "Restart advice shown")
        android.app.AlertDialog.Builder(this)
            .setTitle("Restart your phone?")
            .setMessage("Setup is finished. Restarting once lets the phone pick up the new battery and accessibility settings, so the dragon keeps running reliably.\n\n" +
                "\"Restart now\" opens the power menu: tap Restart there.")
            .setCancelable(true)
            .setPositiveButton("Restart now") { _, _ ->
                Diag.log(this, "Restart advice: restart now")
                val opened = try { IconRegistry.powerDialog?.invoke() == true } catch (_: Throwable) { false }
                if (!opened) Toast.makeText(this, "Hold the power button (or side key) and choose Restart.", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("Restart later") { _, _ -> Diag.log(this, "Restart advice: later") }
            .show()
    }

    private fun a11yEnabled(): Boolean {
        val me = ComponentName(this, IconFinderService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        return enabled.split(':').any { it.equals(me, ignoreCase = true) }
    }

    private fun refresh() {
        val overlay = Settings.canDrawOverlays(this)
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        setupRows[0].update(overlay)
        setupRows[1].update(a11yEnabled())
        if (a11yEnabled()) a11yHelp?.dismiss()      // switched on: the steps are no longer needed
        val battery = pm.isIgnoringBatteryOptimizations(packageName)
        setupRows[2].update(battery)
        setupDone = overlay && a11yEnabled() && battery
        if (setupDone) stopHighlight()
        if (setupDone && !Prefs.restartAsked(this)) showRestartAdvice()

        val running = DragonService.instance != null
        val finder = IconRegistry.serviceActive
        val sb = StringBuilder()
        sb.append("Dragon service : ").append(if (running) "running" else "NOT running").append('\n')
        sb.append("Icon finder    : ").append(if (finder) "connected" else if (a11yEnabled()) "switched on, not connected" else "off").append('\n')
        sb.append("Auto-restarts  : ").append(Diag.restartCount(this)).append('\n')
        val ev = Diag.lines(this)
        sb.append('\n').append(if (ev.isEmpty()) "No events yet." else "Recent events (newest first):")
        for (e in ev) sb.append('\n').append(e)
        healthText.text = sb.toString()
        pill.text = if (running) "●  Running" else "○  Stopped"
        pill.setTextColor(if (running) GREEN else MUTED)
        pill.background = shape(if (running) Color.parseColor("#12332A") else CARD, 20, if (running) GREEN else STROKE)
        toggle.text = if (running) "Stop dragon" else "Start dragon"
        toggle.background = if (running) gradient(Color.parseColor("#E5533D"), Color.parseColor("#F08A3C"), 18)
        else gradient(TEAL, BLUE, 18)
    }

    private fun toggleService() {
        if (DragonService.instance != null) {
            startService(Intent(this, DragonService::class.java).setAction(DragonService.ACTION_STOP))
        } else {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Allow drawing over other apps first.", Toast.LENGTH_SHORT).show()
                return
            }
            startForegroundService(Intent(this, DragonService::class.java))
        }
        window.decorView.postDelayed({ refresh() }, 500)
    }

    private fun isSamsung() = Build.MANUFACTURER.equals("samsung", ignoreCase = true)

    private fun openBackgroundSettings() {
        // First tap: Android's own "allow always running" prompt (a small system pop-up). Once allowed, the next tap opens the phone's battery pages.
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            try {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
                return
            } catch (_: Throwable) {
                // not available on this phone: use the battery pages below
            }
        }
        if (isSamsung()) {
            // Samsung One UI: Battery > Background usage limits > Never sleeping apps. The page behind these names changes between
            // One UI versions and they are not public, so each is tried in turn and the app info page is the safe fallback.
            val tries = listOf(
                "com.samsung.android.lool" to "com.samsung.android.sm.battery.ui.BatteryActivity",
                "com.samsung.android.lool" to "com.samsung.android.sm.ui.battery.BatteryActivity"
            )
            var opened = false
            for ((pkg, cls) in tries) {
                try {
                    startActivity(Intent().setComponent(ComponentName(pkg, cls)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    opened = true
                    break
                } catch (_: Throwable) {
                }
            }
            if (!opened) {
                try { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) } catch (_: Throwable) {}
            }
            Toast.makeText(this,
                (if (opened) "" else "App info opened: tap Battery > Unrestricted. ") +
                    "Also: Settings > Battery > Background usage limits > Never sleeping apps > add Home Dragon, and turn off Put unused apps to sleep.",
                Toast.LENGTH_LONG).show()
            return
        }
        // Battery: open the system battery list (no special permission needed). The user picks Home Dragon and "No restrictions".
        try {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        } catch (e: Exception) {
            try { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) } catch (_: Exception) {}
        }
        // HyperOS / MIUI: Autostart screen (not available on every build, so failure is fine).
        try {
            startActivity(Intent().setComponent(ComponentName(
                "com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"
            )))
        } catch (_: Exception) {
        }
        Toast.makeText(this, "In the battery list switch to All apps, choose Home Dragon, then No restrictions. Also lock it in the recent apps list.", Toast.LENGTH_LONG).show()
    }
}
