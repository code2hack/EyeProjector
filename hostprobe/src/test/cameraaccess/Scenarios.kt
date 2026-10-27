package com.code2hack.eyeprojector.hostprobe

private data class Observation(
    val event: String,
    val sessionClosedObserved: Boolean,
    val sessionRetirementBasis: String?,
    val parentDeviceClosedObserved: Boolean,
)

private class Rig {
    val device = Any()
    val session = Any()
    val events = mutableListOf<String>()
    val observations = mutableListOf<Observation>()
    val deviceCalls = mutableListOf<Any>()
    val sessionCalls = mutableListOf<Any>()
    var releases = 0
    var completions = 0
    var deviceFailure = false
    var releaseFailure = false
    var reentrant = false
    var afterCompletion: () -> Unit = {}
    val owner: CameraCloseOwner<Any, Any> = CameraCloseOwner(
        closeDevice = {
            deviceCalls.add(it)
            if (deviceFailure) throw IllegalStateException("device close uncertain")
            if (reentrant) ownerDeviceClosed(it)
        },
        closeSession = { sessionCalls.add(it); if (reentrant) ownerSessionClosed(it) },
        releaseOutputs = { if (releaseFailure) throw IllegalStateException("output close uncertain"); releases++ },
        event = { name, _ -> events.add(name); observations.add(observation(name)) },
        completed = { completions++; afterCompletion() },
    )
    private fun ownerDeviceClosed(value: Any): Unit = owner.deviceClosed(value)
    private fun ownerSessionClosed(value: Any): Unit = owner.sessionClosed(value)
    private fun observation(name: String) = Observation(name, owner.sessionClosedObserved,
        owner.sessionRetirementBasis, owner.parentDeviceClosedObserved)
    fun opened() { owner.expectOpen(); check(owner.deviceArrived(device)) }
    fun configured() { opened(); owner.expectConfiguration(); check(owner.sessionArrived(session)) }
}

