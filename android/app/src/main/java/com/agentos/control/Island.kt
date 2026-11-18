package com.agentos.control

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import com.agentos.agent.AgentRuntime
import com.agentos.agent.AgentStatus

/**
 * Ubiq's island (docs/UX_SPEC.md): a thin ring of light around the front camera. Yellow when a task needs
 * you, green when one finished and you haven't seen it, breathing white while Ubiq works. On home the camera
 * is tappable even with no light; elsewhere it appears only with a light, over
 * any app and over fullscreen video. Tap it to open the conversation the light is about.
 *
 * During a live call (voice/LiveCall) it widens into a call pill: a breathing green light left of the camera and
 * voice bars right of it, over every app and the lock screen, and a tap goes back to the call.
 *
 * Drawn by the accessibility service, so it needs Ubiq Agent Control to be on.
 */
object Island {
    private const val TAG = "AgentOS/Island"
    enum class Light { NONE, WORKING, NEEDS_YOU, DONE, CALL }

    private val main = Handler(Looper.getMainLooper())
    private var view: PillView? = null
    private val unseen = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    @Volatile private var onHome = false

    /** A conversation finished while you were elsewhere: green until you open it. */
    fun markUnseen(id: String) { unseen.add(id); refresh() }

    /** You are looking at this conversation now. */
    fun seen(id: String) { if (unseen.remove(id)) refresh() }

    /** Home is showing (the island stays there even without a light). */
    fun setHome(showing: Boolean) { if (onHome != showing) { onHome = showing; refresh() } }

    private fun state(): Pair<Light, String?> {
        if (com.agentos.voice.LiveCall.active) return Light.CALL to null
        val active = AgentRuntime.active()
        active.firstOrNull { it.status.value is AgentStatus.WaitingForUser }?.let { return Light.NEEDS_YOU to (it.parentId ?: it.id) }
        unseen.firstOrNull { AgentRuntime.session(it) != null }?.let { return Light.DONE to it }
        active.firstOrNull()?.let { return Light.WORKING to (it.parentId ?: it.id) }
        return Light.NONE to null
    }

    fun refresh() {
        main.post {
            val svc = ControlService.instance ?: return@post
            val (light, target) = state()
            if (light == Light.NONE && !onHome) { remove(svc); return@post }
            // The call pill is a different size: swap the window when a call starts or ends.
            view?.takeIf { it.call != (light == Light.CALL) }?.let { remove(svc) }
            val v = view ?: create(svc, light == Light.CALL) ?: return@post
            v.light = light
            v.target = target
        }
    }

    internal fun remove(ctx: Context) {
        view?.let { v ->
            v.stop()
            try { (ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v) } catch (_: Throwable) {}
        }
        view = null
    }

    private class Box(val x: Int, val y: Int, val w: Int, val h: Int, val hole: Float)

