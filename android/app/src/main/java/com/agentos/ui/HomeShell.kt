package com.agentos.ui

import android.app.Activity
import androidx.compose.runtime.Composable
import java.util.ServiceLoader

/**
 * A phone home screen built around the agent. This app is the agent's own space (the conversation,
 * chats, Space and settings) and ships no home screen. A build for a whole phone OS can add one by
 * registering an implementation in `META-INF/services/com.agentos.ui.HomeShell` and making
 * [HomeActivity] the HOME activity in its manifest. Then the app opens on [Content], and leaving
 * the main conversation goes back to it.
 */
interface HomeShell {
    /** Home and its own pages. [reset] changes each time the user presses Home: show home itself again. */
    @Composable fun Content(nav: ShellNav, reset: Int)

    /** The activity resumed (with home showing or not). */
    fun onResume(activity: Activity) {}

    companion object {
        /** The registered home screen, or null (the app alone). */
        val installed: HomeShell? by lazy {
            try { ServiceLoader.load(HomeShell::class.java, HomeShell::class.java.classLoader).firstOrNull() }
            catch (t: Throwable) { android.util.Log.w("AgentOS/HomeShell", "load", t); null }
        }
    }
}

/** What a home screen can open in the app. */
class ShellNav(
    /** The main conversation. */
    val chat: () -> Unit,
    /** The main conversation, listening. */
    val voice: () -> Unit,
    /** One chat or task by session id. */
    val open: (String) -> Unit,
)
