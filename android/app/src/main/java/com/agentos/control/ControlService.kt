package com.agentos.control

import kotlinx.coroutines.launch

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.LinearLayout
import android.widget.TextView
import com.agentos.agent.AgentRuntime
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executor
import kotlin.coroutines.resume

/**
 * The agent's hands. An AccessibilityService can read every app's UI tree, click, type, scroll,
 * take screenshots and press system keys - which is exactly what an on-device agent needs.
 */
class ControlService : AccessibilityService() {

    @Volatile private var lastEventAt = 0L
    private var overlay: View? = null
    private val mainExecutor: Executor = Executor { r -> mainHandler.post(r) }
    private val mainHandler by lazy { android.os.Handler(mainLooper) }

    override fun onServiceConnected() {
        Island.refresh()
        super.onServiceConnected()
        instance = this
        com.agentos.voice.WakeWord.sync(this)
        Log.i(TAG, "Ubiq control service connected")
        // No accessibility button: a service that requests one gets only a "shortcut" switch in
        // Settings (Android 12+) and a floating button over every app. The assistant opens with a
        // power-button hold, or the nav-handle hold on the ROM.
    }

    /**
     * Android withholds the screen from an assistant that opens itself, so read it here first
     * (text and a screenshot of the current app), then open the sheet with it.
     */
    fun openSheetWithScreen() {
        if (com.agentos.assist.AgentVoiceService.instance == null) { com.agentos.ui.HomeActivity.openAssistant(this); return }
        com.agentos.agent.AgentRuntime.scope.launch {
            val ctx = try {
                val snap = snapshot()
                val png = screenshotPng(maxDim = 1200)
                com.agentos.assist.AgentVoiceService.ScreenContext(snap.packageName,
                    snap.nodes.map { it.text.ifEmpty { it.desc } }.filter { it.isNotBlank() }.distinct().joinToString("\n"), png)
            } catch (t: Throwable) { Log.w(TAG, "screen capture for the sheet", t); null }
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                if (!com.agentos.assist.AgentVoiceService.open(ctx)) com.agentos.ui.HomeActivity.openAssistant(this@ControlService)
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        lastEventAt = SystemClock.uptimeMillis()
        event?.let { com.agentos.memory.Capture.onEvent(this, it) }
        event?.let { Autocomplete.onEvent(this, it) }
        if (event?.packageName == "com.android.systemui") Island.statusBarChanged()
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        hideStatus()
        Island.remove(this)
        IslandCard.remove(this)
        hideAnswer()
        Autocomplete.remove(this)
        instance = null
        super.onDestroy()
    }

    // ---------- Reading ----------

    fun snapshot(displayId: Int = Display.DEFAULT_DISPLAY, maxNodes: Int = 160): ScreenSnapshot = ScreenReader.capture(this, displayId, maxNodes)

    /** Windows on [displayId]: the service's own list for the main screen, the per-display map otherwise. */
    fun windowsOn(displayId: Int): List<AccessibilityWindowInfo> = try {
        if (displayId == Display.DEFAULT_DISPLAY) windows
        else windowsOnAllDisplays.get(displayId) ?: emptyList()
    } catch (_: Throwable) { emptyList() }

    /** Waits until the UI has been quiet for [quietMs], or [timeoutMs] elapses. */
    suspend fun waitForIdle(quietMs: Long = 350, timeoutMs: Long = 2500) {
        val start = SystemClock.uptimeMillis()
        delay(quietMs)
        while (SystemClock.uptimeMillis() - start < timeoutMs) {
            if (SystemClock.uptimeMillis() - lastEventAt >= quietMs) return
            delay(100)
        }
    }

    suspend fun screenshotPng(maxDim: Int = 1000, displayId: Int = Display.DEFAULT_DISPLAY): ByteArray? = suspendCancellableCoroutine { cont ->
        try {
            takeScreenshot(displayId, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    try {
                        val hb = screenshot.hardwareBuffer
                        val bmp = Bitmap.wrapHardwareBuffer(hb, screenshot.colorSpace)
                        hb.close()
                        if (bmp == null) { cont.resume(null); return }
                        val sw = bmp.copy(Bitmap.Config.ARGB_8888, false)
                        val scale = maxDim.toFloat() / maxOf(sw.width, sw.height)
                        val scaled = if (scale < 1f)
                            Bitmap.createScaledBitmap(sw, (sw.width * scale).toInt(), (sw.height * scale).toInt(), true)
                        else sw
                        val bos = ByteArrayOutputStream()
                        scaled.compress(Bitmap.CompressFormat.PNG, 90, bos)
                        cont.resume(bos.toByteArray())
                    } catch (t: Throwable) {
                        Log.w(TAG, "screenshot convert failed", t); cont.resume(null)
                    }
                }
                override fun onFailure(errorCode: Int) {
                    Log.w(TAG, "screenshot failed: $errorCode"); cont.resume(null)
                }
            })
        } catch (t: Throwable) {
            Log.w(TAG, "takeScreenshot threw", t); cont.resume(null)
        }
    }

    // ---------- Acting ----------

    fun clickNode(n: UiNode): Boolean {
        var cur: AccessibilityNodeInfo? = n.node
        var hops = 0
        while (cur != null && hops < 4) {
            if (cur.isClickable && cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            cur = cur.parent; hops++
        }
        return false
    }

    fun longPressNode(n: UiNode): Boolean {
        var cur: AccessibilityNodeInfo? = n.node
        var hops = 0
        while (cur != null && hops < 4) {
            if (cur.isLongClickable && cur.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)) return true
            cur = cur.parent; hops++
        }
        return false
    }

    fun setText(n: UiNode, text: String): Boolean {
        n.node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return n.node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    /** The input-focused node on [displayId]. findFocus() only knows the main screen, so other displays are searched window by window. */
    fun focusedNode(displayId: Int = Display.DEFAULT_DISPLAY): AccessibilityNodeInfo? {
        if (displayId == Display.DEFAULT_DISPLAY) return findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        for (w in windowsOn(displayId).sortedByDescending { it.layer }) {
            val root = w.root ?: continue
            root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let { return it }
        }
        return null
    }

    fun focusedEditable(displayId: Int = Display.DEFAULT_DISPLAY): AccessibilityNodeInfo? =
        focusedNode(displayId)?.takeIf { it.isEditable }

    fun setTextOnFocused(text: String, displayId: Int = Display.DEFAULT_DISPLAY): Boolean {
        val f = focusedEditable(displayId) ?: return false
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return f.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun pressImeEnter(displayId: Int = Display.DEFAULT_DISPLAY): Boolean {
        val f = focusedNode(displayId) ?: return false
        return f.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
    }

    fun scrollNode(n: UiNode, forward: Boolean): Boolean {
        var cur: AccessibilityNodeInfo? = n.node
        var hops = 0
        while (cur != null && hops < 6) {
            if (cur.isScrollable) {
                val a = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                if (cur.performAction(a)) return true
            }
            cur = cur.parent; hops++
        }
        return false
    }

    fun global(action: Int): Boolean = performGlobalAction(action)

    suspend fun tap(x: Int, y: Int, displayId: Int = Display.DEFAULT_DISPLAY): Boolean =
        gesture(Path().apply { moveTo(x.toFloat(), y.toFloat()) }, 60, displayId)

    suspend fun longPress(x: Int, y: Int, displayId: Int = Display.DEFAULT_DISPLAY): Boolean =
        gesture(Path().apply { moveTo(x.toFloat(), y.toFloat()) }, 700, displayId)

    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long = 300, displayId: Int = Display.DEFAULT_DISPLAY): Boolean =
        gesture(Path().apply { moveTo(x1.toFloat(), y1.toFloat()); lineTo(x2.toFloat(), y2.toFloat()) }, durationMs, displayId)

    private suspend fun gesture(path: Path, durationMs: Long, displayId: Int): Boolean = suspendCancellableCoroutine { cont ->
        val g = GestureDescription.Builder()
            .setDisplayId(displayId)
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        val ok = dispatchGesture(g, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) { if (cont.isActive) cont.resume(true) }
            override fun onCancelled(gestureDescription: GestureDescription?) { if (cont.isActive) cont.resume(false) }
        }, null)
        if (!ok && cont.isActive) cont.resume(false)
    }

    fun launchPackage(pkg: String): Boolean {
        val intent = packageManager.getLaunchIntentForPackage(pkg) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        // Name the real screen so a task parked on the workspace display is brought here, not raised there.
        val opts = android.app.ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY).toBundle()
        return try { startActivity(intent, opts); true } catch (t: Throwable) { Log.w(TAG, "launch failed", t); false }
    }

    // ---------- Answer card (a demo's answer, over the app the take ends on) ----------

    private var answerCard: android.view.View? = null

    /** Shows [text] at the bottom of the real screen until tapped or for [ANSWER_MS]. */
    fun showAnswer(text: String) {
        mainHandler.post {
            hideAnswer()
            val density = resources.displayMetrics.density
            val pad = (18 * density).toInt()
            val card = TextView(this).apply {
                this.text = text
                setTextColor(Color.rgb(0xFB, 0xEF, 0xE6)); textSize = 15f * com.agentos.ui.TEXT_SCALE
                try { typeface = androidx.core.content.res.ResourcesCompat.getFont(this@ControlService, com.agentos.R.font.nunito_w700) } catch (_: Throwable) {}
                setPadding(pad, pad, pad, pad)
                background = GradientDrawable().apply { cornerRadius = 24 * density; setColor(Color.rgb(0x1C, 0x14, 0x24)) }
                setOnClickListener { hideAnswer() }
            }
            val lp = WindowManager.LayoutParams(
                (resources.displayMetrics.widthPixels * 0.92f).toInt(),
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT,
            ).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; y = (24 * density).toInt() }
            try {
                (getSystemService(WINDOW_SERVICE) as WindowManager).addView(card, lp)
                answerCard = card
                mainHandler.postDelayed(hideAnswerRunnable, ANSWER_MS)
            } catch (t: Throwable) { Log.w(TAG, "answer card failed", t) }
        }
    }

    private val hideAnswerRunnable = Runnable { hideAnswer() }

    fun dismissAnswer() { mainHandler.post { hideAnswer() } }

    private fun hideAnswer() {
        mainHandler.removeCallbacks(hideAnswerRunnable)
        answerCard?.let { try { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it) } catch (_: Throwable) {} }
        answerCard = null
    }

    // ---------- Stop chip (only while the agent drives the visible screen) ----------

    /**
     * A small Stop chip inside the status bar strip, so it never sits on top of the app. Tasks on the
     * hidden workspace show nothing over other apps: their notification has the status, Stop and Peek.
     */
    fun showStatus() { mainHandler.post { if (overlay == null) createOverlay() } }

    fun hideStatus() {
        mainHandler.post {
            overlay?.let {
                try { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it) } catch (_: Throwable) {}
            }
            overlay = null
        }
    }

    private fun createOverlay() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val density = resources.displayMetrics.density
        val statusBar = wm.currentWindowMetrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars()).top
        val height = (26 * density).toInt()
        val pad = (8 * density).toInt()
        val chip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // Ubiq look (docs/DESIGN.md): the dark deck pill, a lit red LED, the word Stop.
            background = GradientDrawable().apply {
                cornerRadius = 40 * density
                setColor(Color.rgb(0x17, 0x17, 0x1B))
            }
            setPadding(pad, 0, pad + pad / 2, 0)
            contentDescription = "Stop Ubiq"
            setOnClickListener { AgentRuntime.stopOverlayLead() }
        }
        val led = android.view.View(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.rgb(0xD0, 0x10, 0x12)) }
            val size = (7 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(size, size).apply { marginEnd = pad / 2 + pad / 4 }
        }
        val label = TextView(this).apply {
            text = "Stop"; textSize = 12f * com.agentos.ui.TEXT_SCALE
            setTextColor(Color.rgb(0xF4, 0xF4, 0xEF))
            try { androidx.core.content.res.ResourcesCompat.getFont(this@ControlService, com.agentos.R.font.nunito_w700) } catch (_: Throwable) { null }?.let { typeface = it }
        }
        chip.addView(led)
        chip.addView(label)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            height,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = pad
            y = maxOf(0, (statusBar - height) / 2)
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        try { wm.addView(chip, lp); overlay = chip } catch (t: Throwable) {
            Log.w(TAG, "overlay failed", t)
        }
    }

    companion object {
        private const val TAG = "AgentOS/Control"
        private const val ANSWER_MS = 45_000L
        @Volatile var instance: ControlService? = null
            private set
        val isRunning: Boolean get() = instance != null
    }
}
