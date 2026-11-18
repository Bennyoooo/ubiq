package com.agentos.control

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView

/**
 * The phone visibly changing mode when focus starts or ends: the screen's edges dim inward for a moment and a small
 * "focus on" label shows near the top, then both fade away. A pass-through overlay (it never takes a touch); Android
 * itself shows nothing when Do Not Disturb turns on.
 */
object FocusVeil {
    private const val TAG = "AgentOS/FocusVeil"
    private val main = Handler(Looper.getMainLooper())

    fun play(on: Boolean) {
        main.post {
            val svc = ControlService.instance ?: return@post
            try { show(svc, on) } catch (t: Throwable) { Log.w(TAG, "veil", t) }
        }
    }

    private fun show(svc: ControlService, on: Boolean) {
        val wm = svc.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val d = svc.resources.displayMetrics.density
        val root = FrameLayout(svc)
        root.addView(Vignette(svc, if (on) 0x8C else 0x4C), FrameLayout.LayoutParams(-1, -1))
        val label = TextView(svc).apply {
            text = if (on) "☾  focus on" else "focus off"
            textSize = 13f
            setTextColor(Color.rgb(0xF4, 0xF4, 0xEF))
            letterSpacing = 0.04f
            try { androidx.core.content.res.ResourcesCompat.getFont(svc, com.agentos.R.font.nunito_w700) } catch (_: Throwable) { null }?.let { typeface = it }
            background = GradientDrawable().apply { cornerRadius = 999f; setColor(0xE616161A.toInt()) }
            setPadding((14 * d).toInt(), (6 * d).toInt(), (14 * d).toInt(), (6 * d).toInt())
        }
        root.addView(label, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = (64 * d).toInt() })
        root.alpha = 0f
        val lp = WindowManager.LayoutParams(-1, -1, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT).apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        wm.addView(root, lp)
        label.translationY = -8 * d
        label.animate().translationY(0f).setDuration(450).start()
        root.animate().alpha(1f).setDuration(450).withEndAction {
            root.animate().alpha(0f).setStartDelay(900).setDuration(650).withEndAction {
                try { wm.removeView(root) } catch (_: Throwable) {}
            }.start()
        }.start()
    }

    /** Clear in the middle, dark toward the edges. */
    private class Vignette(ctx: Context, private val edgeAlpha: Int) : View(ctx) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            val r = Math.hypot(w.toDouble(), h.toDouble()).toFloat() / 2f
            paint.shader = RadialGradient(w / 2f, h / 2f, r, intArrayOf(Color.TRANSPARENT, Color.argb(edgeAlpha / 3, 0, 0, 0), Color.argb(edgeAlpha, 0, 0, 0)),
                floatArrayOf(0.45f, 0.75f, 1f), Shader.TileMode.CLAMP)
        }
        override fun onDraw(canvas: Canvas) { canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint) }
    }
}
