package dev.tradescanner.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.util.DisplayMetrics
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import dev.tradescanner.automation.MT5Automator
import kotlinx.coroutines.*

class TradeScannerAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "TradeAccessService"
        var instance: TradeScannerAccessibilityService? = null
            private set
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        MT5Automator.getInstance().attachAccessibilityService(this)
        Log.d(TAG, "Service connected")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        MT5Automator.getInstance().detachAccessibilityService()
        serviceScope.cancel()
        Log.d(TAG, "Service destroyed")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // We don't need to react to all events continuously, but we could log for debugging
        // event?.let { Log.v(TAG, "Event: ${it.eventType} pkg=${it.packageName}") }
    }

    override fun onInterrupt() {
        Log.d(TAG, "onInterrupt")
    }

    // ----- Helper methods for automation -----

    fun getRootNode(): AccessibilityNodeInfo? {
        return try {
            rootInActiveWindow ?: windows.firstOrNull()?.root
        } catch (e: Exception) {
            null
        }
    }

    fun findAndClickByTexts(texts: List<String>, exact: Boolean = false): Boolean {
        val root = getRootNode() ?: return false
        for (text in texts) {
            if (clickNodeByText(root, text, exact)) {
                Log.d(TAG, "Clicked by text: $text")
                return true
            }
        }
        // Try all windows
        for (win in windows) {
            val wRoot = win.root ?: continue
            for (text in texts) {
                if (clickNodeByText(wRoot, text, exact)) {
                    Log.d(TAG, "Clicked by text in window: $text")
                    return true
                }
            }
        }
        return false
    }

    private fun clickNodeByText(node: AccessibilityNodeInfo, search: String, exact: Boolean): Boolean {
        val nodes = node.findAccessibilityNodeInfosByText(search)
        if (nodes.isNotEmpty()) {
            for (n in nodes) {
                if (exact && n.text?.toString() != search) continue
                if (tryClickNode(n)) return true
                // try parent if this node not clickable
                var parent = n.parent
                var depth = 0
                while (parent != null && depth < 4) {
                    if (tryClickNode(parent)) return true
                    parent = parent.parent
                    depth++
                }
            }
        }
        // DFS traversal for contentDescription or viewId containing text
        return dfsFindAndClick(node, search)
    }

    private fun dfsFindAndClick(root: AccessibilityNodeInfo, search: String): Boolean {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)
        val lowerSearch = search.lowercase()
        while (stack.isNotEmpty()) {
            val cur = stack.removeFirst()
            val text = cur.text?.toString() ?: ""
            val desc = cur.contentDescription?.toString() ?: ""
            val viewId = cur.viewIdResourceName ?: ""
            if (text.lowercase().contains(lowerSearch) || desc.lowercase().contains(lowerSearch) || viewId.lowercase().contains(lowerSearch)) {
                if (tryClickNode(cur)) return true
            }
            for (i in 0 until cur.childCount) {
                cur.getChild(i)?.let { stack.add(it) }
            }
        }
        return false
    }

    fun findNodeContainingText(search: String): AccessibilityNodeInfo? {
        val root = getRootNode() ?: return null
        val lower = search.lowercase()
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            val cur = stack.removeFirst()
            val text = cur.text?.toString()?.lowercase() ?: ""
            if (text.contains(lower)) return cur
            val desc = cur.contentDescription?.toString()?.lowercase() ?: ""
            if (desc.contains(lower)) return cur
            for (i in 0 until cur.childCount) {
                cur.getChild(i)?.let { stack.add(it) }
            }
        }
        // search other windows
        for (win in windows) {
            val wRoot = win.root ?: continue
            stack.add(wRoot)
            while (stack.isNotEmpty()) {
                val cur = stack.removeFirst()
                val text = cur.text?.toString()?.lowercase() ?: ""
                if (text.contains(lower)) return cur
                for (i in 0 until cur.childCount) {
                    cur.getChild(i)?.let { stack.add(it) }
                }
            }
        }
        return null
    }

    fun clickNode(node: AccessibilityNodeInfo): Boolean {
        return tryClickNode(node)
    }

    private fun tryClickNode(node: AccessibilityNodeInfo): Boolean {
        return try {
            if (node.isClickable) {
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                true
            } else {
                // Try to find clickable ancestor
                var parent = node.parent
                while (parent != null) {
                    if (parent.isClickable) {
                        parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        return true
                    }
                    parent = parent.parent
                }
                false
            }
        } catch (e: Exception) {
            false
        }
    }

    fun findAndInputText(labelTexts: List<String>, value: String): Boolean {
        val root = getRootNode() ?: return false
        // Find label then find nearby EditText
        for (label in labelTexts) {
            val labelNode = findNodeContainingText(label) ?: continue
            // Search siblings and parent children for EditText
            val edit = findEditTextNearNode(labelNode) ?: findEditTextInRoot(root, label)
            if (edit != null) {
                return inputTextInNode(edit, value)
            }
        }
        // Direct search for EditText with hint containing label
        for (label in labelTexts) {
            val edit = findEditTextInRoot(root, label)
            if (edit != null) {
                return inputTextInNode(edit, value)
            }
        }
        return false
    }

    private fun findEditTextNearNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // Check parent's children for EditText
        var parent = node.parent
        var depth = 0
        while (parent != null && depth < 3) {
            for (i in 0 until parent.childCount) {
                val child = parent.getChild(i) ?: continue
                if (child.className?.contains("EditText") == true) {
                    return child
                }
                // check child of child
                for (j in 0 until child.childCount) {
                    val sub = child.getChild(j) ?: continue
                    if (sub.className?.contains("EditText") == true) return sub
                }
            }
            parent = parent.parent
            depth++
        }
        // Also check node's own children
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (child.className?.contains("EditText") == true) return child
        }
        return null
    }

    private fun findEditTextInRoot(root: AccessibilityNodeInfo, hint: String): AccessibilityNodeInfo? {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            val cur = stack.removeFirst()
            val className = cur.className?.toString() ?: ""
            if (className.contains("EditText")) {
                // Check if near label or has hint
                // For simplicity return first EditText that is near bottom half? Or check all
                // We'll check its surrounding text
                // This simplistic version returns first EditText found after label, but we refine by scanning order
                // So we need to avoid returning unrelated EditText
                // Use heuristic: if root contains hint text, consider EditText close in y
            }
            for (i in 0 until cur.childCount) {
                cur.getChild(i)?.let { stack.add(it) }
            }
        }
        // Alternative: collect all EditTexts and return the one whose bounds are closest to label node
        return collectAllEditTexts(root).firstOrNull()
    }

    private fun collectAllEditTexts(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val list = mutableListOf<AccessibilityNodeInfo>()
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            val cur = stack.removeFirst()
            if (cur.className?.contains("EditText") == true) list.add(cur)
            for (i in 0 until cur.childCount) {
                cur.getChild(i)?.let { stack.add(it) }
            }
        }
        return list
    }

    private fun inputTextInNode(editNode: AccessibilityNodeInfo, value: String): Boolean {
        return try {
            // Try set text action
            val args = Bundle()
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
            val result = editNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            if (result) {
                Log.d(TAG, "Set text via ACTION_SET_TEXT: $value")
                return true
            }
            // Fallback: focus and paste via clipboard
            editNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            Thread.sleep(100)
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("mt5", value))
            editNode.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            Log.d(TAG, "Set text via clipboard paste: $value")
            true
        } catch (e: Exception) {
            Log.e(TAG, "inputText error", e)
            false
        }
    }

    fun inputTextInFocusedField(value: String): Boolean {
        // Find focused EditText
        val root = getRootNode() ?: return false
        val focused = findFocusedEditText(root) ?: return false
        return inputTextInNode(focused, value)
    }

    private fun findFocusedEditText(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (root.isFocused && root.className?.contains("EditText") == true) return root
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            val result = findFocusedEditText(child)
            if (result != null) return result
        }
        return null
    }

    // Gesture based clicks - more reliable when node search fails
    fun clickAt(x: Int, y: Int) {
        try {
            val path = Path()
            path.moveTo(x.toFloat(), y.toFloat())
            val builder = GestureDescription.Builder()
            builder.addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            dispatchGesture(builder.build(), object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    super.onCompleted(gestureDescription)
                    Log.d(TAG, "Gesture click at $x,$y completed")
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    super.onCancelled(gestureDescription)
                    Log.w(TAG, "Gesture click at $x,$y cancelled")
                }
            }, null)
        } catch (e: Exception) {
            Log.e(TAG, "clickAt error", e)
        }
    }

    fun clickAtRelative(relX: Float, relY: Float) {
        val metrics = resources.displayMetrics
        val x = (metrics.widthPixels * relX).toInt()
        val y = (metrics.heightPixels * relY).toInt()
        clickAt(x, y)
    }

    fun swipe(startX: Int, startY: Int, endX: Int, endY: Int, duration: Long = 300) {
        val path = Path()
        path.moveTo(startX.toFloat(), startY.toFloat())
        path.lineTo(endX.toFloat(), endY.toFloat())
        val builder = GestureDescription.Builder()
        builder.addStroke(GestureDescription.StrokeDescription(path, 0, duration))
        dispatchGesture(builder.build(), null, null)
    }
}
