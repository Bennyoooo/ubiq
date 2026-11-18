package com.agentos.control

import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.util.DisplayMetrics
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/** One interactive or informative element on screen, numbered so the model can refer to it. */
class UiNode(
    val id: Int,
    val className: String,
    val text: String,
    val desc: String,
    val resId: String,
    val bounds: Rect,
    val clickable: Boolean,
    val longClickable: Boolean,
    val editable: Boolean,
    val scrollable: Boolean,
    val checkable: Boolean,
    val checked: Boolean,
    val focused: Boolean,
    val selected: Boolean,
    val enabled: Boolean,
    val node: AccessibilityNodeInfo,
    /** Inside a WebView: ACTION_CLICK is acknowledged but not delivered to the page there, so taps must be real gestures. */
    val inWebView: Boolean = false,
    /** A password field: its text is a secret (see agent/Secrets). */
    val password: Boolean = false,
    /** The field shows its placeholder, not typed text (so [text] is the hint). */
    val showingHint: Boolean = false,
) {
    /** What the field really holds: empty while it shows its hint. */
    val value get() = if (showingHint) "" else text

    val cx get() = bounds.centerX()
    val cy get() = bounds.centerY()

    fun render(): String {
        val sb = StringBuilder()
        sb.append('[').append(id).append("] ").append(className)
        if (text.isNotEmpty() && showingHint) sb.append(" hint=\"").append(text.take(80)).append('"')
        else if (text.isNotEmpty()) sb.append(" \"").append(text.take(120)).append('"')
        if (desc.isNotEmpty() && desc != text) sb.append(" desc=\"").append(desc.take(80)).append('"')
        if (resId.isNotEmpty()) sb.append(" id=").append(resId)
        val flags = mutableListOf<String>()
        if (clickable) flags += "clickable"
        if (longClickable && !clickable) flags += "long-clickable"
        if (editable) flags += "editable"
        if (password) flags += "password"
        if (scrollable) flags += "scrollable"
        if (checkable) flags += if (checked) "checked" else "unchecked"
        if (selected) flags += "selected"
        if (focused) flags += "focused"
        if (!enabled) flags += "disabled"
        if (flags.isNotEmpty()) sb.append(" (").append(flags.joinToString(", ")).append(')')
        sb.append(" @(").append(cx).append(',').append(cy).append(')')
        return sb.toString()
    }
}

class ScreenSnapshot(
    val packageName: String,
    val nodes: List<UiNode>,
    val keyboardVisible: Boolean,
    val screenW: Int,
    val screenH: Int,
    val truncated: Boolean,
) {
    fun find(id: Int): UiNode? = nodes.firstOrNull { it.id == id }

    /**
     * What a control says: its own text or description, else the first text inside it. Compose
     * apps put the label ("Send SMS", "Pay") on a child of the clickable node.
     */
    fun labelOf(n: UiNode): String = n.text.ifEmpty { n.desc }.trim().ifEmpty {
        nodes.firstOrNull { it.id > n.id && n.bounds.contains(it.bounds) && !it.clickable && (it.text.isNotBlank() || it.desc.isNotBlank()) }
            ?.let { it.text.ifEmpty { it.desc }.trim() }.orEmpty()
    }

    fun render(): String {
        val sb = StringBuilder()
        sb.append("app=").append(packageName)
            .append(" screen=").append(screenW).append('x').append(screenH)
        if (keyboardVisible) sb.append(" keyboard=visible")
        sb.append('\n')
        if (nodes.isEmpty()) sb.append("(no accessible elements found; try a screenshot)\n")
        for (n in nodes) sb.append(n.render()).append('\n')
        if (truncated) sb.append("... (more elements not shown; scroll or use screenshot)\n")
        return sb.toString()
    }
}

object ScreenReader {
    private const val MAX_NODES = 160

    fun capture(service: ControlService, displayId: Int = Display.DEFAULT_DISPLAY, maxNodes: Int = MAX_NODES): ScreenSnapshot {
        val metrics = if (displayId == Display.DEFAULT_DISPLAY) service.resources.displayMetrics else DisplayMetrics().also { dm ->
            val d = service.getSystemService(DisplayManager::class.java).getDisplay(displayId)
            if (d != null) @Suppress("DEPRECATION") d.getRealMetrics(dm) else service.resources.displayMetrics.let { dm.setTo(it) }
        }
        val out = ArrayList<UiNode>()
        var keyboard = false
        var pkg = ""
        var truncated = false

        val windows = service.windowsOn(displayId)
        val roots = ArrayList<AccessibilityNodeInfo>()
        if (windows.isNotEmpty()) {
            // Topmost (highest layer) windows first so dialogs come before what is underneath.
            for (w in windows.sortedByDescending { it.layer }) {
                if (w.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) { keyboard = true; continue }
                if (w.type == AccessibilityWindowInfo.TYPE_SYSTEM) continue
                val r = w.root ?: continue
                // Our own Stop chip is not part of the app being driven: a tap on it would stop the task.
                if (w.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY && r.packageName == service.packageName) continue
                roots += r
            }
        }
        if (roots.isEmpty() && displayId == Display.DEFAULT_DISPLAY) service.rootInActiveWindow?.let { roots += it }

        var nextId = 1
        for (root in roots) {
            if (pkg.isEmpty()) pkg = root.packageName?.toString() ?: ""
            val stack = ArrayDeque<Pair<AccessibilityNodeInfo, Boolean>>()
            stack.addLast(root to false)
            while (stack.isNotEmpty()) {
                if (out.size >= maxNodes) { truncated = true; break }
                val (n, parentWeb) = stack.removeLast()
                val inWeb = parentWeb || n.className?.toString()?.endsWith("WebView") == true
                val b = Rect(); n.getBoundsInScreen(b)
                val visible = n.isVisibleToUser && b.width() > 0 && b.height() > 0 &&
                    b.right > 0 && b.bottom > 0 && b.left < metrics.widthPixels && b.top < metrics.heightPixels
                if (visible) {
                    val text = n.text?.toString()?.trim().orEmpty()
                    val desc = n.contentDescription?.toString()?.trim().orEmpty()
                    val interesting = text.isNotEmpty() || desc.isNotEmpty() || n.isClickable || n.isEditable ||
                        n.isScrollable || n.isCheckable || n.isLongClickable
                    if (interesting) {
                        val cls = (n.className?.toString() ?: "View").substringAfterLast('.')
                        out += UiNode(
                            id = nextId++, className = cls, text = text, desc = desc,
                            resId = n.viewIdResourceName?.substringAfter('/').orEmpty(),
                            bounds = b, clickable = n.isClickable, longClickable = n.isLongClickable,
                            editable = n.isEditable, scrollable = n.isScrollable, checkable = n.isCheckable,
                            checked = n.isChecked, focused = n.isFocused, selected = n.isSelected,
                            enabled = n.isEnabled, node = n, inWebView = inWeb,
                            password = n.isPassword, showingHint = n.isShowingHintText,
                        )
                    }
                }
                // Push children in reverse so traversal order is top-to-bottom, left-to-right.
                for (i in n.childCount - 1 downTo 0) n.getChild(i)?.let { stack.addLast(it to inWeb) }
            }
            if (truncated) break
        }
        return ScreenSnapshot(pkg, out, keyboard, metrics.widthPixels, metrics.heightPixels, truncated)
    }
}
