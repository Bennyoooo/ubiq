package com.agentos.workspace;

import android.view.Surface;

/**
 * The background workspace. Implemented twice: by WorkspaceShell in the app, which Shizuku runs as
 * the ADB shell user (stock Android), and by Ubiq Core, a platform-signed app (the ROM).
 */
interface IWorkspaceShell {
    /** Shizuku calls this transaction to tear the helper down. */
    void destroy() = 16777114;

    /** Creates the trusted virtual display rendering into [surface]. Returns the display id, or -1 (see lastError). */
    int createDisplay(in Surface surface, int width, int height, int dpi) = 1;
    String lastError() = 2;
    int displayId() = 3;
    void releaseDisplay() = 4;

    /** `am start --display <id>` of a component (pkg/cls). Returns the command output. */
    String startActivity(String component, int displayId, boolean newTask) = 5;
    boolean injectKey(int displayId, int keyCode) = 6;
    String exec(String command) = 7;
    /** Removes every task on [displayId] except those whose top activity starts with [keepPrefix]. Returns how many. */
    int clearDisplay(int displayId, String keepPrefix) = 8;
    /** The DisplayManager flags the display was created with. */
    int displayFlags() = 9;
    /** One finger of touch input on [displayId]; action is a MotionEvent action (down/move/up/cancel). */
    oneway void injectTouch(int displayId, int action, float x, float y, long downTime, long eventTime) = 10;
    /** True when the display sits in its own power group (kept awake by the system), so it keeps running while the phone sleeps. */
    boolean ownPowerGroup() = 11;
    /** How the display was created: "virtual-device", "display-manager", or "" when none. */
    String displayKind() = 12;
    /** Keeps the display's own power group awake (virtual-device displays only); off lets it doze after the screen timeout. */
    void setDisplayAwake(boolean on) = 13;

    // Several workspaces (one per running session). createDisplay makes a new one on every call.
    /** Releases one display (the virtual device closes with the last one). */
    void releaseDisplayId(int displayId) = 14;
    /** setDisplayAwake for a single display. */
    void setDisplayAwakeFor(int displayId, boolean on) = 15;

    /**
     * Moves the top root task on [fromDisplay] (skipping tasks whose activities start with [skipPrefix], the floor)
     * to the top of [toDisplay], state and all: the user sees the page the agent prepared. Returns the moved task's
     * top activity (pkg/cls), or a string starting with "Error". Older implementations return null.
     */
    String moveTopTask(int fromDisplay, int toDisplay, String skipPrefix) = 17;
}
