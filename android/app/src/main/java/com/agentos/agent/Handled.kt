package com.agentos.agent

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Things Ubiq dealt with today that leave no task behind (a screened call), for the Ubiq brick on home. Counts reset
 * at midnight. Tasks and job runs aren't counted here: they are sessions, which home reads directly.
 */
object Handled {
    private const val PREFS = "ubiq_handled"
    private val _calls = MutableStateFlow(0)
    /** Calls Ubiq answered for the user today. */
    val calls: StateFlow<Int> = _calls

    private fun today() = java.time.LocalDate.now().toString()

    fun read(ctx: Context): Int {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return (if (p.getString("day", null) == today()) p.getInt("calls", 0) else 0).also { _calls.value = it }
    }

    fun callScreened(ctx: Context) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val n = read(ctx) + 1
        p.edit().putString("day", today()).putInt("calls", n).apply()
        _calls.value = n
    }
}
