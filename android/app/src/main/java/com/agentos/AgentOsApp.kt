package com.agentos

import android.app.Application
import com.agentos.agent.AgentRuntime
import com.agentos.voice.Speaker
import com.agentos.workspace.Workspace
import kotlinx.coroutines.launch

class AgentOsApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        Speaker.init(this)
        com.agentos.space.Space.init(this)
        com.agentos.connect.McpServers.init(this)
        AgentRuntime.init(this)
        com.agentos.rules.InAppPolicy.init(this)
        Workspace.init(this)
        com.agentos.notify.NotificationStore.init(this)
        com.agentos.memory.Lifelog.init(this)
        com.agentos.memory.Places.start(this)
        com.agentos.notify.NotifyRules.init(this)
        com.agentos.jobs.Jobs.init(this)
        com.agentos.agent.MiniAppPlugin.installed?.init(this)
    }

    /** Under memory pressure, drop the on-device model (if the build has one) and Pi's Node unless a task is using them. */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_RUNNING_LOW && com.agentos.agent.AgentRuntime.active().isEmpty())
            com.agentos.agent.OnDeviceModel.installed?.let { m -> com.agentos.agent.AgentRuntime.scope.launch { try { m.unloadAll() } catch (_: Throwable) {} } }
        if (level >= TRIM_MEMORY_RUNNING_LOW) com.agentos.agent.PiRuntime.stop("low memory")
    }

    companion object {
        lateinit var instance: AgentOsApp
            private set
    }
}
