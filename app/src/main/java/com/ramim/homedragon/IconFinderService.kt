package com.ramim.homedragon

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import kotlin.math.abs
import kotlin.math.min

/**
 * Finds home screen icon positions. It only reads node bounds (and whether a node is
 * clickable and labelled). It never reads or stores text.
 */
class IconFinderService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var scanQueued = false
    private var lastScan = 0L
    private var lastScrollX = -1
    private val settleScan = Runnable { scanQueued = false; lastScan = System.currentTimeMillis(); scan() }

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
        IconRegistry.powerDialog = { if (Build.VERSION.SDK_INT >= 31) performGlobalAction(GLOBAL_ACTION_POWER_DIALOG) else false }
        KeepAlive.ensureDragon(this, "icon finder connected")
        IconRegistry.listener?.invoke()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Diag.log(this, "Icon finder disconnected")
        IconRegistry.powerDialog = null
        handler.removeCallbacks(rc1); handler.removeCallbacks(rc2); handler.removeCallbacks(rc3); handler.removeCallbacks(graceRun)
        IconRegistry.recheck = null
        IconRegistry.serviceActive = false
        IconRegistry.icons = emptyList()
        IconRegistry.listener?.invoke()
        return super.onUnbind(intent)
    }

    override fun onInterrupt() {}

    private var recentsEvt = false      // launcher reported a recents-like screen class
    private var recentsNode = false     // recents views seen in the launcher's node tree, or no icons at all
    private var recentsEvtAt = 0L
    private var missCount = 0           // consecutive settled scans that found no icons
    private var lastScrollMs = 0L
    private val recents: Boolean get() = recentsEvt || recentsNode

    private fun refreshHome() {
        homeFromWindows()?.let { setHome(it) }
    }

    /**
     * Is the home screen what the user is looking at? Looks at the top-most application window
     * (keyboards, status bar, our own overlay and picture-in-picture windows do not count).
     * Returns null when the window list is unavailable.
     */
    private fun homeFromWindows(): Boolean? {
        keeping = false
        val launcher = IconRegistry.launcherPkg ?: return null
        val p = topAppPackage() ?: return null
        if (p.isEmpty()) return decideUnreadable(launcher)   // unreadable window on top: do not just hide the dragon, see below
        unreadableSince = 0L
        lastUnreadLog = ""
        return p == launcher && !recents
    }

    private var keeping = false             // the last homeFromWindows() answer was "no change": keep what the dragon is doing
    private var deciding = false            // guards against the scan below calling back into the decision
    private var unreadableSince = 0L        // when the current unreadable window was first seen (0 = none)
    private var lastIconsMs = 0L            // last time a scan found the home screen icons
    private var lastWinPkg: String? = null  // package of the last window change event (it is known even when the window cannot be read)
    private var lastUnreadLog = ""
    private val graceRun = Runnable { refreshHome() }

    private fun unreadableLog(msg: String) {
        if (msg != lastUnreadLog) { lastUnreadLog = msg; Diag.log(this, msg) }
    }

    /**
     * The top window cannot be read. This used to count as "not home" and the dragon faded out and paused. Now, in this order:
     * 1. a fresh icon scan finds the home screen icons -> home, keep going;
     * 2. the last window change event names a package: the launcher -> keep going, another app -> hide;
     * 3. nothing says which app it is: keep what the dragon is doing, with no time limit, until something known arrives.
     * Returns null for "no change".
     */
    private fun decideUnreadable(launcher: String): Boolean? {
        if (deciding) { keeping = true; return null }
        deciding = true
        try {
            val now = System.currentTimeMillis()
            lastScan = now
            scan(true)
            if (lastIconsMs >= now) {
                unreadableSince = 0L
                unreadableLog("Unreadable window in front: the icon scan found the home screen, kept going")
                return true
            }
            val wp = lastWinPkg
            if (wp != null) {
                unreadableSince = 0L
                return if (wp == launcher) {
                    unreadableLog("Unreadable window from the launcher in front: kept going")
                    !recents
                } else {
                    unreadableLog("Unreadable window from $wp in front: hidden")
                    false
                }
            }
            // nothing says which app it is: no change, the dragon keeps doing what it was doing (no time limit)
            keeping = true
            unreadableLog("Unreadable window in front, no package name: kept going")
            return null
        } finally {
            deciding = false
        }
    }

    /** Package of the top-most application window (not keyboards, status bar, our overlay or picture-in-picture). null = unknown, "" = unreadable. */
    private fun topAppPackage(): String? {
        try {
            for (w in windows) {                         // ordered top to bottom
                if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
                if (w.isInPictureInPictureMode) continue
                val p = w.root?.packageName?.toString()
                if (p == packageName) continue
                return p ?: ""
            }
        } catch (_: Exception) {
        }
        return null
    }

    /** Short reason for the health log: why the dragon is not being shown. */
    private fun whyNow(): String = when {
        IconRegistry.onHome -> "home screen in front"
        recentsEvt -> "recents screen event"
        recentsNode -> "recents views or no icons seen"
        else -> "another app in front, or an unreadable window that could not be placed"
    }

    private var rechecking = false                       // true while a re-check (not a normal event) is running: only used for the log

    /**
     * Look again at what is in front. Called a few times after the phone is unlocked: while the lock screen is up the
     * launcher window is unreadable, so "home" was set to false, and the window change that follows the unlock can
     * arrive before the launcher is readable again. Without this the dragon stayed hidden until the app was restarted.
     */
    private fun recheckHome() {
        if (!IconRegistry.serviceActive) return
        val launcher = IconRegistry.launcherPkg ?: return
        var top = topAppPackage()
        if (top == null) top = try { rootInActiveWindow?.packageName?.toString() } catch (e: Exception) { null }
        if (top == "") {                                 // unreadable window: decided by the scan, the window event or the 5 s wait
            homeFromWindows()?.let { setHome(it) }
            return
        }
        if (top != launcher) {                           // another app is in front: nothing to look at
            if (top != null) setHome(false)
            return
        }
        // The launcher is in front. Do not trust old "recents" notes: read the live screen now.
        rechecking = true
        try {
            lastScan = System.currentTimeMillis()
            scan(true)
            homeFromWindows()?.let { setHome(it) }
        } finally {
            rechecking = false
        }
    }

    private val rc1 = Runnable { recheckHome() }
    private val rc2 = Runnable { recheckHome() }
    private val rc3 = Runnable { recheckHome() }

    /** After a window change while the dragon is hidden: look again 0.3, 0.9 and 2 s later, when the transition is over. */
    private fun scheduleRechecks() {
        handler.removeCallbacks(rc1); handler.removeCallbacks(rc2); handler.removeCallbacks(rc3)
        handler.postDelayed(rc1, 300); handler.postDelayed(rc2, 900); handler.postDelayed(rc3, 2000)
    }

    private fun setHome(h: Boolean) {
        if (h != IconRegistry.onHome) {
            if (h && rechecking) Diag.log(this, "Home found by re-check (was hidden: " + whyNow() + ")")
            IconRegistry.onHome = h
            IconRegistry.homeWhy = whyNow()
            IconRegistry.listener?.invoke()
            if (h) queueScan()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (!IconRegistry.serviceActive) return          // not connected (no consent yet)
        KeepAlive.ensureDragon(this, "screen change")    // dragon service gone while it should be on: start it again (cheap check, throttled)
        val launcher = IconRegistry.launcherPkg ?: return
        val type = event.eventType

        // Window list changes carry no package name, so they are handled first.
        if (type == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            homeFromWindows()?.let { setHome(it) }
            DragonService.instance?.resetHiddenBackoff()
            if (!IconRegistry.onHome) scheduleRechecks()
            return
        }
        val pkg = event.packageName?.toString() ?: return

        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            // Ignore system overlays that do not change what the user is looking at.
            if (pkg == "com.android.systemui" || pkg == packageName) return
            if (pkg == launcher) {
                recentsEvt = event.className?.let { it.contains("recent", true) || it.contains("overview", true) } == true
                recentsEvtAt = System.currentTimeMillis()
            }
            lastWinPkg = pkg
            val hw = homeFromWindows()
            if (hw != null) setHome(hw) else if (!keeping) setHome(pkg == launcher && !recents)
            DragonService.instance?.resetHiddenBackoff()
            if (!IconRegistry.onHome) scheduleRechecks()
        }
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED && pkg == launcher) {
            val dx = event.scrollDeltaX; val dy = event.scrollDeltaY
            val horizontal = if (dx != 0 || dy != 0) abs(dx) >= 2 && abs(dx) > abs(dy)
            else event.maxScrollX > 0 && event.scrollX != lastScrollX
            lastScrollX = event.scrollX
            lastScrollMs = System.currentTimeMillis()
            if (horizontal) {
                IconRegistry.swipeListener?.invoke()
                // rescan shortly after the last scroll event, i.e. once the page has settled
                handler.removeCallbacks(settleScan)
                handler.postDelayed(settleScan, 150)
                return
            }
        }
        if (pkg == launcher) queueScan()
    }

    private fun queueScan() {
        if (scanQueued) return
        scanQueued = true
        val wait = (300 - (System.currentTimeMillis() - lastScan)).coerceAtLeast(60)
        handler.postDelayed({
            scanQueued = false
            lastScan = System.currentTimeMillis()
            scan()
        }, wait)
    }

    private fun scan(force: Boolean = false) {
        val root = try { rootInActiveWindow } catch (e: Exception) { null } ?: return
        if (root.packageName?.toString() != IconRegistry.launcherPkg) return

        val whyBefore = whyNow()
        val d = resources.displayMetrics
        val minSide = 44 * d.density
        val maxSide = 140 * d.density
        val found = ArrayList<RectF>()
        val seen = HashSet<Long>()
        val b = Rect()
        var sawRecents = false

        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 14) return
            val cls = n.className?.toString() ?: ""
            if (!sawRecents && n.isVisibleToUser) {
                val id = n.viewIdResourceName ?: ""
                if (id.contains("recents", true) || id.contains("overview", true) || id.contains("task_view", true) ||
                    id.contains("clear_all", true) || cls.contains("RecentsView") || cls.contains("TaskView")
                ) sawRecents = true
            }
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
                    val key = (b.left.toLong() shl 32) xor b.top.toLong()
                    if (seen.add(key)) {
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
                    val key = (b.left.toLong() shl 32) xor b.top.toLong()
                    if (seen.add(key)) {
                        found.add(RectF(b))
                        return   // do not treat things inside a widget as separate icons
                    }
                }
            }
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        try { walk(root, 0) } catch (e: Exception) { return }

        // No icons for two settled scans in a row also means the home screen is covered (recents etc.).
        val settled = System.currentTimeMillis() - lastScrollMs > 600
        if (found.size >= 2) missCount = 0 else if (settled) missCount++
        // a recents flag from an event is dropped once icons are plainly visible again for a while
        val evtStale = recentsEvt && found.size >= 2 && !sawRecents && System.currentTimeMillis() - recentsEvtAt > (if (force) 800 else 4000)
        if (evtStale) recentsEvt = false
        val nowRecents = sawRecents || missCount >= 2
        val changed = nowRecents != recentsNode || evtStale
        recentsNode = nowRecents
        if (found.size >= 2 && !sawRecents) {
            lastIconsMs = System.currentTimeMillis()
            IconRegistry.icons = found
            if (!IconRegistry.onHome) {
                IconRegistry.onHome = true                         // the front window is the launcher and shows its icons: it is the home screen
                if (rechecking) Diag.log(this, "Home found by re-check (was hidden: $whyBefore)")
            }
            IconRegistry.listener?.invoke()
        }
        if (changed) refreshHome()
        IconRegistry.homeWhy = whyNow()
    }
}
