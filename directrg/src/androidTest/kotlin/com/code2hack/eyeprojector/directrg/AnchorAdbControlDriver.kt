package com.code2hack.eyeprojector.directrg

import android.app.Instrumentation
import android.content.BroadcastReceiver
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import com.code2hack.eyeprojector.protocol.Geometry
import com.code2hack.eyeprojector.protocol.Fit
import org.json.JSONObject
import java.io.File
import java.lang.ref.WeakReference

internal fun runAnchorAdbControlDriver(instrumentation: Instrumentation, args: Bundle, id: String, report: JSONObject) {
    val driver = AnchorAdbControlDriver(instrumentation, args, id)
    val observerArgs = Bundle(args).apply {
        putString("navigationMetadata", "true")
        putString("gestureGuide", "false")
        putString("stopOnRequest", "false")
    }
    try { runNativeInputObservation(instrumentation, observerArgs, id, report, driver) }
    finally { report.put("adbControl", driver.summary()) }
}

/**
 * Opt-in unattended test driver. Reflection is limited to declared own-app fields/methods in
 * f6d/2703 and frozen115 snapshots; final matched SDK/device qualification is still required.
 * The host supplies a source-attributed plan from actual Phone display/fixture observations.
 */
internal class AnchorAdbControlDriver(private val instrumentation: Instrumentation, private val args: Bundle,
                                    private val id: String) : NativeObservationAutomation {
    private val planFile = File(instrumentation.targetContext.filesDir, "probe-$id-adb-plan.json")
    private var receiver = WeakReference<ReceiverActivity>(null)
    private var queue: NativeInputObservationQueue? = null
    private var closed = false
    private var initialConnectionAccounted = false
    private var awaitPlanRecorded = false
    private val unavailable = linkedMapOf<String, Map<String, String>>()
    private var commands = emptyMap<String, JSONObject>()
    private var aimedPointer: PointerOverlay? = null
    private var restoreRunning = false
    private var aim: AnchorAdbSyntheticAim? = null
    private var aimedBounds: PointerBounds? = null
    private var aimedRotation: Int? = null
    private var aimTarget: Pair<Float, Float>? = null
    private var lastSnapshot: Map<String, Any?> = emptyMap()
    private var publishedState: Map<String, Any?> = emptyMap()
    private val phases = AnchorAdbPhasePlan { event, phase, reason ->
        record(event, mapOf("phase" to phase, "reason" to reason))
    }
    override var activityLaunched = false; private set
    override val activityFinished = false // Leave the normal Receiver/session to its existing lifetime owner.
    override var syntheticInputInjected = false; private set

    override fun installed(queue: NativeInputObservationQueue) {
        this.queue = queue
        record("observer_installed", mapOf("planFile" to planFile.name))
        val intent = Intent().setClassName(instrumentation.targetContext.packageName, ReceiverActivity::class.java.name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val activity = instrumentation.startActivitySync(intent) as ReceiverActivity
        receiver = WeakReference(activity); activityLaunched = true
        record("own_receiver_started", mapOf("injectionKind" to "normal_activity_start"))
    }

    override fun tick() {
        if (closed) return
        onMain {
            val activity = receiver.get() ?: return@onMain
            val pointer = field(activity, "pointer") as? PointerOverlay
            aimTarget?.let { target ->
                if (pointer != null && pointer === aimedPointer && activity.hasWindowFocus() && field(activity, "resumed") == true) {
                    val model = field(pointer, "model") as HeadPointerModel
                    val bounds = field(model, "bounds") as PointerBounds
                    val rotation = activity.display?.rotation ?: 0
                    if (field(pointer, "running") == true || bounds != aimedBounds || rotation != aimedRotation) {
                        pointer.stop()
                        aimedBounds = bounds; aimedRotation = rotation
                        aim = AnchorAdbSyntheticAim(model, bounds, rotation)
                        record("synthetic_pose_rebound", mapOf("injectionKind" to "synthetic_rotation_sample"))
                    }
                    aim?.tick(target.first, target.second, SystemClock.elapsedRealtimeNanos())
                    pointer.onPositionChanged?.invoke()
                    pointer.invalidate()
                }
            }
            lastSnapshot = snapshot(activity)
            // Publish actual setup transitions even when streaming/access/plan is unavailable.
            val state = lastSnapshot - setOf("pointerX", "pointerY")
            if (state != publishedState) {
                publishedState = state
                record("source_state", mapOf("source" to lastSnapshot))
            }
            if (!initialConnectionAccounted && (lastSnapshot["streaming"] == true || field(activity, "session") != null ||
                field(activity, "connectRequested") == true || field(activity, "connectBeforeBinding") == true)) {
                initialConnectionAccounted = true
                record("initial_connection_observed", mapOf("source" to lastSnapshot))
            }
            if (args.getString("autoConnect") != "false" && !initialConnectionAccounted && lastSnapshot["focused"] == true &&
                lastSnapshot["streaming"] == false && field(activity, "session") == null &&
                field(activity, "connectRequested") != true && field(activity, "connectBeforeBinding") != true) {
                val button = field(activity, "connectButton") as? Button
                if (button != null && button.isShown && button.isEnabled) {
                    initialConnectionAccounted = true
                    record("own_connect_submitted", mapOf("injectionKind" to "own_view_performClick"))
                    syntheticInputInjected = true
                    button.performClick()
                }
            }
        }
        if (!planFile.exists()) {
            if (!awaitPlanRecorded) { awaitPlanRecorded = true; record("await_plan", mapOf("planFile" to planFile.name)) }
            return
        }
        val plan = try { JSONObject(planFile.readText()) } catch (error: Exception) {
            record("plan_read_error", mapOf("errorType" to error.javaClass.simpleName)); return
        }
        check(plan.getString("runId") == id) { "Test plan run mismatch" }
        plan.optJSONArray("unavailable")?.let { entries ->
            for (i in 0 until entries.length()) {
                val item = entries.getJSONObject(i)
                val scope = item.getString("scope")
                val fact = mapOf("scope" to scope, "reason" to item.getString("reason"),
                    "sourceWitness" to item.getString("sourceWitness"))
                if (unavailable[scope] != fact) { unavailable[scope] = fact; record("setup_unavailable", fact) }
            }
        }
        val entries = plan.getJSONArray("commands")
        val values = (0 until entries.length()).map { entries.getJSONObject(it) }
        commands = values.associateBy { it.getString("id") }
        fun predicates(command: JSONObject, name: String): Map<String, Boolean> {
            val obj = command.optJSONObject(name) ?: return emptyMap()
            return obj.keys().asSequence().associateWith { obj.getBoolean(it) }
        }
        val steps = values.map { AnchorAdbPhasePlan.Step(it.getString("id"), it.getString("op"),
            it.optLong("timeoutMs", 10000), predicates(it, "before"), predicates(it, "after")) }
        val booleans = lastSnapshot.filterValues { it is Boolean }.mapValues { it.value as Boolean }
        phases.tick(steps, SystemClock.elapsedRealtime(), booleans) { step ->
            onMain {
                val activity = receiver.get() ?: error("Original Receiver gone")
                val command = commands.getValue(step.id)
                val before = snapshot(activity)
                if (command.has("sourceSessionIdentity")) check(command.getInt("sourceSessionIdentity") == before["sessionIdentity"])
                executeObserved(activity, command)
                lastSnapshot = snapshot(activity)
                record("own_action", mapOf("phase" to step.id, "operation" to step.op,
                    "beforeSource" to before, "afterSource" to lastSnapshot))
            }
        }
    }

    private fun executeObserved(activity: ReceiverActivity, command: JSONObject) {
        try { execute(activity, command) }
        catch (error: Throwable) {
            try {
                record("own_action_exception", mapOf("phase" to command.optString("id"),
                    "operation" to command.optString("op"), "exceptionType" to error.javaClass.name,
                    "exceptionMessage" to error.message, "exceptionStack" to error.stackTraceToString(),
                    "causeType" to error.cause?.javaClass?.name, "causeMessage" to error.cause?.message,
                    "causeStack" to error.cause?.stackTraceToString()))
            } catch (_: Throwable) { /* Optional diagnostics cannot replace the original failure. */ }
            throw error
        }
    }

    private fun reportNativeFunction(activity: ReceiverActivity, command: JSONObject) {
        val action = command.getString("action")
        check(action in FUNCTION_REPORT_ACTIONS) { "Unsupported original Function report" }
        val original = field(activity, "functionInput") ?: error("Original NativeFunctionInput unavailable")
        check(field(original, "registered") == true) { "Original NativeFunctionInput is not registered" }
        val receiver = field(original, "receiver") as? BroadcastReceiver ?: error("Original Function receiver unavailable")
        syntheticInputInjected = true
        receiver.onReceive(activity, Intent(action).setPackage(activity.packageName))
        record("receiver_report_submitted", mapOf("injectionKind" to "direct_original_own_receiver_onReceive",
            "actionName" to action, "functionInputIdentity" to System.identityHashCode(original),
            "receiverIdentity" to System.identityHashCode(receiver),
            "scope" to "Synthetic same-process original registered Function receiver entry; no OS broadcast, firmware or target acknowledgment"))
    }

    private fun execute(activity: ReceiverActivity, command: JSONObject) {
        when (command.getString("op")) {
            "observe" -> record("observed", mapOf("injectionKind" to "none", "source" to snapshot(activity)))
            "aim" -> {
                val target = target(activity, command)
                val pointer = field(activity, "pointer") as PointerOverlay
                if (aimedPointer !== pointer) {
                    restoreAim(activity)
                    restoreRunning = field(pointer, "running") == true
                    pointer.stop() // Replace only acquisition in this test; retain actual model/capture logic.
                    val model = field(pointer, "model") as HeadPointerModel
                    val bounds = field(model, "bounds") as PointerBounds
                    aimedBounds = bounds; aimedRotation = activity.display?.rotation ?: 0
                    aim = AnchorAdbSyntheticAim(model, bounds, aimedRotation!!)
                    aimedPointer = pointer
                }
                aimTarget = target
                syntheticInputInjected = true
                aim!!.tick(target.first, target.second, SystemClock.elapsedRealtimeNanos())
                record("synthetic_pose_started", mapOf("injectionKind" to "synthetic_rotation_sample",
                    "targetX" to target.first, "targetY" to target.second))
            }
            "surface_tap" -> {
                val target = target(activity, command)
                val root = field(activity, "root") as View
                val location = IntArray(2); root.getLocationInWindow(location)
                val down = SystemClock.uptimeMillis()
                for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                    val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action,
                        target.first + location[0], target.second + location[1], 0)
                    event.source = 0x1002 // Explicit virtual touchscreen injection, not native pad/hover.
                    try {
                        syntheticInputInjected = true
                        val handled = activity.dispatchTouchEvent(event)
                        record("touch_edge_submitted", mapOf("injectionKind" to "own_activity_dispatch_MotionEvent",
                            "actionMasked" to action, "downTimeMs" to event.downTime,
                            "eventTimeMs" to event.eventTime, "ownDispatchReturned" to handled))
                    } finally { event.recycle() }
                }
                record("touch_submitted", mapOf("injectionKind" to "own_activity_dispatch_MotionEvent", "downTimeMs" to down))
            }
            "own_view_click" -> {
                val name = command.getString("view")
                val view = if (name == "connect") field(activity, "connectButton") as Button else {
                    val overlay = field(activity, "anchorOverlay") as? View ?: error("Matched Anchor overlay unavailable")
                    check(overlay.isShown)
                    val buttons = field(overlay, "buttons") as List<*>
                    buttons[listOf("chooser_click", "chooser_hold", "chooser_cancel").indexOf(name)] as Button
                }
                check(view.isShown && view.isEnabled)
                syntheticInputInjected = true
                view.performClick()
                record("view_click_submitted", mapOf("injectionKind" to "own_view_performClick", "view" to name))
            }
            "native_broadcast" -> {
                val action = command.getString("action")
                check(action in BROADCASTS) { "Unsupported test ingress" }
                syntheticInputInjected = true
                instrumentation.targetContext.sendBroadcast(Intent(action).setPackage(instrumentation.targetContext.packageName))
                record("broadcast_submitted", mapOf("injectionKind" to "injected_native_broadcast", "actionName" to action))
            }
            "native_receiver_report" -> reportNativeFunction(activity, command)
            "callback" -> {
                val callback = command.getString("callback")
                check(callback in listOf("contextualBack", "contextualHome", "showControlPanel", "showHidBindings"))
                val method = activity.javaClass.declaredMethods.single { it.name.substringBefore('$') == callback && it.parameterCount == 0 }
                method.isAccessible = true; syntheticInputInjected = true; method.invoke(activity)
                record("callback_submitted", mapOf("injectionKind" to "direct_own_callback", "callback" to callback))
            }
            else -> error("Unsupported test command")
        }
    }

    private fun target(activity: ReceiverActivity, command: JSONObject): Pair<Float, Float> {
        val target = command.getJSONObject("target")
        val geometry = field(activity, "currentGeometry") as? Geometry ?: error("No current encoded geometry")
        check(target.getInt("geometryWidth") == geometry.width && target.getInt("geometryHeight") == geometry.height)
        val display = target.getInt("phoneDisplayId") // Supplied by actual Phone source/fixture observation; never default0.
        val x = target.getDouble("frameX"); val y = target.getDouble("frameY")
        check(x.isFinite() && y.isFinite() && x >= 0 && x < geometry.width && y >= 0 && y < geometry.height)
        val root = field(activity, "root") as View
        check(root.width > 0 && root.height > 0)
        val fit = Fit.inside(root.width, root.height, geometry)
        record("target_mapping", mapOf("phoneDisplayId" to display, "displayIdentityOrigin" to "host_forwarded_actual_phone_observation",
            "geometryWidth" to geometry.width, "geometryHeight" to geometry.height,
            "sourceWitness" to target.getString("sourceWitness"), "frameX" to x, "frameY" to y))
        return (fit.left + x / geometry.width * fit.width).toFloat() to (fit.top + y / geometry.height * fit.height).toFloat()
    }

    private fun snapshot(activity: ReceiverActivity): Map<String, Any?> {
        val session = field(activity, "session"); val root = field(activity, "root") as? View
        val pointer = field(activity, "pointer") as? PointerOverlay
        val point = pointer?.inputPosition(); val geometry = field(activity, "currentGeometry") as? Geometry
        val overlay = field(activity, "anchorOverlay") as? View
        val held = field(activity, "anchorHeldLabel") as? View
        val aimAt = aimTarget
        return mapOf("receiverIdentity" to System.identityHashCode(activity), "taskId" to activity.taskId,
            "glassesDisplayId" to activity.display?.displayId, "focused" to activity.hasWindowFocus(),
            "resumed" to (field(activity, "resumed") == true), "streaming" to (field(activity, "streaming") == true),
            "connectionRequested" to (field(activity, "connectRequested") == true || field(activity, "connectBeforeBinding") == true),
            "sessionIdentity" to session?.let(System::identityHashCode), "sessionStartedMonoNs" to session?.let { field(it, "started") },
            "ownerIdentity" to field(activity, "owner")?.let(System::identityHashCode),
            "inputGeneration" to field(activity, "inputGeneration"), "sourceUsability" to primitiveIdentity(session?.let { field(it, "sourceUsability") }),
            "geometryWidth" to geometry?.width, "geometryHeight" to geometry?.height,
            "rootWidth" to root?.width, "rootHeight" to root?.height,
            "pointerX" to point?.x, "pointerY" to point?.y, "pointerAvailable" to (point?.available == true),
            "aimAtTarget" to (point?.available == true && aimAt != null && kotlin.math.abs(point.x - aimAt.first) <= 2 && kotlin.math.abs(point.y - aimAt.second) <= 2),
            "chooserShown" to (overlay?.isShown == true), "heldIndicatorShown" to (held?.isShown == true),
            "matchedAnchorOverlayPresent" to (overlay != null),
            "heldIndicatorScope" to "Glasses feedback UI only, not authoritative Phone HELD/retirement evidence")
    }

    private fun primitiveIdentity(value: Any?, depth: Int = 0): Any? = when {
        value == null || value is String || value is Number || value is Boolean -> value
        value is Enum<*> -> value.name
        depth >= 3 -> null
        else -> listOf("ownerEpoch", "incarnation", "inputRevision", "mode", "revision", "sequence", "id", "value")
            .mapNotNull { name -> field(value, name)?.let { name to primitiveIdentity(it, depth + 1) } }.toMap()
    }

    private fun record(event: String, fields: Map<String, Any?> = emptyMap()) {
        queue?.capture("adb_control", receiver.get()?.let(System::identityHashCode), SystemClock.uptimeMillis(),
            SystemClock.elapsedRealtimeNanos()) { fields + mapOf("runId" to id, "driverEvent" to event,
                "pid" to android.os.Process.myPid(), "uid" to android.os.Process.myUid(), "physicalPass" to false) }
    }

    private fun restoreAim(activity: ReceiverActivity?) {
        val pointer = aimedPointer ?: return
        pointer.stop()
        if (restoreRunning && activity != null && field(activity, "pointer") === pointer &&
            field(activity, "resumed") == true && activity.hasWindowFocus()) pointer.start()
        aimedPointer = null; aim = null; aimTarget = null; aimedBounds = null; aimedRotation = null
    }

    override fun close() {
        if (closed) return
        closed = true; phases.close()
        if (lastSnapshot["streaming"] != true) record("setup_unavailable", mapOf(
            "scope" to "remote_session", "reason" to "no_streaming_original_session_observed",
            "sourceWitness" to "Glasses_same_process_snapshot"))
        onMain { restoreAim(receiver.get()) }
        record("cleanup", mapOf("planFileRetained" to planFile.name))
    }

    fun summary() = JSONObject().put("schema", "eyeprojector.anchor-adb-control.v1").put("runId", id)
        .put("planFile", planFile.name).put("ownPhasesCompleted", phases.completed).put("ownPhasesInconclusive", phases.inconclusive)
        .put("activityLaunched", activityLaunched).put("activityFinished", activityFinished).put("cleanupCalled", closed)
        .put("syntheticInputInjected", syntheticInputInjected)
        .put("physicalPass", false).put("targetAppAcknowledgment", false)
        .put("lastOwnSnapshot", JSONObject(lastSnapshot)).put("forwardedUnavailableScopes", org.json.JSONArray(unavailable.values))
        .put("phoneAuthorityScope", "Requires separate source-bound Phone trace; no Phone HELD/accessibility readiness inferred from Glasses feedback")
        .put("scope", "Synthetic model pose, own-View/MotionEvent/direct callback and injected broadcast paths are individually labeled; Phone fixture effects and original Phone gate trace are separate evidence")

    private fun onMain(operation: () -> Unit) = nativeInputObservationOnMain({ task -> instrumentation.runOnMainSync { task() } }, operation)

    companion object {
        private val FUNCTION_REPORT_ACTIONS = listOf("com.android.action.ACTION_SPRITE_BUTTON_DOWN",
            "com.android.action.ACTION_SPRITE_BUTTON_UP", "com.android.action.ACTION_SPRITE_BUTTON_CLICK",
            "com.android.action.ACTION_SPRITE_BUTTON_LONG_PRESS")
        private val BROADCASTS = listOf("com.android.action.ACTION_SPRITE_BUTTON_CLICK", "com.android.action.ACTION_SPRITE_BUTTON_LONG_PRESS",
            "com.android.action.ACTION_SPRITE_BUTTON_DOWN", "com.android.action.ACTION_SPRITE_BUTTON_UP",
            "com.android.action.ACTION_TWO_FINGER_SWIPE_BACK", "com.android.action.ACTION_TWO_FINGER_SWIPE_FORWARD")
        private fun field(owner: Any, name: String): Any? {
            var type: Class<*>? = owner.javaClass
            while (type != null) {
                try { val field = type.getDeclaredField(name); field.isAccessible = true; return field.get(owner) }
                catch (_: NoSuchFieldException) { type = type.superclass }
            }
            return null
        }
    }
}
