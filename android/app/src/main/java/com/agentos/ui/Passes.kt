package com.agentos.ui

import android.app.ActivityOptions
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.view.Display
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import org.json.JSONObject

/**
 * A pass on the lock screen: a QR code, boarding pass or ticket that shows over the keyguard, at full
 * brightness, the moment the phone wakes. Ubiq puts one there when it is about to be needed (at the
 * airport, at the store counter), so there is nothing to unlock or open. The last pass is kept until it is
 * dismissed with "done".
 */
object Passes {
    private const val TAG = "AgentOS/Passes"
    private const val CHANNEL = "passes"
    private const val NOTICE = 7301

    class Pass(val title: String, val detail: String, val code: String, val format: String)

    fun show(ctx: Context, title: String, detail: String, code: String, format: String = "QR", wake: Boolean = false) {
        val app = ctx.applicationContext
        app.getSharedPreferences("passes", Context.MODE_PRIVATE).edit()
            .putString("current", JSONObject().put("title", title).put("detail", detail).put("code", code).put("format", format).toString()).apply()
        // Silent and public: it is there on the lock screen, it does not ring.
        val nm = app.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Passes on the lock screen", NotificationManager.IMPORTANCE_LOW).apply {
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC; setShowBadge(false)
        })
        val open = PendingIntent.getActivity(app, NOTICE, intent(app, false), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        nm.notify(NOTICE, Notification.Builder(app, CHANNEL).setSmallIcon(com.agentos.R.drawable.ic_launcher_monochrome)
            .setContentTitle(title).setContentText(detail).setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(open).setOngoing(true).build())
        // Phone control lets Ubiq start it from the background; without it the notification still opens it.
        try {
            val starter: Context = com.agentos.control.ControlService.instance ?: app
            starter.startActivity(intent(app, wake), ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY).toBundle())
        } catch (t: Throwable) { Log.w(TAG, "could not show the pass", t) }
    }

    fun current(ctx: Context): Pass? = try {
        ctx.getSharedPreferences("passes", Context.MODE_PRIVATE).getString("current", null)?.let { JSONObject(it) }
            ?.let { Pass(it.getString("title"), it.optString("detail"), it.getString("code"), it.optString("format", "QR")) }
    } catch (_: Throwable) { null }

    fun clear(ctx: Context) {
        ctx.getSharedPreferences("passes", Context.MODE_PRIVATE).edit().remove("current").apply()
        ctx.getSystemService(NotificationManager::class.java).cancel(NOTICE)
    }

    private fun intent(ctx: Context, wake: Boolean) = Intent(ctx, PassActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("wake", wake)

    /** The code as black on white; square for QR and Aztec, wide for PDF417. */
    fun bitmap(code: String, format: String): Bitmap? = try {
        val f = when (format.uppercase().replace("_", "").replace(" ", "")) {
            "PDF417" -> BarcodeFormat.PDF_417; "AZTEC" -> BarcodeFormat.AZTEC; else -> BarcodeFormat.QR_CODE
        }
        val (w, h) = if (f == BarcodeFormat.PDF_417) 1200 to 400 else 900 to 900
        val m = MultiFormatWriter().encode(code, f, w, h, mapOf(EncodeHintType.MARGIN to 1))
        val px = IntArray(m.width * m.height) { i -> if (m.get(i % m.width, i / m.width)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
        Bitmap.createBitmap(px, m.width, m.height, Bitmap.Config.ARGB_8888)
    } catch (t: Throwable) { Log.w(TAG, "code", t); null }
}

class PassActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        if (intent.getBooleanExtra("wake", false)) setTurnScreenOn(true)
        // Scanners read a bright screen best.
        window.attributes = window.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        render()
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); render() }

    private fun render() {
        val pass = Passes.current(this) ?: run { finish(); return }
        setContent {
            AgentOsTheme {
                val code = remember(pass.code, pass.format) { Passes.bitmap(pass.code, pass.format) }
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(horizontal = 18.dp, vertical = 36.dp)) {
                    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Text("ubiq · on your lock screen", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(10.dp))
                        Text(pass.title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(4.dp))
                        Text(pass.detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(18.dp))
                        if (code != null) Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color.White).padding(14.dp)) {
                            Image(code.asImageBitmap(), pass.title, Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth, filterQuality = FilterQuality.None)
                        }
                        Spacer(Modifier.height(18.dp))
                        TextKey("done", { Passes.clear(this@PassActivity); finish() })
                    }
                }
            }
        }
    }
}
