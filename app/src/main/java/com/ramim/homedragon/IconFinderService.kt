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
private const val SETTLE_MS = 500L

/**
 * Home screen settle gate. "Home is not on top" acts at once (dragon hides, scanning stops, Phone Status is told). "Home is on top"
 * waits this long first, so the launcher can finish its transition; if home is left again within that time nothing happens at all.
 * The first scan runs [SETTLE_MS] into this time, so the icon layout is ready when the dragon appears.
 */
private const val HOME_GATE_MS = 1000L

/**
 * Finds home screen icon positions. It only reads node bounds (and whether a node is
 * clickable and labelled). It never reads or stores text.
 */
class IconFinderService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var lastScrollX = -1
    private val settleScan = Runnable { if (IconRegistry.onHome) scan() }

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
        homeFromWindows()?.let {                         // what is in front right now (the default is "home")
            IconRegistry.onHome = it
            IconRegistry.homeWhy = whyNow()
            if (it) scan()
        }
        publishHome()
    }

    // ---- Phone Status link: "home screen on top, phone unlocked, screen on, no keyboard, no shade" ----
    // The state is sent once, when it changes. Phone Status answers "received"; without that answer it is sent again
    // after 1 s, 2 s and 4 s, then given up (Phone Status asks for the state itself when it starts).

    private val powerMgr by lazy { getSystemService(Context.POWER_SERVICE) as PowerManager }
    private val keyguardMgr by lazy { getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager }
    private var signalReceiverOn = false
    private var sentHome: Boolean? = null
    private var sentPage = 0
    private var seq = 0L
    private var pendingSeq = 0L                 // 0 = nothing is waiting for a confirmation
    private var pendingHome = false
    private var pendingWhy = ""
    private var pendingPage = 0
    private var pendingPages = 0
    private var tries = 0
    private val waits = longArrayOf(1000, 2000, 4000, 4000)
    private val publishRun1 = Runnable { publishHome() }
    private val publishRun2 = Runnable { publishHome() }
    private val resendRun = Runnable { resendPending() }

    private val signalReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                HomeSignal.ACTION_ACK -> onConfirmed(i.getLongExtra("seq", 0L), i.getBooleanExtra("home", false))
                HomeSignal.ACTION_ASK -> {
                    Diag.log(c, "Phone Status asked for the state")
                    publishHome(true)
                }
                else -> {                                   // screen on / off / unlocked
                    publishHome()
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
     * Sends the state when it differs from the last one sent (home or not, or the page), or always when [force] is true, then waits for the confirmation.
     * "Not home" goes out at once. "Home" is held back until it has been true for 1 s ([settled] = this is that moment), unless
     * [force] is true (Phone Status asked for the state, or confirmed a different one).
     */
    private fun publishHome(force: Boolean = false, settled: Boolean = false) {
        if (!IconRegistry.serviceActive) return
        val (home, why) = strictHome()
        val page = if (home) IconRegistry.page else 0          // the page the last settled scan read from the page bar
        val pages = if (home) IconRegistry.pages else 0
        if (!home) {
            handler.removeCallbacks(publishGateRun); publishGateOn = false     // left again within the settle time: nothing is sent
        } else if (!force && !settled && sentHome != true) {
            if (!publishGateOn) { publishGateOn = true; handler.postDelayed(publishGateRun, HOME_GATE_MS) }
            return
        }
        if (!force && home == sentHome && page == sentPage) return
        sentHome = home; sentPage = page
        seq++
        pendingSeq = seq; pendingHome = home; pendingWhy = why; pendingPage = page; pendingPages = pages; tries = 0
        HomeSignal.send(this, home, why, seq, page, pages)
        Diag.log(this, "Sent to Phone Status: " + (if (home) "home, page $page of $pages" else "not home") + " (" + why + ")")
        handler.removeCallbacks(resendRun)
        handler.postDelayed(resendRun, waits[0])
    }

    private var publishGateOn = false                 // "home" is waiting for its 1 s settle time before it is sent
    private val publishGateRun = Runnable { publishGateOn = false; publishHome(settled = true) }

    private fun resendPending() {
        if (pendingSeq == 0L) return
        if (tries >= waits.size - 1) {
            Diag.log(this, "Phone Status did not confirm after " + (tries + 1) + " tries: stopped (it asks when it starts)")
            pendingSeq = 0L
            return
        }
        tries++
        HomeSignal.send(this, pendingHome, pendingWhy, pendingSeq, pendingPage, pendingPages)
        Diag.log(this, "No confirmation yet, sent again (try " + (tries + 1) + ")")
        handler.postDelayed(resendRun, waits[tries])
    }

    private fun onConfirmed(confirmedSeq: Long, confirmedHome: Boolean) {
        if (pendingSeq == 0L || confirmedSeq != pendingSeq) return       // an old answer
        handler.removeCallbacks(resendRun)
        pendingSeq = 0L
        if (confirmedHome == pendingHome) {
            Diag.log(this, "Phone Status confirmed: " + (if (confirmedHome) "home" else "not home"))
        } else {
            Diag.log(this, "Phone Status confirmed a different state: sending again")
            publishHome(true)
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        handler.removeCallbacks(resendRun); handler.removeCallbacks(publishRun1); handler.removeCallbacks(publishRun2)
        handler.removeCallbacks(gateRun); handler.removeCallbacks(gateScanRun); handler.removeCallbacks(publishGateRun); handler.removeCallbacks(settleScan)
        homePending = false; publishGateOn = false
        layouts.clear()
        seq++
        HomeSignal.send(this, false, "icon finder off", seq, 0, 0)
        pendingSeq = 0L
        sentHome = null; sentPage = 0
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
        IconRegistry.page = 0; IconRegistry.pages = 0; IconRegistry.scanNote = ""
        IconRegistry.listener?.invoke()
        return super.onUnbind(intent)
    }

    override fun onInterrupt() {}

    private var notHomeWhy = "not checked yet"   // why the last window check said "not home" (health log, and the reason sent to Phone Status)
    private var unreadableTop = false              // the window in front cannot be read: window events then look again shortly after

    /**
     * Is the home screen what the user is looking at? Yes when the top-most application window (keyboards, status bar, our own
     * overlay and picture-in-picture windows do not count) is the launcher AND the launcher shows its page bar. No for any other app,
     * for a window that cannot be read, and for the launcher without the bar (recents, an open folder, the app vault).
     * Returns null when the window list is unavailable: nothing is guessed then, the last answer stays.
     */
    private fun homeFromWindows(): Boolean? {
        val launcher = IconRegistry.launcherPkg ?: return null
        try {
            for (w in windows) {                         // ordered top to bottom
                if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
                if (w.isInPictureInPictureMode) continue
                val root = w.root
                val p = root?.packageName?.toString()
                if (p == packageName) continue
                launcherFront = p == launcher
                unreadableTop = p == null
                if (p == null) { notHomeWhy = "window in front cannot be read"; return false }
                if (p != launcher) { notHomeWhy = "another app in front"; return false }
                if (findBar(root, 0) == null) { notHomeWhy = "launcher without its page bar (recents, folder or app vault)"; return false }
                return true
            }
        } catch (_: Exception) {
        }
        return null
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
    private fun whyNow(): String = if (IconRegistry.onHome) "home screen in front (page bar found)" else notHomeWhy

    /**
     * Look again at what is in front. Called by the hidden-dragon poll and shortly after a window change that left the dragon
     * hidden or met an unreadable window: the launcher can become readable (for example after unlock) without any new event.
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
                setHome(false)
                updateMode()
                return
            }
        }
        homeFromWindows()?.let { setHome(it) }
        updateMode()
    }

    private val rc1 = Runnable { recheckHome() }
    private val rc2 = Runnable { recheckHome() }

    /** After a window change while the dragon is hidden: look again 0.4 and 0.8 s later, when the transition is over. */
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

    /** Judged home / not home by the window checks. "Not home" acts now; "home" goes through the 1 s settle gate. */
    private fun setHome(h: Boolean) {
        if (h) { if (!IconRegistry.onHome) startGate() } else leaveHome()
    }

    private var homePending = false         // home was found and the 1 s settle time is running
    private val gateRun = Runnable { homePending = false; applyHome(true) }
    private val gateScanRun = Runnable { if (homePending) scan() }     // the first scan, while the dragon is still hidden

    private fun startGate() {
        if (homePending) return
        homePending = true
        handler.postDelayed(gateScanRun, SETTLE_MS)
        handler.postDelayed(gateRun, HOME_GATE_MS)
    }

    /** Home is not on top: cancel a running settle time, then hide, stop scanning and tell Phone Status right away. */
    private fun leaveHome() {
        if (homePending) { homePending = false; handler.removeCallbacks(gateRun); handler.removeCallbacks(gateScanRun) }
        applyHome(false)
    }

    /** Takes the new state into use: dragon (through the listener), mode, scanning and the message to Phone Status. */
    private fun applyHome(h: Boolean) {
        if (h == IconRegistry.onHome) return
        IconRegistry.onHome = h
        IconRegistry.homeWhy = whyNow()
        IconRegistry.listener?.invoke()                 // the dragon shows or hides now
        updateMode()
        if (h) {
            handler.removeCallbacks(rc1); handler.removeCallbacks(rc2)   // home found: no re-check is needed any more
            Diag.log(this, "Home settled after 1 s: dragon shown, telling Phone Status")
            scan()                                      // normally a light scan: the page was already read 0.5 s into the settle time
            publishHome(settled = true)                 // and Phone Status hears "home" and the page at that moment
        } else {
            handler.removeCallbacks(settleScan)          // scanning stops at once
            publishHome()                               // "not home" goes out at once
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (!IconRegistry.serviceActive) return          // not connected (no consent yet)
        KeepAlive.ensureDragon(this, "screen change")    // dragon service gone while it should be on: start it again (cheap check, throttled)
        val launcher = IconRegistry.launcherPkg ?: return
        val type = event.eventType
        if (type == AccessibilityEvent.TYPE_WINDOWS_CHANGED || type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) queuePublish()

        // Window list changes carry no package name, so they are handled first.
        if (type == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            homeFromWindows()?.let { setHome(it) }
            updateMode()
            DragonService.instance?.resetHiddenBackoff()
            if (!IconRegistry.onHome || unreadableTop) scheduleRechecks()
            return
        }
        val pkg = event.packageName?.toString() ?: return

        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            // Ignore system overlays that do not change what the user is looking at.
            if (pkg == "com.android.systemui" || pkg == packageName) return
            launcherFront = pkg == launcher
            homeFromWindows()?.let { setHome(it) }          // no window list: nothing is guessed, the last answer stays
            updateMode()
            DragonService.instance?.resetHiddenBackoff()
            if (!IconRegistry.onHome || unreadableTop) scheduleRechecks()
        }
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED && pkg == launcher) {
            val dx = event.scrollDeltaX; val dy = event.scrollDeltaY
            val horizontal = if (dx != 0 || dy != 0) abs(dx) >= 2 && abs(dx) > abs(dy)
            else event.maxScrollX > 0 && event.scrollX != lastScrollX
            lastScrollX = event.scrollX
            if (horizontal) IconRegistry.swipeListener?.invoke()
            // Any swipe or scroll (pages, app drawer): scan once, after 0.5 s without further scrolling.
            handler.removeCallbacks(settleScan)
            handler.postDelayed(settleScan, SETTLE_MS)
            return
        }
        // The launcher window itself changed (home came to front, drawer or folder opened): scan once.
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && pkg == launcher) queueScan()
    }

    private fun queueScan() {
        if (!IconRegistry.onHome) return                     // not home: the icon finder does not scan
        handler.removeCallbacks(settleScan)
        handler.postDelayed(settleScan, SETTLE_MS)           // like every scan: after 0.5 s without a change
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
     * Reads the launcher's current page. The page comes from the page bar. A page that was scanned before is only compared with its
     * saved copy (icon count and positions): if they match nothing is rebuilt and the dragon is left alone (light scan). If they
     * differ, or the page has no saved copy, the new layout is saved over the old one and handed to the dragon (full scan).
     * Saved layouts live in memory only and are dropped when the screen size changes or the icon finder restarts.
     */
    private fun scan() {
        val root = try { rootInActiveWindow } catch (e: Exception) { null } ?: return
        if (root.packageName?.toString() != IconRegistry.launcherPkg) return

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
        try { walk(root, 0) } catch (e: Exception) { return }

        val page = bar?.get(0) ?: 0
        val pages = bar?.get(1) ?: 0
        IconRegistry.page = page; IconRegistry.pages = pages
        val saved = if (page > 0) layouts[page] else null
        val note: String
        if (saved != null && sameBoxes(saved.raw, raw)) {
            // light scan: same count, same positions. Nothing is rebuilt; the dragon gets the saved copy if it is on another page now.
            if (IconRegistry.icons !== saved.icons) { IconRegistry.icons = saved.icons; IconRegistry.listener?.invoke() }
            note = "page $page of $pages: same as the saved copy (light scan), ${raw.size} items"
        } else if (found.size >= 2) {
            // full scan: no saved copy, or the page changed (icons moved, added or removed, new grid)
            if (page > 0) layouts[page] = Layout(raw, found)
            IconRegistry.icons = found
            IconRegistry.listener?.invoke()
            note = "page $page of $pages: " + (if (saved == null) "first scan of this page" else "page changed, saved copy replaced") + " (full scan), ${found.size} items"
        } else {
            note = "page $page of $pages: fewer than 2 items found, nothing changed"
        }
        IconRegistry.scanNote = note + ", " + (SystemClock.elapsedRealtime() - t0) + " ms, saved pages: " +
            (if (layouts.isEmpty()) "none" else layouts.keys.sorted().joinToString(","))
        IconRegistry.homeWhy = whyNow()
        if (IconRegistry.onHome && sentHome == true) publishHome()      // swiped to another page: Phone Status hears the new page
    }
}
