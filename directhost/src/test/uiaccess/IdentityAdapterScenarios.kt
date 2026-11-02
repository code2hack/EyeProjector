package com.code2hack.eyeprojector.directhost.uiaccess

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Build
import android.util.SparseArray
import android.view.accessibility.AccessibilityNodeInfo as Node
import android.view.accessibility.AccessibilityWindowInfo as Window
import com.code2hack.eyeprojector.directhost.ProjectionContext
import com.code2hack.eyeprojector.protocol.Geometry
import java.security.MessageDigest

/** Actual Android adapter/reader, named framework handles; no live-app label is invented here. */
internal object IdentityAdapterScenarios {
    private fun read(node: Node): UiTargetObservation {
        val size = Geometry(400, 400)
        val source = ManagedUiSource(ProjectionContext("owner", "session"), "display", 34, "display",
            "selected.app", null, UiSourceGeometry(size, size, 320, 0, false, size))
        val window = Window(node = node)
        val result = UiTargetReader(AndroidUiAccess(AccessibilityService(
            windowsOnAllDisplays = SparseArray(mapOf(34 to listOf(window)))))) { 1L }.read(UiTargetRequest(source, "selected.app"))
        check(result.status == UiTargetReadStatus.READ)
        check(node.closed == 1 && window.closed == 1 && window.rootsRead == 1 && node.childrenRead == 0)
        return result.windows.single().targets.single()
    }
    private fun digest(value: String) = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    @JvmStatic fun main(args: Array<String>) {
        Build.VERSION.SDK_INT = 35
        val label = "Fixture control label — not an observed app value"
        val button = Node(viewIdResourceName = null, reportedUniqueId = "fixture-stable-control",
            screen = Rect(101, 152, 109, 159), isClickable = false, actionList = emptyList(),
            controlDescription = label, controlText = "must not replace supplied description")
        val observed = read(button)
        check(observed.uniqueId?.value == "fixture-stable-control")
        check(observed.uniqueIdRead && observed.controlLabelRead)
        check(observed.controlLabel == UiControlLabel(UiControlLabelSource.CONTENT_DESCRIPTION, digest(label)))
        check(button.descriptionReads == 1 && button.textReads == 0)
        check(label !in observed.toString() && "must not replace" !in observed.toString() && "fixture-stable-control" !in observed.toString())

        val textButton = Node(controlText = label, isClickable = false)
        check(read(textButton).controlLabel == UiControlLabel(UiControlLabelSource.BUTTON_TEXT, digest(label)))
        check(textButton.textReads == 1)

        for (node in listOf(Node(isEditable = true, forbidLabelRead = true),
            Node(isPassword = true, forbidLabelRead = true), Node(inputType = 1, forbidLabelRead = true),
            Node(className = "android.widget.EditText", isEditable = true, forbidLabelRead = true),
            Node(className = "android.view.View", isEditable = true, controlDescription = label, forbidLabelRead = true))) {
            val current = read(node)
            check(current.controlLabel == null && !current.controlLabelRead && node.descriptionReads == 0 && node.textReads == 0)
        }
        for (className in listOf("android.view.View", "android.widget.TextView", "android.widget.EditText", "example.CustomControl")) {
            val generic = Node(className = className, controlDescription = label,
                controlText = "private conversation/input content", forbidTextRead = true)
            val current = read(generic)
            check(current.className == className && current.controlLabel == UiControlLabel(UiControlLabelSource.CONTENT_DESCRIPTION, digest(label)))
            check(current.controlLabelRead && generic.descriptionReads == 1 && generic.textReads == 0)
            check(label !in current.toString() && "private conversation/input content" !in current.toString())
            val unnamed = Node(className = className, controlText = "private conversation/input content", forbidTextRead = true)
            check(read(unnamed).controlLabel == null && unnamed.descriptionReads == 1 && unnamed.textReads == 0)
        }
        check(read(Node()).let { it.controlLabel == null && it.controlLabelRead }) // Observed absence differs from an unread label.
        Build.VERSION.SDK_INT = 32
        check(read(Node(viewIdResourceName = "selected.app:id/old", forbidUniqueIdRead = true)).let { it.uniqueId == null && !it.uniqueIdRead })
        Build.VERSION.SDK_INT = 35
        println("PASS actual identity metadata copy, nullable API33 uniqueId, semantic control label fingerprints, no input/conversation label reads, no raw label diagnostics, one original traversal and cleanup")
    }
}
