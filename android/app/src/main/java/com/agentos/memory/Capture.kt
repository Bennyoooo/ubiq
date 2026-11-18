package com.agentos.memory

import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.agentos.control.ControlService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Screen capture for the timeline (docs/MEMORY.md): once the main screen settles, the text of the app in front, and
 * only the lines that are new for that app (a scrolled list adds its new rows; the same screen again only extends
 * the last event). Never password fields, text being typed, codes ([Lifelog.clean]), or [Lifelog.EXCLUDED] apps.
 * The agent's own desks are not the owner's life and are not captured.
 */
object Capture {
    private const val TAG = "AgentOS/Capture"
    private const val SETTLE_MS = 1200L
    private const val MIN_GAP_MS = 2000L
    /** Some screens never settle (a clock, an animation): capture anyway this long after the first change. */
    private const val MAX_WAIT_MS = 4000L
    private const val MAX_NODES = 600
    private const val LINES_PER_APP = 3000

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var lastEventAt = 0L
    @Volatile private var lastCaptureAt = 0L
    private var pending: Job? = null
    /** Lines seen lately, per app, so only new ones are stored. */
    private val seen = object : LinkedHashMap<String, LinkedHashSet<String>>(32, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, LinkedHashSet<String>>) = size > 40
    }
    private var lastPkg = ""
    private var lastId: Long? = null

    /** Called for every accessibility event; cheap: it only (re)schedules one capture after the screen settles. */
    fun onEvent(svc: ControlService, event: AccessibilityEvent) {
        // Events without a display (-1) are the main screen's too; the desks' events carry their own display.
        // Android 12 events carry no display id (API 33): there, every event counts as the main screen's.
        val display = if (android.os.Build.VERSION.SDK_INT >= 33) event.displayId else Display.DEFAULT_DISPLAY
        if (display != Display.DEFAULT_DISPLAY && display != Display.INVALID_DISPLAY) return
        lastEventAt = SystemClock.uptimeMillis()
        if (pending?.isActive == true) return
        val first = lastEventAt
        pending = scope.launch {
            while (true) {
                val now = SystemClock.uptimeMillis()
                val wait = maxOf(minOf(lastEventAt + SETTLE_MS, first + MAX_WAIT_MS), lastCaptureAt + MIN_GAP_MS) - now
                if (wait <= 0) break
                delay(wait)
            }
            lastCaptureAt = SystemClock.uptimeMillis()
            try { capture(svc) } catch (t: Throwable) { Log.w(TAG, "capture", t) }
        }
    }

    @Synchronized fun reset() { seen.clear(); lastPkg = ""; lastId = null }

    @Synchronized private fun capture(svc: ControlService) {
        if (!Lifelog.enabled(svc)) return
        if (svc.getSystemService(PowerManager::class.java)?.isInteractive != true) return
        val root = svc.rootInActiveWindow ?: return
        val pkg = root.packageName?.toString().orEmpty()
        if (pkg.isEmpty() || pkg in Lifelog.EXCLUDED) return
        val lines = LinkedHashSet<String>()
        walk(root, lines)
        if (lines.isEmpty()) return
        val known = seen.getOrPut(pkg) { LinkedHashSet() }
        val fresh = lines.filter { key(it) !in known }
        lines.forEach { val k = key(it); known.remove(k); known.add(k) }
        while (known.size > LINES_PER_APP) known.remove(known.first())
        val now = System.currentTimeMillis()
        if (fresh.isEmpty()) {
            if (pkg == lastPkg) lastId?.let { Lifelog.extend(it, now) }
            return
        }
        lastPkg = pkg
        lastId = Lifelog.add(Lifelog.Kind.SCREEN, pkg, fresh.joinToString("\n"), now)
    }

    /** A short line that differs only in its digits (a clock, a timer, a counter) is the same line. */
    private fun key(line: String) = if (line.length <= 8) line.replace(Regex("\\d"), "#") else line

    private fun walk(root: AccessibilityNodeInfo, out: MutableSet<String>) {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var n = 0
        while (stack.isNotEmpty() && n < MAX_NODES) {
            val node = stack.removeLast(); n++
            if (!node.isVisibleToUser) continue
            // Passwords and what is being typed stay out; the rest of the screen is read.
            if (!node.isPassword && !node.isEditable) {
                val t = node.text?.toString()?.trim().orEmpty().ifEmpty { node.contentDescription?.toString()?.trim().orEmpty() }
                if (t.length >= 2) Lifelog.clean(t.take(1000).replace('\n', ' '))?.let { out += it }
            }
            for (i in node.childCount - 1 downTo 0) node.getChild(i)?.let { stack.addLast(it) }
        }
    }
}
