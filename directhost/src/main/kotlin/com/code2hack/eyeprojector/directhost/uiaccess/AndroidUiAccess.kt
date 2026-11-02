package com.code2hack.eyeprojector.directhost.uiaccess

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/** Public APIs only. No default-display root, conversation/input content, input invocation or logging. */
internal class AndroidUiAccess(private val service: AccessibilityService) : UiAccess {
    override val canRead: Boolean get() {
        val info = service.serviceInfo ?: return false
        return info.capabilities and AccessibilityServiceInfo.CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT != 0 &&
            info.flags and AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS != 0
    }

    override fun windowsOnAllDisplays(): Map<Int, List<UiAccessWindow>> {
        val all = service.windowsOnAllDisplays
        return (0 until all.size()).associate { index ->
            all.keyAt(index) to all.valueAt(index).map { Window(it) }
        }
    }

    private class Window(private val window: AccessibilityWindowInfo) : UiAccessWindow {
        override var rootRefresh: UiRootRefresh? = null
            private set
        override val metadata: UiWindowMetadata get() = UiWindowMetadata(window.displayId, window.id,
            window.type, window.layer, window.isActive, window.isFocused,
            Rect().also(window::getBoundsInScreen).bounds())
        override fun root(): UiAccessNode? {
            val root = window.root ?: return null
            val node = Node(root)
            try {
                // An empty metadata shell is not evidence of an absent UI. Refresh the SAME
                // returned node once; never replace the original window or walk a second tree.
                if (root.childCount == 0 && root.className == null) {
                    val before = node.observation(0)
                    if (!before.bounds.hasArea) {
                        rootRefresh = try { UiRootRefresh(before, 0, root.refresh()) }
                        catch (error: Exception) { UiRootRefresh(before, 0, null, error.javaClass.name) }
                    }
                }
                return node
            } catch (error: Exception) {
                node.close()
                throw error
            }
        }
        @Suppress("DEPRECATION") override fun close() = window.recycle()
    }

    private class Node(private val node: AccessibilityNodeInfo) : UiAccessNode {
        override val packageName get() = node.packageName?.toString()
        override val windowId get() = node.windowId
        override val childCount get() = node.childCount
        override fun child(index: Int): UiAccessNode? = node.getChild(index)?.let { Node(it) }
        override fun observation(ordinal: Int): UiTargetObservation {
            val actions = node.actionList.map { it.id }
            val clickable = node.isClickable
            val longClickable = node.isLongClickable
            val resourceId = node.viewIdResourceName
            val className = node.className?.toString()
            val editable = node.isEditable
            val password = node.isPassword
            val inputType = node.inputType
            val uniqueId = if (Build.VERSION.SDK_INT >= 33) node.uniqueId?.takeIf { it.isNotBlank() }?.let(::UiNodeUniqueId) else null
            // A supplied accessibility name works for any class. Only button-like controls
            // use static text as a fallback; generic conversation/input text is never read.
            val controlLabelRead = !editable && !password && inputType == 0
            val label = if (controlLabelRead) {
                val description = node.contentDescription?.toString()?.takeIf { it.isNotBlank() }
                if (description != null) fingerprint(UiControlLabelSource.CONTENT_DESCRIPTION, description)
                else if (className in BUTTON_LABEL_CLASSES) node.text?.toString()?.takeIf { it.isNotBlank() }?.let {
                    fingerprint(UiControlLabelSource.BUTTON_TEXT, it)
                } else null
            } else null
            // Flags/actions are copied metadata. User-directed selection uses actual hierarchy
            // and persistent identity, including custom touch regions with no advertised action.
            return UiTargetObservation(ordinal, windowId, packageName.orEmpty(), resourceId,
                className, Rect().also(node::getBoundsInScreen).bounds(), actions,
                clickable, longClickable, node.isVisibleToUser, node.isEnabled, drawingOrder = node.drawingOrder,
                uniqueId = uniqueId, controlLabel = label, editable = editable, password = password, inputType = inputType,
                uniqueIdRead = Build.VERSION.SDK_INT >= 33, controlLabelRead = controlLabelRead)
        }
        @Suppress("DEPRECATION") override fun close() = node.recycle()

        private fun fingerprint(source: UiControlLabelSource, value: String) = UiControlLabel(source, uiIdentityFingerprint(value))

        companion object {
            private val BUTTON_LABEL_CLASSES = setOf("android.widget.Button", "android.widget.ImageButton",
                "android.widget.CheckBox", "android.widget.RadioButton", "android.widget.ToggleButton", "android.widget.Switch")
        }
    }
}

private fun Rect.bounds() = UiBounds(left, top, right, bottom)
