package com.code2hack.eyeprojector.directhost.uiaccess

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/** Public service APIs only. No default-display root, content reads, action invocation or logging. */
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
        override val metadata: UiWindowMetadata get() = UiWindowMetadata(window.displayId, window.id,
            window.type, window.layer, window.isActive, window.isFocused,
            Rect().also(window::getBoundsInScreen).bounds())
        override fun root(): UiAccessNode? = window.root?.let { Node(it) }
        @Suppress("DEPRECATION") override fun close() = window.recycle()
    }

    private class Node(private val node: AccessibilityNodeInfo) : UiAccessNode {
        override val packageName get() = node.packageName?.toString()
        override val windowId get() = node.windowId
        override val childCount get() = node.childCount
        override fun child(index: Int): UiAccessNode? = node.getChild(index)?.let { Node(it) }
        override fun observation(ordinal: Int): UiTargetObservation? {
            val actions = node.actionList.map { it.id }
            val clickable = node.isClickable
            val longClickable = node.isLongClickable
            if (!clickable && !longClickable && actions.none { it == AccessibilityNodeInfo.ACTION_CLICK ||
                    it == AccessibilityNodeInfo.ACTION_LONG_CLICK ||
                    it == AccessibilityNodeInfo.AccessibilityAction.ACTION_PRESS_AND_HOLD.id }) return null
            return UiTargetObservation(ordinal, windowId, packageName.orEmpty(), node.viewIdResourceName,
                node.className?.toString(), Rect().also(node::getBoundsInScreen).bounds(), actions,
                clickable, longClickable, node.isVisibleToUser, node.isEnabled)
        }
        @Suppress("DEPRECATION") override fun close() = node.recycle()
    }
}

private fun Rect.bounds() = UiBounds(left, top, right, bottom)