fun main() {
    var passed = 0
    fun scenario(name: String, action: () -> Unit) {
        action(); passed++; println("PASS $name")
    }
    scenario("cancel pending open retains ownership through late onOpened and original onClosed") {
        val r = Rig(); r.owner.expectOpen(); r.owner.requestClose()
        check(r.owner.openPending && !r.owner.complete && r.releases == 0)
        r.owner.deviceArrived(r.device)
        check(r.deviceCalls == listOf(r.device) && !r.owner.complete)
        r.owner.deviceClosed(r.device)
        check(r.owner.complete && r.releases == 1 && r.completions == 1)
    }
    scenario("pending configuration survives parent closure until original late session arrives") {
        val r = Rig(); r.opened(); r.owner.expectConfiguration(); r.owner.requestClose()
        r.owner.deviceClosed(r.device)
        check(!r.owner.complete && r.releases == 0)
        check(r.owner.configurationPending && r.owner.parentDeviceClosedObserved)
        check("session_retired_by_parent_device_closed" !in r.events)
        r.owner.sessionArrived(r.session)
        check(r.owner.complete && r.releases == 1)
        check(r.sessionCalls.isEmpty() && !r.owner.sessionClosedObserved)
        check(r.owner.sessionRetirementBasis == "PARENT_DEVICE_CLOSED")
    }
    scenario("original parent callback retires configured session without inventing its callback") {
        for (sessionFirst in listOf(false, true)) {
            val r = Rig(); r.configured(); r.owner.requestClose()
            if (sessionFirst) {
                r.owner.sessionClosed(r.session)
                check(!r.owner.complete && r.releases == 0)
            }
            r.owner.deviceClosed(r.device)
            check(r.owner.complete && r.releases == 1)
            check(r.owner.parentDeviceClosedObserved && r.owner.sessionClosedObserved == sessionFirst)
            check(r.owner.sessionRetirementBasis == if (sessionFirst) "SESSION_CLOSED" else "PARENT_DEVICE_CLOSED")
            check(("session_closed" in r.events) == sessionFirst)
            val completion = r.observations.single { it.event == "consumer_close_completed" }
            check(completion.parentDeviceClosedObserved && completion.sessionClosedObserved == sessionFirst)
            if (!sessionFirst) {
                val camera = r.events.indexOf("camera_closed")
                val retired = r.events.indexOf("session_retired_by_parent_device_closed")
                check(camera >= 0 && retired > camera && r.events.indexOf("consumer_close_completed") > retired)
                check(completion.sessionRetirementBasis == "PARENT_DEVICE_CLOSED")
            }
        }
    }
    scenario("configure failure is an actual terminal path without invented session_closed") {
        val r = Rig(); r.opened(); r.owner.expectConfiguration(); r.owner.configurationFailed(r.session)
        check(r.sessionCalls.isEmpty() && !r.owner.complete)
        check("session_configure_failed" in r.events && "session_closed" !in r.events)
        r.owner.deviceClosed(r.device)
        check(r.owner.complete && r.completions == 1)
        check(!r.owner.sessionClosedObserved && r.owner.sessionRetirementBasis == "CONFIGURATION_FAILED")
        check("session_retired_by_parent_device_closed" !in r.events)
    }
    scenario("wrong resource callback cannot release original resource or complete successor") {
        val a = Rig(); a.configured(); a.owner.requestClose()
        a.owner.deviceClosed(Any()); a.owner.sessionClosed(Any())
        check(!a.owner.complete && a.releases == 0)
        check(!a.owner.parentDeviceClosedObserved && !a.owner.sessionClosedObserved)
        val b = Rig()
        a.afterCompletion = { b.opened() }
        a.owner.sessionClosed(a.session); a.owner.deviceClosed(a.device)
        check(b.owner.device === b.device && !b.owner.closing)
        a.owner.deviceClosed(a.device); a.owner.configurationFailed(a.session)
        check(a.releases == 1 && b.deviceCalls.isEmpty() && !b.owner.closing)
    }
    scenario("reentrant close callbacks neither release twice nor advance prematurely") {
        val r = Rig(); r.configured(); r.reentrant = true; r.owner.requestClose()
        check(r.owner.complete && r.deviceCalls.size == 1 && r.sessionCalls.size == 1)
        check(r.releases == 1 && r.completions == 1)
    }
    scenario("close exception remains unresolved until actual original callback") {
        val r = Rig(); r.opened(); r.deviceFailure = true; r.owner.requestClose()
        check(!r.owner.complete && r.releases == 0 && "camera_close_unconfirmed" in r.events)
        r.owner.requestClose()
        check(r.deviceCalls.size == 1 && !r.owner.complete)
        r.owner.deviceClosed(r.device)
        check(r.owner.complete && r.completions == 1)
    }
    scenario("session and device close returns are not the actual parent closure fence") {
        val r = Rig(); r.configured(); r.owner.requestClose()
        check(r.deviceCalls == listOf(r.device) && r.sessionCalls == listOf(r.session))
        check(!r.owner.parentDeviceClosedObserved && !r.owner.sessionClosedObserved)
        check(!r.owner.complete && r.releases == 0)
        check("session_retired_by_parent_device_closed" !in r.events)
        r.owner.requestClose() // A disconnected/error request alone supplies no new observation.
        check(!r.owner.complete && r.deviceCalls.size == 1 && r.sessionCalls.size == 1)
    }
    scenario("late real session callback is observed separately after parent retirement exactly once") {
        val r = Rig(); r.configured(); r.owner.requestClose(); r.owner.deviceClosed(r.device)
        val completion = r.observations.single { it.event == "consumer_close_completed" }
        check(!completion.sessionClosedObserved && completion.sessionRetirementBasis == "PARENT_DEVICE_CLOSED")
        r.owner.sessionClosed(Any())
        check(!r.owner.sessionClosedObserved && "session_closed" !in r.events)
        r.owner.sessionClosed(r.session)
        check(r.owner.sessionClosedObserved && r.owner.sessionRetirementBasis == "PARENT_DEVICE_CLOSED")
        check(r.events.count { it == "session_closed" } == 1)
        val late = r.observations.single { it.event == "session_closed" }
        check(late.sessionClosedObserved && late.parentDeviceClosedObserved)
        check(!completion.sessionClosedObserved) // The prior completion snapshot is not rewritten.
        r.owner.sessionClosed(r.session); r.owner.deviceClosed(r.device); r.owner.requestClose()
        check(r.releases == 1 && r.completions == 1 && r.events.count { it == "session_closed" } == 1)
    }
    scenario("parent retirement does not erase an independently pending configuration leg") {
        val r = Rig(); r.configured(); r.owner.expectConfiguration(); r.owner.requestClose()
        r.owner.deviceClosed(r.device)
        check(r.owner.configurationPending && !r.owner.complete && r.releases == 0)
        check(!r.owner.sessionClosedObserved && r.owner.sessionRetirementBasis == "PARENT_DEVICE_CLOSED")
        r.owner.sessionArrived(Any())
        check(r.owner.configurationPending && !r.owner.complete)
        r.owner.sessionArrived(r.session)
        check(r.owner.complete && !r.owner.configurationPending && r.releases == 1)
    }
    scenario("output fault after actual parent retirement remains pending without duplicate closes") {
        val r = Rig(); r.configured(); r.owner.requestClose(); r.releaseFailure = true
        r.owner.deviceClosed(r.device)
        check(!r.owner.complete && r.completions == 0 && "outputs_release_unconfirmed" in r.events)
        check(!r.owner.sessionClosedObserved && r.owner.sessionRetirementBasis == "PARENT_DEVICE_CLOSED")
        r.owner.configurationFailed(r.session) // A late actual path cannot rewrite the retirement basis.
        check(!r.owner.complete && r.owner.sessionRetirementBasis == "PARENT_DEVICE_CLOSED")
        r.releaseFailure = false; r.owner.requestClose()
        check(r.owner.complete && r.releases == 1 && r.completions == 1)
        check(r.deviceCalls.size == 1 && r.sessionCalls.size == 1)
        check(r.events.count { it == "session_retired_by_parent_device_closed" } == 1)
    }
    scenario("invoked open exception is not a synthesized callback or early release") {
        val r = Rig(); r.owner.expectOpen(); r.owner.requestClose()
        check(!r.owner.complete && r.owner.openPending)
        r.owner.deviceArrived(r.device); check(!r.owner.complete)
        r.owner.deviceClosed(r.device); check(r.owner.complete)
    }
    scenario("invoked configuration exception retains pending configuration until actual terminal") {
        val r = Rig(); r.opened(); r.owner.expectConfiguration(); r.owner.requestClose()
        r.owner.deviceClosed(r.device)
        check(!r.owner.complete && r.owner.configurationPending)
        r.owner.configurationFailed(r.session)
        check(r.owner.complete && "session_closed" !in r.events)
    }
    scenario("setup failure before framework call releases local outputs without fake camera closure") {
        val r = Rig(); r.owner.requestClose()
        check(r.owner.complete && r.releases == 1 && r.deviceCalls.isEmpty())
        check("camera_closed" !in r.events && "session_closed" !in r.events)
    }
    scenario("output release failure cannot report completed cleanup") {
        val r = Rig(); r.releaseFailure = true; r.owner.requestClose()
        check(!r.owner.complete && r.completions == 0)
        r.releaseFailure = false; r.owner.requestClose()
        check(r.owner.complete && r.releases == 1 && r.completions == 1)
    }
    scenario("replacement and finish completion hooks cannot run before original closure") {
        val r = Rig(); r.owner.expectOpen()
        var finish = false; var successor = false
        r.afterCompletion = { if (finish) r.events.add("finish") else successor = true }
        r.owner.requestClose(); finish = true
        check(!successor && "finish" !in r.events)
        r.owner.deviceArrived(r.device); check(!successor)
        r.owner.deviceClosed(r.device)
        check("finish" in r.events && !successor)
    }
    scenario("session onClosed can itself resolve an original pending configuration") {
        val r = Rig(); r.opened(); r.owner.expectConfiguration(); r.owner.requestClose()
        r.owner.sessionClosed(r.session)
        check(!r.owner.complete)
        r.owner.deviceClosed(r.device)
        check(r.owner.complete)
    }
    scenario("mode contract retains combined default and independent preview-only choice") {
        check(CameraOutputMode.PREVIEW_JPEG.wire == "preview-jpeg" && CameraOutputMode.PREVIEW_JPEG.jpeg)
        check(CameraOutputMode.PREVIEW_ONLY.wire == "preview-only" && !CameraOutputMode.PREVIEW_ONLY.jpeg)
    }
    println("RESULT $passed scenarios passed; real owner declaration, fake delayed callbacks, no Android execution")
}