    /**
     * Where the pill goes: the prototype's island (64 x 28 on its 286-wide canvas, so about 92 x 40 dp
     * on a 411 dp phone), centred on the camera hole. The hole comes from the cutout's path: its
     * bounding rect runs down the whole status bar and is far too big.
     */
    private fun box(svc: Context, call: Boolean = false): Box {
        val wm = svc.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val dm = svc.resources.displayMetrics
        val u = minOf(dm.widthPixels, dm.heightPixels) / 286f   // one prototype unit, in pixels
        val insets = wm.currentWindowMetrics.windowInsets
        // The display's own cutout carries the hole's path; the window insets' copy often does not.
        val cutout = (try { svc.display?.cutout } catch (_: Throwable) { null }) ?: insets.displayCutout
        val path = (cutout?.cutoutPath ?: insets.displayCutout?.cutoutPath)
            ?.let { p -> android.graphics.RectF().also { p.computeBounds(it, true) } }?.takeIf { !it.isEmpty }
        // Without a path, assume a round hole as wide as the narrowest plausible camera (34 units) in the bounding rect's middle.
        val hole = path ?: cutout?.boundingRectTop?.takeIf { !it.isEmpty }?.let { r ->
            val d = minOf(r.width().toFloat(), 22 * u); val cy = minOf(r.height() / 2f, d)
            android.graphics.RectF(r.centerX() - d / 2, cy - d / 2, r.centerX() + d / 2, cy + d / 2)
        }
        Log.i(TAG, "cutout path=$path rect=${cutout?.boundingRectTop} -> hole=$hole")
        val bar = insets.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars()).top
        if (call) {
            val h = maxOf(28 * u, (hole?.height() ?: 0f) + 8 * u).toInt()
            // A call must be obvious from any app (user feedback 2026-11-13): wide enough for a light and voice bars beside the camera.
            val w = ((hole?.width() ?: (22 * u)) + 2 * 36 * svc.resources.displayMetrics.density).toInt()
            val cx = hole?.centerX() ?: (dm.widthPixels / 2f)
            val cy = hole?.centerY() ?: (bar / 2f)
            return Box((cx - w / 2f).toInt(), (cy - h / 2f).toInt().coerceAtLeast(0), w, h, hole?.width() ?: 0f)
        }
        // Otherwise a thin ring around the camera (user feedback 2026-11-15: the pill was thick and covered the clock).
        // Rule: the ring never leaves the cutout's bounding rect, the strip SystemUI keeps its icons out of, so it
        // cannot cover an icon on any phone. Without a bounding rect, a few units around the hole.
        val d = hole?.height() ?: (18 * u)
        val cx = hole?.centerX() ?: (dm.widthPixels / 2f)
        val cy = hole?.centerY() ?: (bar / 2f)
        val room = cutout?.boundingRectTop?.takeIf { !it.isEmpty }?.let { r -> minOf(cx - r.left, r.right - cx) } ?: (d / 2 + 4 * u)
        val side = (2 * minOf(room, d / 2 + 2.5f * u)).toInt()
        return Box((cx - side / 2f).toInt(), (cy - side / 2f).toInt().coerceAtLeast(0), side, side, d)
    }

    /** Just below the island, where its card drops to. */
    fun bottom(): Int {
        val svc = ControlService.instance ?: return 0
        val b = box(svc)
        return b.y + b.h + (6 * svc.resources.displayMetrics.density).toInt()
    }

    private fun create(svc: ControlService, call: Boolean): PillView? {
        val wm = svc.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val b = box(svc, call)
        val w = b.w; val h = b.h
        val v = PillView(svc, b.hole, call).apply {
            contentDescription = if (call) "In a call with Ubiq. Open the call" else "Ubiq"
            // Out of touch mode (a key press) a focused view gets a grey focus square: the island is never focused.
            isFocusable = false
            defaultFocusHighlightEnabled = false
            setOnClickListener { if (this.call) com.agentos.voice.LiveCallActivity.open(svc) else open(svc, target) }
        }
        val lp = WindowManager.LayoutParams(w, h, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            x = b.x
            y = b.y
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        return try { wm.addView(v, lp); view = v; v } catch (t: Throwable) { Log.w(TAG, "island failed", t); null }
    }

    private fun open(ctx: Context, id: String?) {
        ctx.startActivity(Intent(ctx, com.agentos.ui.HomeActivity::class.java)
            .putExtra(com.agentos.ui.HomeActivity.EXTRA_OPEN_CHAT, true)
            .apply { if (id != null) putExtra(com.agentos.ui.HomeActivity.EXTRA_SESSION, id) }
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            // The real screen, not whichever display has focus (the agent's hidden workspace while it works).
            android.app.ActivityOptions.makeBasic().setLaunchDisplayId(android.view.Display.DEFAULT_DISPLAY).toBundle())
    }

    /** The pill and its light. The light sits to the right of the camera. */
    private class PillView(ctx: Context, private val hole: Float, val call: Boolean) : View(ctx) {
        private val d = ctx.resources.displayMetrics.density
        private val pill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
        private val led = Paint(Paint.ANTI_ALIAS_FLAG)
        private val halo = Paint(Paint.ANTI_ALIAS_FLAG)
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private var breath = 1f
        private val anim = ValueAnimator.ofFloat(0.35f, 1f).apply {
            duration = 900; repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE
            addUpdateListener { breath = it.animatedValue as Float; invalidate() }
        }
        private val green = Color.rgb(0x16, 0xD8, 0x73)
        private val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = green; strokeCap = Paint.Cap.ROUND }
        private var voice = 0f
        /** A call: the light breathes slowly and the bars follow the voice, every frame. */
        private val callAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1600; repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                val f = it.animatedFraction
                breath = 0.55f + 0.45f * kotlin.math.abs(1f - 2f * f)
                voice += (com.agentos.voice.LiveCall.level.value - voice) * 0.35f
                invalidate()
            }
        }
        init { if (call) callAnim.start() }
        var target: String? = null
        var light: Light = Light.NONE
            set(v) {
                if (field == v) return
                field = v
                if (v == Light.WORKING) anim.start() else { anim.cancel(); breath = 1f }
                invalidate()
            }

        fun stop() { anim.cancel(); callAnim.cancel() }

        override fun onDraw(c: Canvas) {
            if (call) {
                val r = height / 2f
                c.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), r, r, pill)
                drawCall(c, r); return
            }
            // A thin ring of light around the camera, a hairline gap off its edge. No light, nothing drawn: the
            // camera alone is the island and stays tappable.
            val color = when (light) {
                Light.NONE, Light.CALL -> return
                Light.WORKING -> Color.WHITE
                Light.NEEDS_YOU -> Color.rgb(0xFF, 0xCD, 0x03)
                Light.DONE -> Color.rgb(0x16, 0xD8, 0x73)
            }
            val cx = width / 2f; val cy = height / 2f
            val outer = width / 2f
            val stroke = minOf(1.6f * d, (outer - hole / 2f) * 0.6f).coerceAtLeast(1f)
            ring.color = color
            ring.strokeWidth = stroke
            ring.alpha = (breath * 255).toInt()
            c.drawCircle(cx, cy, outer - stroke / 2f, ring)
        }

        /** Left of the camera a green light, right of it four voice bars. */
        private fun drawCall(c: Canvas, r: Float) {
            val side = (width - hole) / 2f
            val dot = 3.6f * d
            val lx = side / 2f + r * 0.25f
            halo.color = green; halo.alpha = (60 * breath).toInt()
            c.drawCircle(lx, r, dot * 1.8f, halo)
            led.shader = RadialGradient(lx - dot * 0.3f, r - dot * 0.35f, dot * 1.3f, blend(green, Color.WHITE, 0.5f), green, Shader.TileMode.CLAMP)
            led.alpha = (255 * (0.6f + 0.4f * breath)).toInt()
            c.drawCircle(lx, r, dot, led)
            val gap = 4.2f * d
            bar.strokeWidth = 2.4f * d
            val x0 = width - side / 2f - r * 0.25f - gap * 1.5f
            val t = System.currentTimeMillis() / 140.0
            for (i in 0 until 4) {
                val wobble = (0.55f + 0.45f * kotlin.math.sin(t + i * 1.7).toFloat())
                val hh = (1.5f * d + voice * height * 0.42f * wobble).coerceAtMost(height * 0.32f)
                c.drawLine(x0 + i * gap, r - hh, x0 + i * gap, r + hh, bar)
            }
        }

        private fun blend(a: Int, b: Int, t: Float) = Color.rgb(
            (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
            (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
            (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt())
    }
}
