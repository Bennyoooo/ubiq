package com.agentos.core;

import com.agentos.workspace.IWorkspaceShell;

/**
 * Ubiq Core: the platform-signed broker that holds every privilege the agent app does not.
 * Only ai.ubiq.app holding com.agentos.core.permission.CONTROL may bind (checked on every call).
 */
interface ICore {
    /**
     * One tool call. Request: {"opId","tool","args":{},"origin":"owner|agent|job|trigger","task"}.
     * Reply: {"ok","state":"done|failed|unknown|refused|approval_required","result",
     *         "receipt":{"opId","tool","argsHash","at","evidence"}}.
     * Calls with effects are journaled by opId: repeating an opId returns the first reply.
     */
    String call(String requestJson) = 1;

    /** The background workspace (virtual displays, input, activity starts), run inside Core. */
    IWorkspaceShell getWorkspace() = 2;

    /** Ties the caller's workspace displays to [token]: Core releases them when the caller's process dies. */
    void linkClient(IBinder token) = 3;

    /** Contract version (CoreContract.VERSION). */
    int version() = 4;

    /**
     * The active phone call's audio, 16-bit little-endian mono PCM at sampleRate (8000, 16000 or 48000):
     * [0] reads what the caller says, [1] writes what Ubiq says into the call. Throws when no call is
     * active or the phone cannot intercept call audio. Since version 2.
     */
    ParcelFileDescriptor[] openCallAudio(int sampleRate) = 5;
}
