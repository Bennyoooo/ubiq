package com.agentos.assist

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.agentos.control.ControlService
import com.agentos.ui.HomeActivity

/**
 * Holding the nav handle: Launcher3 asks the Contextual Search service, which launches this (Core
 * points config_defaultContextualSearchPackageName at Ubiq). No UI of its own: it steps aside,
 * then opens the assistant sheet over the app underneath, with that app's screen, as the
 * accessibility shortcut does.
 */
class ContextualSearchActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = applicationContext
        finish()
        overridePendingTransition(0, 0)
        // Let this activity go so the screen capture sees the app, not Ubiq.
        Handler(Looper.getMainLooper()).postDelayed({
            ControlService.instance?.openSheetWithScreen() ?: HomeActivity.openAssistant(app)
        }, 150)
    }
}
