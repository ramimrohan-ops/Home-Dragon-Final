package com.ramim.homedragon

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import kotlin.math.abs
import kotlin.math.min

/** How long the screen must stay still (no swipe, scroll or window change) before icon positions are read. Every scan waits this long. */
private const val SETTLE_MS = 300L

/** After a scan the screen is read once more this much later; while the boxes keep moving (an animation is still running) it is read again. */
private const val VERIFY_MS = 300L

/** At most this many extra reads after the first one (so up to 4 scans in a row). */
private const val VERIFY_MAX = 3

/**
 * Finds home screen icon positions and the home screen page. It only reads node bounds (and whether a node is
 * clickable and labelled) and the two numbers of the page bar. It never reads or stores text.
 *
 * The home screen counts as "on top" only when the launcher is the top application window AND its page bar with the two page
 * numbers can be read. Without the page number the dragon is hidden and Phone Status hears "don't run".
 */
class IconFinderService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var lastScrollX = -1
    private val settleScan = Runnable { onSettle() }

    /** One home screen page as the last full scan saw it: the raw boxes (to compare) and the icon parts (what the dragon uses). */
    private class Layout(val raw: List<RectF>, val icons: List<RectF>)
    private val layouts = HashMap<Int, Layout>()     // by page number from the page bar; in memory only, newest scan of each page
    private var layoutsKey = ""                      // screen size and density the saved layouts belong to

    override fun onServiceConnected() {
        super.onServiceConnected()
        // Not allowed to work until the user has agreed to the in-app disclosure: switch off and show it.
        if (!Prefs.a11yConsent(this)) {
            try {
                startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra("a11y_disclosure", true))
            } catch (_: Exception) {
            }
            disableSelf()
            return
        }
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        IconRegistry.launcherPkg = packageManager.resolveActivity(home, 0)?.activityInfo?.packageName
        IconRegistry.serviceActive = true
        IconRegistry.onHome = false                      // proven below (launcher on top and its page number readable), never assumed
        IconRegistry.page = 0; IconRegistry.pages = 0; IconRegistry.noItems = false
        IconRegistry.recheck = { handler.post { recheckHome() } }
        Diag.log(this, "Icon finder connected")
        light = false                                    // Android starts a connected service in full mode
        IconRegistry.light = false
        IconRegistry.modeChanged = { handler.post { updateMode() } }
        KeepAlive.ensureDragon(this, "icon finder connected")
        IconRegistry.listener?.invoke()
        if (!signalReceiverOn) {
            try {
                ContextCompat.registerReceiver(
                    this, signalReceiver,
                    IntentFilter().apply {
                        addAction(Intent.ACTION_SCREEN_OFF)
                        addAction(Intent.ACTION_SCREEN_ON)
                        addAction(Intent.ACTION_USER_PRESENT)
                        addAction(HomeSignal.ACTION_ACK)      // Phone Status: "received"
                        addAction(HomeSignal.ACTION_ASK)      // Phone Status: "what is the state now?"
                    },
                    ContextCompat.RECEIVER_EXPORTED
                )
                signalReceiverOn = true
            } catch (_: Throwable) {
            }
        }
        homeFromWindows()?.let { applyCheck(it) }         // what is in front right now
        updateMode()
        publishRun()
    }

    // ---- Phone Status link: "Run" (home screen on top, page 2, phone unlocked, screen on, no keyboard, no shade) or "don't run" ----
    // The state is sent once, when it changes. Phone Status answers "received"; without that answer it is sent again
    // after 1 s, 2 s and 4 s, then given up (Phone Status asks for the state itself when it starts).

    private val powerMgr by lazy { getSystemService(Context.POWER_SERVICE) as PowerManager }
    private val keyguardMgr by lazy { getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager }
    private var signalReceiverOn = false
    private var sentRun: Boolean? = null
    private var seq = 0L
    private var pendingSeq = 0L                 // 0 = nothing is waiting for a confirmation
    private var pendingRun = false
    private var pendingWhy = ""
    private var tries = 0
    private val waits = longArrayOf(1000, 2000, 4000, 4000)
    private val publishRun1 = Runnable { publishRun() }
    private val publishRun2 = Runnable { publishRun() }
    private val resendRun = Runnable { resendPending() }

    private val signalReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                HomeSignal.ACTION_ACK -> onConfirmed(i.getLongExtra("seq", 0L), i.getBooleanExtra("run", false))
                HomeSignal.ACTION_ASK -> {
                    Diag.log(c, "Phone Status asked for the state")
                    publishRun(true)
                }
                else -> {                                   // screen on / off / unlocked
                    publishRun()
                    queuePublish()                          // look again once the change has settled
                }
            }
        }
    }

    /** Re-check shortly after a window change (0.15 s) and again when it has settled (0.7 s). */
    private fun queuePublish() {
        handler.removeCallbacks(publishRun1); handler.removeCallbacks(publishRun2)
        handler.postDelayed(publishRun1, 150)
        handler.postDelayed(publishRun2, 700)
    }

    /**
     * Strict answer for Phone Status. True only when the screen is on, the phone is unlocked, the home screen is on top,
     * no keyboard is showing and the focused window is the launcher (a pulled-down notification shade takes the focus).
     */
    private fun strictHome(): Pair<Boolean, String> {
        if (!powerMgr.isInteractive) return false to "screen off"
        if (keyguardMgr.isKeyguardLocked) return false to "phone locked"
        if (!IconRegistry.onHome) return false to whyNow()
        val launcher = IconRegistry.launcherPkg ?: return false to "launcher unknown"
        try {
            var focused: AccessibilityWindowInfo? = null
            val r = Rect()
            for (w in windows) {
                if (w.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                    w.getBoundsInScreen(r)
                    if (r.height() > 0) return false to "keyboard showing"
                }
                if (focused == null && w.isFocused) focused = w
            }
            if (focused != null) {
                val p = focused.root?.packageName?.toString()
                if (focused.type != AccessibilityWindowInfo.TYPE_APPLICATION || p != launcher) {
                    return false to "other window focused (" + (p ?: "system window") + ")"
                }
            }
        } catch (_: Throwable) {
        }
        return true to "home on top"
    }

    /**
     * Sends "Run" (strict home AND the page bar names [HomeSignal.RUN_PAGE]) or "don't run" (anything else) when it differs
     * from the last one sent, or always when [force] is true (Phone Status asked for the state, or confirmed a different one),
     * then waits for the confirmation. Swiping between other pages sends nothing: it stays "don't run".
     */
    private fun publishRun(force: Boolean = false) {
        if (!IconRegistry.serviceActive) return
        val (home, why) = strictHome()
        val page = IconRegistry.page
        val pages = IconRegistry.pages
        val run = home && page == HomeSignal.RUN_PAGE
        val reason = when {
            !home -> why
            run -> "home, page $page of $pages"
            else -> "home, page $page of $pages (Phone Status runs on page ${HomeSignal.RUN_PAGE})"
        }
        if (!force && run == sentRun) return
        sentRun = run
        seq++
        pendingSeq = seq; pendingRun = run; pendingWhy = reason; tries = 0
        HomeSignal.send(this, run, reason, seq)
        Diag.log(this, "Sent to Phone Status: " + (if (run) "Run" else "Don't run") + " (" + reason + ")")
        handler.removeCallbacks(resendRun)
        handler.postDelayed(resendRun, waits[0])
    }

    private fun resendPending() {
        if (pendingSeq == 0L) return
        if (tries >= waits.size - 1) {
            Diag.log(this, "Phone Status did not confirm after " + (tries + 1) + " tries: stopped (it asks when it starts)")
            pendingSeq = 0L
            return
        }
        tries++
        HomeSignal.send(this, pendingRun, pendingWhy, pendingSeq)
        Diag.log(this, "No confirmation yet, sent again (try " + (tries + 1) + ")")
        handler.postDelayed(resendRun, waits[tries])
    }

    private fun onConfirmed(confirmedSeq: Long, confirmedRun: Boolean) {
        if (pendingSeq == 0L || confirmedSeq != pendingSeq) return       // an old answer
        handler.removeCallbacks(resendRun)
        pendingSeq = 0L
        if (confirmedRun == pendingRun) {
            Diag.log(this, "Phone Status confirmed: " + (if (confirmedRun) "Run" else "Don't run"))
        } else {
            Diag.log(this, "Phone Status confirmed a different state: sending again")
            publishRun(true)
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        handler.removeCallbacks(resendRun); handler.removeCallbacks(publishRun1); handler.removeCallbacks(publishRun2)
        handler.removeCallbacks(settleScan)
        layouts.clear()
        seq++
        HomeSignal.send(this, false, "icon finder off", seq)
        pendingSeq = 0L
        sentRun = null
        if (signalReceiverOn) {
            try { unregisterReceiver(signalReceiver) } catch (_: Throwable) {}
            signalReceiverOn = false
        }
        Diag.log(this, "Icon finder disconnected")
        IconRegistry.modeChanged = null
        IconRegistry.light = false
        handler.removeCallbacks(rc1); handler.removeCallbacks(rc2)
        IconRegistry.recheck = null
        IconRegistry.serviceActive = false
        IconRegistry.icons = emptyList()
        IconRegistry.page = 0; IconRegistry.pages = 0; IconRegistry.noItems = false
        IconRegistry.listener?.invoke()
        return super.onUnbind(intent)
    }

    override fun onInterrupt() {}

    private var notHomeWhy = "not checked yet"   // why the last window check said "not home" (health log, and the reason sent to Phone Status)

    /** The top application window: keyboards, the status bar, our own overlay and picture-in-picture windows do not count. */
    private class Front(val root: AccessibilityNodeInfo?, val pkg: String?)

    /** Null when the window list is unavailable (nothing is guessed then). */
    private fun front(): Front? {
        try {
            for (w in windows) {                         // ordered top to bottom
                if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
                if (w.isInPictureInPictureMode) continue
                val root = w.root
                val p = root?.packageName?.toString()
                if (p == packageName) continue
                return Front(root, p)
            }
        } catch (_: Exception) {
        }
        return null
    }

    private class Check(val home: Boolean, val page: Int, val pages: Int)

    /**
     * Is the home screen what the user is looking at? Yes when the top application window is the launcher AND the page bar with its
     * two numbers can be read in that same window; the check then also gives the page. No for any other app, for a window that cannot
     * be read, and for the launcher without a readable page number (recents, an app vault, a screen still being drawn).
     * Returns null when the window list is unavailable: nothing is guessed then, the last answer stays.
     */
    private fun homeFromWindows(): Check? {
        val launcher = IconRegistry.launcherPkg ?: return null
        val f = front() ?: return null
        launcherFront = f.pkg == launcher
        if (f.pkg == null) { notHomeWhy = "window in front cannot be read"; return Check(false, 0, 0) }
        if (f.pkg != launcher) { notHomeWhy = "another app in front"; return Check(false, 0, 0) }
        val bar = findBar(f.root, 0)
        if (bar == null) { notHomeWhy = "page number not readable (recents, app vault or screen still drawing)"; return Check(false, 0, 0) }
        return Check(true, bar[0], bar[1])
    }

    /** The page bar: the SeekBar whose label holds two numbers ("Page 2 of 5 pages"). Found by its type and the two numbers, not by words. */
    private fun findBar(n: AccessibilityNodeInfo?, depth: Int): IntArray? {
        if (n == null || depth > 14) return null
        pageBar(n)?.let { return it }
        for (i in 0 until n.childCount) findBar(n.getChild(i), depth + 1)?.let { return it }
        return null
    }

    /** [page, pages] when this node is the page bar, else null. Any script's digits count. */
    private fun pageBar(n: AccessibilityNodeInfo): IntArray? {
        if (n.className?.toString() != "android.widget.SeekBar") return null
        val label = n.contentDescription?.toString() ?: return null
        val nums = ArrayList<Int>(2)
        var cur = -1
        for (ch in label) {
            val dg = Character.digit(ch, 10)
            if (dg >= 0) cur = (if (cur < 0) 0 else cur) * 10 + dg
            else if (cur >= 0) { nums.add(cur); cur = -1 }
        }
        if (cur >= 0) nums.add(cur)
        if (nums.size != 2 || nums[0] < 1 || nums[0] > nums[1]) return null
        return intArrayOf(nums[0], nums[1])
    }

    /** Short reason for the health log: why the dragon is not being shown. */
    private fun whyNow(): String = if (IconRegistry.onHome) "home screen in front (page number read)" else notHomeWhy

    /**
     * Look again at what is in front. Called after every window change (0.4 s and 0.8 s later, when the transition is over), by the
     * hidden-dragon poll, and when a scan cannot read the page number: the launcher can become readable (for example after unlock) or
     * stop being the home screen (recents) without any further event.
     * With usage access on, an app in front comes straight from Android, even when its window cannot be read.
     */
    private fun recheckHome() {
        if (!IconRegistry.serviceActive) return
        val launcher = IconRegistry.launcherPkg ?: return
        val top = Foreground.current(this)
        if (top != null && top != launcher) {
            // Android's list can lag a moment behind: if the focused window really is the launcher, believe the window
            val active = try { rootInActiveWindow?.packageName?.toString() } catch (e: Exception) { null }
            if (active != launcher) {
                notHomeWhy = "another app in front (usage access)"
                launcherFront = false
                leaveHome()
                updateMode()
                return
            }
        }
        homeFromWindows()?.let { applyCheck(it) }
        updateMode()
    }

    private val rc1 = Runnable { recheckHome() }
    private val rc2 = Runnable { recheckHome() }

    /** After every window change: look again 0.4 and 0.8 s later, when the transition is over (also while home, to catch recents). */
    private fun scheduleRechecks() {
        handler.removeCallbacks(rc1); handler.removeCallbacks(rc2)
        handler.postDelayed(rc1, 400); handler.postDelayed(rc2, 800)
    }

    private var light = false               // light mode: only window changes are delivered (see updateMode)
    private var launcherFront = true        // the launcher window is in front (home, app drawer or recents): its screen still needs to be read

    /**
     * Light mode while the dragon has nothing to follow: the home screen is not in front and the launcher is not in front
     * either (another app is open), or the screen is off or locked. Android then only reports window changes, not every
     * content change and scroll of the app in front, and does not build the full node tree. Full mode returns as soon as
     * the launcher is in front again or the screen is awake and home is found.
     */
    private fun updateMode() {
        val want = IconRegistry.idle || (!IconRegistry.onHome && !launcherFront)
        if (want == light) return
        try {
            val info = getServiceInfo() ?: return
            if (want) {
                info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or AccessibilityEvent.TYPE_WINDOWS_CHANGED
                info.flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            } else {
                // No content-changed events: a widget redrawing every second must not wake the icon finder.
                info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_VIEW_SCROLLED or AccessibilityEvent.TYPE_WINDOWS_CHANGED
                info.flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            }
            setServiceInfo(info)
            light = want
            IconRegistry.light = want
        } catch (_: Throwable) {
        }
    }

    /** The window check's answer: home (with its page) comes on at once, no settle time; "not home" acts at once too. */
    private fun applyCheck(c: Check) {
        if (!c.home) { leaveHome(); return }
        IconRegistry.page = c.page; IconRegistry.pages = c.pages
        if (!IconRegistry.onHome) enterHome() else publishRun()      // already home: the page may have changed
    }

    /** Home is on top with a readable page number: the dragon is shown (it fades in after the first scan), Phone Status is told. */
    private fun enterHome() {
        IconRegistry.onHome = true
        IconRegistry.homeWhy = whyNow()
        IconRegistry.listener?.invoke()                 // the dragon shows now (it fades in once the first scan has placed it)
        updateMode()
        Diag.log(this, "Home found: page ${IconRegistry.page} of ${IconRegistry.pages}")
        scanSoon()
        publishRun()
    }

    /** Home is not on top: hide the dragon, stop scanning, and tell Phone Status right away. */
    private fun leaveHome() {
        val was = IconRegistry.onHome
        IconRegistry.onHome = false
        IconRegistry.page = 0; IconRegistry.pages = 0; IconRegistry.noItems = false
        IconRegistry.homeWhy = whyNow()
        handler.removeCallbacks(settleScan)             // scanning stops at once
        if (was) {
            Diag.log(this, "Home left: " + notHomeWhy)
            IconRegistry.listener?.invoke()             // the dragon hides now
            updateMode()
        }
        publishRun()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (!IconRegistry.serviceActive) return          // not connected (no consent yet)
        KeepAlive.ensureDragon(this, "screen change")    // dragon service gone while it should be on: start it again (cheap check, throttled)
        val launcher = IconRegistry.launcherPkg ?: return
        val type = event.eventType
        if (type == AccessibilityEvent.TYPE_WINDOWS_CHANGED || type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) queuePublish()

        // Window list changes carry no package name, so they are handled first.
        if (type == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            homeFromWindows()?.let { applyCheck(it) }
            updateMode()
            DragonService.instance?.resetHiddenBackoff()
            scheduleRechecks()                           // every window change, also while home: recents has no event of its own once the bar is gone
            return
        }
        val pkg = event.packageName?.toString() ?: return

        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            // Ignore system overlays that do not change what the user is looking at.
            if (pkg == "com.android.systemui" || pkg == packageName) return
            launcherFront = pkg == launcher
            homeFromWindows()?.let { applyCheck(it) }       // no window list: nothing is guessed, the last answer stays
            updateMode()
            DragonService.instance?.resetHiddenBackoff()
            scheduleRechecks()
        }
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED && pkg == launcher) {
            val dx = event.scrollDeltaX; val dy = event.scrollDeltaY
            val horizontal = if (dx != 0 || dy != 0) abs(dx) >= 2 && abs(dx) > abs(dy)
            else event.maxScrollX > 0 && event.scrollX != lastScrollX
            lastScrollX = event.scrollX
            if (horizontal) IconRegistry.swipeListener?.invoke()
            scanSoon()                                       // any swipe or scroll (pages, app drawer): scan after 0.3 s without further scrolling
            return
        }
        // The launcher window itself changed (home came to front, drawer or folder opened or closed): scan.
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && pkg == launcher) scanSoon()
    }

    // ---- scanning: wait for quiet, scan, then look once more until two reads in a row agree ----
    private var verifyPrev: List<RectF>? = null     // the boxes of the previous scan in this run
    private var scansDone = 0                       // scans in this run

    /** Any trigger: the next scan waits [SETTLE_MS] of quiet (every new trigger restarts the wait). */
    private fun scanSoon() {
        if (!IconRegistry.onHome) return                     // not home: the icon finder does not scan
        verifyPrev = null; scansDone = 0
        handler.removeCallbacks(settleScan)
        handler.postDelayed(settleScan, SETTLE_MS)
    }

    /**
     * Scan, then compare with the previous scan of this run. A folder or page animation that was still running when the first scan
     * happened shows up as moved boxes: the screen is then read again, up to [VERIFY_MAX] more times, until two reads agree.
     */
    private fun onSettle() {
        if (!IconRegistry.onHome) return
        val raw = scan() ?: return
        scansDone++
        val prev = verifyPrev
        if (prev != null && sameBoxes(prev, raw)) {
            IconRegistry.noteScan("page ${IconRegistry.page} of ${IconRegistry.pages}: stable after $scansDone scans")
            verifyPrev = null
            return
        }
        verifyPrev = raw
        if (scansDone <= VERIFY_MAX) {
            if (scansDone > 1) IconRegistry.noteScan("page ${IconRegistry.page} of ${IconRegistry.pages}: boxes still moving, scanning again ($scansDone of ${VERIFY_MAX + 1})")
            handler.postDelayed(settleScan, VERIFY_MS)
        } else {
            IconRegistry.noteScan("page ${IconRegistry.page} of ${IconRegistry.pages}: still changing after $scansDone scans, stopped")
        }
    }

    /** Two walks see the same page when the same boxes are in the same places (a few pixels of slack). */
    private fun sameBoxes(a: List<RectF>, b: List<RectF>): Boolean {
        if (a.size != b.size) return false
        for (r in a) {
            if (b.none { abs(it.left - r.left) <= 3f && abs(it.top - r.top) <= 3f && abs(it.width() - r.width()) <= 3f && abs(it.height() - r.height()) <= 3f }) return false
        }
        return true
    }

    /**
     * Reads the launcher's current page from the top application window (the same window the home check reads). The page comes from
     * the page bar; without a readable page number the dragon is hidden and nothing is saved or sent. A page that was scanned before is
     * only compared with its saved copy (icon count and positions): if they match nothing is rebuilt and the dragon is left alone (light
     * scan). If they differ, or the page has no saved copy, the new layout is saved over the old one and handed to the dragon (full
     * scan). A page with nothing found hides the dragon. One item is enough. Saved layouts live in memory only and are dropped when
     * the screen size changes or the icon finder restarts. Returns the raw boxes, or null when nothing could be read.
     */
    private fun scan(): List<RectF>? {
        val root = front()?.root ?: (try { rootInActiveWindow } catch (e: Exception) { null }) ?: return null
        if (root.packageName?.toString() != IconRegistry.launcherPkg) return null

        val t0 = SystemClock.elapsedRealtime()
        val d = resources.displayMetrics
        val key = "${d.widthPixels}x${d.heightPixels}@${d.densityDpi}"
        if (key != layoutsKey) { layouts.clear(); layoutsKey = key }

        val minSide = 44 * d.density
        val maxSide = 140 * d.density
        val raw = ArrayList<RectF>()
        val found = ArrayList<RectF>()
        val seen = HashSet<Long>()
        val b = Rect()
        var bar: IntArray? = null

        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 14) return
            if (bar == null) bar = pageBar(n)
            val cls = n.className?.toString() ?: ""
            val isWidget = cls.contains("WidgetHostView")
            val labelled = !n.text.isNullOrEmpty() || !n.contentDescription.isNullOrEmpty()
            // One UI Home and others expose icons as BubbleTextView / IconView nodes that are not always flagged clickable.
            val iconClass = cls.contains("BubbleTextView") || cls.contains("IconView") || cls.contains("AppIcon")
            val isIconNode = (n.isClickable || n.isLongClickable || iconClass) && labelled
            if (n.isVisibleToUser && (isWidget || isIconNode)) {
                n.getBoundsInScreen(b)
                val w = b.width().toFloat()
                val h = b.height().toFloat()
                val onScreen = b.left >= 0 && b.right <= d.widthPixels && b.top >= 0 && b.bottom <= d.heightPixels
                if (isIconNode && w in minSide..maxSide && h in minSide..(maxSide * 1.3f) && h / w in 0.6f..1.6f && onScreen) {
                    val k = (b.left.toLong() shl 32) xor b.top.toLong()
                    if (seen.add(k)) {
                        raw.add(RectF(b))
                        // The node covers icon + label. Keep the icon part.
                        val iconSide = min(w * 0.78f, h * 0.72f)
                        val cx = b.exactCenterX()
                        val top = b.top + h * 0.05f
                        found.add(RectF(cx - iconSide / 2, top, cx + iconSide / 2, top + iconSide))
                    }
                } else if ((isWidget || isIconNode) && w > maxSide * 0.9f && w <= d.widthPixels * 0.99f &&
                    h >= minSide && h <= d.heightPixels * 0.45f && onScreen
                ) {
                    // Widget or large folder: bigger ground. The whole rectangle is kept.
                    val k = (b.left.toLong() shl 32) xor b.top.toLong()
                    if (seen.add(k)) {
                        raw.add(RectF(b))
                        found.add(RectF(b))
                        return   // do not treat things inside a widget as separate icons
                    }
                }
            }
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        try { walk(root, 0) } catch (e: Exception) { return null }

        val ms = SystemClock.elapsedRealtime() - t0
        val barNow = bar
        if (barNow == null) {
            // No page number: the home screen is not proven, so nothing is shown. Looked at again shortly (it may only be a screen being drawn).
            notHomeWhy = "page number not readable (scan)"
            IconRegistry.noteScan("page number not readable: dragon hidden, Phone Status told \"don't run\", $ms ms")
            leaveHome()
            updateMode()
            scheduleRechecks()
            return null
        }
        val page = barNow[0]
        val pages = barNow[1]
        IconRegistry.page = page; IconRegistry.pages = pages
        val saved = layouts[page]
        val head = "page $page of $pages: "
        val tail = ", $ms ms"
        if (found.isEmpty()) {
            // nothing found: the dragon is hidden (an empty page, or a screen still drawing: the next scan decides)
            IconRegistry.noItems = true
            IconRegistry.listener?.invoke()
            IconRegistry.noteScan(head + "no items found, dragon hidden" + tail)
        } else if (saved != null && sameBoxes(saved.raw, raw)) {
            // light scan: same count, same positions. Nothing is rebuilt; the dragon gets the saved copy if it is on another page now.
            val wasEmpty = IconRegistry.noItems
            IconRegistry.noItems = false
            val changed = IconRegistry.icons !== saved.icons
            if (changed) IconRegistry.icons = saved.icons
            if (changed || wasEmpty) IconRegistry.listener?.invoke()
            IconRegistry.noteScan(head + "saved layout reused (light scan), ${raw.size} items" + tail)
        } else {
            // full scan: no saved copy, or the page changed (icons moved, added or removed, new grid)
            IconRegistry.noItems = false
            layouts[page] = Layout(raw, found)
            IconRegistry.icons = found
            IconRegistry.listener?.invoke()
            IconRegistry.noteScan(head + (if (saved == null) "new page, layout saved" else "icons moved, layout saved again") + " (full scan), ${found.size} items" + tail)
        }
        IconRegistry.scanSaved = if (layouts.isEmpty()) "none" else layouts.keys.sorted().joinToString(",")
        IconRegistry.homeWhy = whyNow()
        IconRegistry.scanDone?.invoke()                  // the dragon may fade in now (it waited for this scan)
        publishRun()                                     // swiped to another page: Phone Status hears Run or don't run
        return raw
    }
}
