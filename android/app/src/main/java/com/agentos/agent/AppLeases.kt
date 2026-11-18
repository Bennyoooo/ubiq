package com.agentos.agent

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first

/**
 * Which session is using which app. Android gives most apps one task, so two sessions in the same
 * messenger would yank it back and forth between their desks; a session leases the app while its
 * turn runs and others wait for it. Apps that run as several independent windows (see AppCaps)
 * are not leased. The key "screen" leases the visible screen to one session at a time.
 */
object AppLeases {
    const val SCREEN = "screen"
    private val _holders = MutableStateFlow<Map<String, String>>(emptyMap())
    val holders: StateFlow<Map<String, String>> = _holders

    /** Waits until [key] is free (or already ours), then takes it. [onWait] gets the holder's title while waiting. */
    suspend fun acquire(key: String, session: Session, onWait: (String) -> Unit) {
        while (true) {
            val holder = synchronized(this) {
                val h = _holders.value[key]
                val stale = h != null && AgentRuntime.session(h)?.isActive != true
                if (h == null || h == session.id || stale) { _holders.value = _holders.value + (key to session.id); null } else h
            } ?: return
            onWait(AgentRuntime.session(holder)?.title?.value ?: "another task")
            _holders.first { it[key] != holder }
        }
    }

    fun releaseAll(sessionId: String) = synchronized(this) {
        _holders.value = _holders.value.filterValues { it != sessionId }
    }

    fun holder(key: String): String? = _holders.value[key]
}
