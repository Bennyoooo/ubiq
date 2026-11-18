package com.agentos.workspace

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.TextView

/**
 * The "desktop" of the hidden workspace display. It is launched once by the shell helper; after
 * that Ubiq itself is present on the display and may start any app there. Pressing "home" in
 * the workspace brings this back to the front.
 */
class WorkspaceHomeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // One floor per workspace display; remember its task so Desk.home() can bring it forward.
        display?.displayId?.let { Workspace.floorTasks[it] = taskId }
        setContentView(TextView(this).apply {
            text = "Ubiq workspace\n\nThe agent opens apps here, out of your way."
            gravity = Gravity.CENTER
            setTextColor(Color.argb(200, 255, 255, 255))
            textSize = 18f
            setBackgroundColor(Color.rgb(18, 16, 28))
        })
    }

    override fun onBackPressed() { /* The workspace floor never closes. */ }
}
