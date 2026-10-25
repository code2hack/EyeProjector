package com.code2hack.eyeprojector.hostprobe

private class Rig {
    val device = Any()
    val session = Any()
    val events = mutableListOf<String>()
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
        event = { name, _ -> events.add(name) },
        completed = { completions++; afterCompletion() },
    )
    private fun ownerDeviceClosed(value: Any): Unit = owner.deviceClosed(value)
    private fun ownerSessionClosed(value: Any): Unit = owner.sessionClosed(value)
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
    scenario("pending configuration waits even when camera closes before late configured session") {
        val r = Rig(); r.opened(); r.owner.expectConfiguration(); r.owner.requestClose()
        r.owner.deviceClosed(r.device)
        check(!r.owner.complete && r.releases == 0)
        r.owner.sessionArrived(r.session)
        check(r.sessionCalls == listOf(r.session) && !r.owner.complete)
        r.owner.sessionClosed(r.session)
        check(r.owner.complete && r.releases == 1)
    }
    scenario("normal configured resources resolve only after both original callbacks in either order") {
        for (sessionFirst in listOf(false, true)) {
            val r = Rig(); r.configured(); r.owner.requestClose()
            if (sessionFirst) r.owner.sessionClosed(r.session) else r.owner.deviceClosed(r.device)
            check(!r.owner.complete && r.releases == 0)
            if (sessionFirst) r.owner.deviceClosed(r.device) else r.owner.sessionClosed(r.session)
            check(r.owner.complete && r.releases == 1)
        }
    }
    scenario("configure failure is an actual terminal path without invented session_closed") {
        val r = Rig(); r.opened(); r.owner.expectConfiguration(); r.owner.configurationFailed(r.session)
        check(r.sessionCalls.isEmpty() && !r.owner.complete)
        check("session_configure_failed" in r.events && "session_closed" !in r.events)
        r.owner.deviceClosed(r.device)
        check(r.owner.complete && r.completions == 1)
    }
    scenario("wrong resource callback cannot release original resource or complete successor") {
        val a = Rig(); a.configured(); a.owner.requestClose()
        a.owner.deviceClosed(Any()); a.owner.sessionClosed(Any())
        check(!a.owner.complete && a.releases == 0)
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
