package com.agentos.core

import android.content.Context
import com.agentos.workspace.IWorkspaceShell
import com.agentos.workspace.Workspace
import com.agentos.workspace.WorkspaceBackend

/** The ROM: Ubiq Core (platform-signed) runs the workspace in its own process. */
object CoreWorkspaceBackend : WorkspaceBackend {
    override val name = "Ubiq Core"
    override val isCore = true

    override fun init(ctx: Context, listener: WorkspaceBackend.Listener) {
        CoreClient.init(ctx)
        CoreClient.onLost = { listener.lost(this) }
    }

    override fun availability(): Workspace.Availability =
        if (CoreClient.available) Workspace.Availability.Ready(this) else Workspace.Availability.NotInstalled

    override suspend fun connect(): IWorkspaceShell? =
        CoreClient.workspace() ?: null.also { Workspace.lastError = "Ubiq Core is unreachable" }
}
