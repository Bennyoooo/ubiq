package com.agentos.rules

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.agentos.policy.Effect
import com.agentos.policy.PolicyEngine

/**
 * The owner's rules on a stock phone: the Stop switch and a full / ask / read / off rule per app.
 * The only place a rule can be loosened. While it is open the agent may not act on Ubiq's
 * screens at all ([OwnerScreens]). On the ROM, Ubiq Core's own rules screen replaces this one.
 */
class RulesActivity : ComponentActivity() {
    private class AppRow(val pkg: String, val label: String)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val apps = packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.packageName }.distinct()
            .filter { it !in InAppPolicy.protectedPackages }
            .map { AppRow(it, InAppPolicy.label(it)) }
            .sortedBy { it.label.lowercase() }
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) { Screen(apps) }
            }
        }
    }

    override fun onResume() { super.onResume(); OwnerScreens.shown() }
    override fun onPause() { OwnerScreens.hidden(); super.onPause() }

    @Composable
    private fun Screen(apps: List<AppRow>) {
        val stopped by InAppPolicy.stopped.collectAsState()
        val rules by InAppPolicy.rules.collectAsState()
        var filter by remember { mutableStateOf("") }
        LazyColumn(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 16.dp)) {
            item {
                Text("Agent rules", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
                Text("Ubiq checks these before the agent does anything. The agent can make a rule stricter, never looser.",
                    style = MaterialTheme.typography.bodyMedium)
                Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Stop the agent", style = MaterialTheme.typography.titleMedium)
                        Text(if (stopped) "Stopped: it cannot use any app." else "Running under the rules below.", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = stopped, onCheckedChange = { if (it) InAppPolicy.stop("owner switch") else InAppPolicy.resume() })
                }
                HorizontalDivider()
                Text("Full: acts freely. Ask: you approve each action (once or for the task, 10 min). Read: may look, not act. Off: may not open the app.",
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp))
                OutlinedTextField(filter, { filter = it }, label = { Text("Search apps") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp))
            }
            items(apps.filter { filter.isBlank() || it.label.contains(filter, true) || it.pkg.contains(filter, true) }, key = { it.pkg }) { a ->
                val rule = rules[a.pkg] ?: PolicyEngine.DEFAULT_RULE
                Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Text(a.label, style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        PolicyEngine.RULES.forEach { r ->
                            FilterChip(selected = rule == r, onClick = { InAppPolicy.setRule(a.pkg, r, byOwner = true) }, label = { Text(r) })
                        }
                    }
                }
            }
        }
    }
}

/**
 * Asks the owner to approve an action in an app whose rule is "ask": once, or for the rest of the
 * task (both expire after 10 minutes). Secure window, and the agent may not act on it ([OwnerScreens]).
 */
class OwnerApprovalActivity : Activity() {
    private var dialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        show(intent)
    }

    override fun onResume() { super.onResume(); OwnerScreens.shown() }
    override fun onPause() { OwnerScreens.hidden(); super.onPause() }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); show(intent) }

    private fun show(intent: Intent) {
        dialog?.dismiss()
        val id = intent.getStringExtra("id") ?: return finish()
        val p = InAppPolicy.pending(id) ?: return finish()
        val c = p.check
        val what = when (c.effect) {
            Effect.DESTRUCTIVE -> "delete or change something"
            Effect.EXTERNAL -> "send, share or pay"
            else -> "act"
        }
        val detail = buildString {
            append("Ubiq wants to $what in ${InAppPolicy.label(c.pkg)}")
            if (c.label.isNotBlank()) append(": \"${c.label.take(80)}\"")
            if (c.text.isNotBlank()) append("\nText: \"${c.text.take(120)}\"")
            append(".\n\nYou set this app to \"ask\".")
        }
        dialog = AlertDialog.Builder(this)
            .setTitle("Allow Ubiq?")
            .setMessage(detail)
            .setPositiveButton("Allow for this task") { _, _ -> InAppPolicy.answer(id, "task"); finish() }
            .setNeutralButton("Allow once") { _, _ -> InAppPolicy.answer(id, "once"); finish() }
            .setNegativeButton("Deny") { _, _ -> InAppPolicy.answer(id, "deny"); finish() }
            .setOnCancelListener { InAppPolicy.answer(id, "deny"); finish() }
            .create().also {
                it.setOnShowListener { _ -> it.window?.decorView?.filterTouchesWhenObscured = true }
                it.show()
            }
    }

    override fun onDestroy() { dialog?.dismiss(); super.onDestroy() }
}
