#!/usr/bin/env python3
"""Bounded cached actual-source window-observation checks; no SDK, Gradle or devices.

The existing retained-session test fixture is copied into the private output and
extended there. Only the appended JUnit methods run. Android, EGL, codec and TLS
remain the repository's explicit doubles; these checks make no optical claim.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import signal
import subprocess
import time


METHODS = [
    "presentation236SuccessfulRepeatedCallbacksAndFailedSwap",
    "presentation236StaleGenerationStillObservedWithoutAcceptance",
    "presentation236StaleOutputAndSessionKeepOriginalIdentity",
    "presentation236ActualLocalPanelAndNoneModes",
    "presentation236DelayedDeliveryAndDetach",
    "presentation236ReentrySnapshotsRemainOwned",
    "presentation236ObserverThrowPreservesProductAndPrimaryFailure",
    "presentation236RetainedDrawHasPacketAndDecoderPlateaus",
    "presentation236DisabledAndEnabledKeepProductRevisions",
    "presentation236LateInstallKeepsUnknownOriginalAndTruthfulCurrentSession",
]

EXTRA_TESTS = r'''
    // Appended only to the private copy: actual Fixture and its TemporaryFolder rule.
    private fun observation236Field(): java.lang.reflect.Field? =
        ReceiverSessionOwner::class.java.declaredFields.firstOrNull { it.name == "windowPresentationObserver" }
            ?.apply { isAccessible = true }
    private fun observation236Install(observer: WindowPresentationObserver?): Boolean {
        val field = observation236Field() ?: return false
        field.set(null, observer)
        return true
    }
    private fun observation236Generation(f: Fixture): Long =
        (f.owner.javaClass.getDeclaredField("windowGeneration").apply { isAccessible = true }.get(f.owner)
            as java.util.concurrent.atomic.AtomicLong).get()
    private fun observation236Started(session: ProjectionSession): Long =
        session.javaClass.getDeclaredField("started").apply { isAccessible = true }.getLong(session)
    private fun observation236Counter(session: ProjectionSession, name: String): Long = synchronized(session) {
        session.javaClass.getDeclaredField(name).apply { isAccessible = true }.getLong(session)
    }
    private fun observation236RedrawBarrier(f: Fixture, target: Surface = f.first) {
        f.output.redraw()
        // Existing synchronous attach waits for preceding GL work and makes another actual draw.
        f.output.attach(target, observation236Generation(f))
    }
    private fun observation236Events() = java.util.concurrent.CopyOnWriteArrayList<SuccessfulWindowReceipt>()
    private fun observation236Revisions(f: Fixture): List<Long> = listOf(
        f.owner.inputRevision,
        f.owner.javaClass.getDeclaredField("reportRevision").apply { isAccessible = true }.getLong(f.owner),
        (f.owner.javaClass.getDeclaredField("usabilityRevision").apply { isAccessible = true }.get(f.owner)
            as java.util.concurrent.atomic.AtomicLong).get(),
    )

    @Test fun presentation236SuccessfulRepeatedCallbacksAndFailedSwap() {
        val events = observation236Events()
        observation236Install(WindowPresentationObserver { events.add(it) })
        try {
            Fixture(withFeature = true).use { f ->
                f.streaming(); f.pump()
                assertTrue("Actual successful swap callback must be observable", events.isNotEmpty())
                val originalS = observation236Started(f.owner.session!!)
                assertTrue(events.any { it.originalSessionStartedMonoNs == originalS && it.existingAcceptanceBranchEntered })
                f.owner.presentation(f.view, true, false)
                val count = events.size; val swaps = EGL14.swaps.size
                observation236RedrawBarrier(f); f.pump()
                val repeated = events.drop(count)
                assertEquals("Each successful actual draw has one owner receipt", EGL14.swaps.size - swaps, repeated.size)
                assertTrue(repeated.size >= 2)
                assertTrue(repeated.all { !it.existingAcceptanceBranchEntered && it.callbackCompleted })
                assertTrue(repeated.all { it.originalSessionStartedMonoNs == originalS && it.callbackMonoNs >= originalS })
                assertTrue(repeated.all { it.after?.currentDisplayed == true && it.after?.outputMatchesOriginal == true })
                assertTrue(repeated.all { it.after?.actualReportedMode == "PROJECTED" && it.after?.actualUsabilityMode == "PROJECTED" })
                assertTrue(repeated.all { it.after?.reportMatchesOriginalOwner == true && it.after?.reportMatchesCurrentDemand == true })
                assertTrue(repeated.all { it.callbackMonoNs <= it.callbackReadEndMonoNs &&
                    it.before!!.readStartMonoNs >= it.callbackReadEndMonoNs &&
                    it.before!!.readStartMonoNs <= it.before!!.readEndMonoNs &&
                    it.after!!.readStartMonoNs <= it.after!!.readEndMonoNs })
                val priorFailures = events.size; val retained = f.owner.session
                EGL14.failSwap = true; EGL14.error = EGL14.EGL_BAD_SURFACE
                f.output.redraw(); f.output.invalidate()
                EGL14.failSwap = false; f.pump()
                assertEquals("Failed eglSwapBuffers must not emit successful receipt", priorFailures, events.size)
                assertSame(retained, f.owner.session)
                assertEquals("DISPLAY_DETACHED", f.owner.snapshot().state)
                println("EVIDENCE successfulRepeated callbacks=${repeated.size} swaps=${EGL14.swaps.size - swaps} failedSwapSuccessfulReceipts=0")
            }
        } finally { observation236Install(null) }
    }

    @Test fun presentation236StaleGenerationStillObservedWithoutAcceptance() {
        val events = observation236Events()
        observation236Install(WindowPresentationObserver { events.add(it) })
        try {
            Fixture().use { f ->
                f.streaming(); f.pump(); events.clear()
                val originalS = observation236Started(f.owner.session!!)
                val originalGeneration = observation236Generation(f)
                f.output.redraw()
                f.owner.attachSurface(f.view, null) // Waits for GL, leaving successful callback queued.
                f.pump()
                val stale = events.filter { it.callbackGeneration == originalGeneration }
                assertTrue(stale.isNotEmpty())
                assertTrue(stale.all { it.originalSessionStartedMonoNs == originalS && !it.existingAcceptanceBranchEntered })
                assertTrue(stale.all { it.after?.windowMatchesCallback == false && it.after?.currentSessionMatchesOriginal == true })
                assertTrue(stale.all { it.after!!.windowGenerationBefore != it.callbackGeneration })
                assertEquals("DISPLAY_DETACHED", f.owner.snapshot().state)
                assertFalse(f.owner.session!!.navigate(Navigation.HOME))
            }
        } finally { observation236Install(null) }
    }

    @Test fun presentation236StaleOutputAndSessionKeepOriginalIdentity() {
        val events = observation236Events()
        observation236Install(WindowPresentationObserver { events.add(it) })
        try {
            Fixture().use { f ->
                f.streaming(); f.pump(); events.clear()
                val original = f.owner.session!!; val originalS = observation236Started(original)
                f.output.redraw()
                f.owner.disconnect(notifyPeer = false) // Renderer close waits; prior callback remains queued.
                f.observation(SessionState("replacement-phone-owner", SessionPhase.AVAILABLE))
                val replacement = f.owner.session!!
                assertNotSame(original, replacement)
                f.pump()
                val stale = events.filter { it.originalSessionStartedMonoNs == originalS }
                assertTrue(stale.isNotEmpty())
                assertTrue(stale.all { !it.existingAcceptanceBranchEntered })
                assertTrue(stale.all { it.after?.currentSessionMatchesOriginal == false && it.after?.outputMatchesOriginal == false })
                assertTrue(stale.all { it.after?.currentSessionStartedMonoNs == observation236Started(replacement) })
                assertSame(replacement, f.owner.session)
            }
        } finally { observation236Install(null) }
    }

    @Test fun presentation236ActualLocalPanelAndNoneModes() {
        val events = observation236Events()
        observation236Install(WindowPresentationObserver { events.add(it) })
        try {
            Fixture(withFeature = true).use { f ->
                f.streaming(); f.pump()
                f.owner.presentation(f.view, true, true); events.clear()
                observation236RedrawBarrier(f); f.pump()
                assertTrue(events.isNotEmpty())
                assertTrue(events.all { it.after?.actualReportedMode.toString() == "LOCAL_PANEL" && it.after?.actualUsabilityMode.toString() == "LOCAL_PANEL" })
                assertTrue(events.all { it.after?.currentDisplayed == true && it.after?.localPanel == true })
                f.owner.presentation(f.view, false, true); events.clear()
                observation236RedrawBarrier(f); f.pump()
                assertTrue(events.isNotEmpty())
                assertTrue(events.all { it.after?.actualReportedMode.toString() == "NONE" && it.after?.actualUsabilityMode.toString() == "NONE" })
                assertTrue(events.all { it.after?.currentDisplayed == true && it.after?.uiUsable == false })
            }
        } finally { observation236Install(null) }
    }

    @Test fun presentation236DelayedDeliveryAndDetach() {
        val events = observation236Events()
        observation236Install(WindowPresentationObserver { events.add(it) })
        try {
            Fixture().use { f ->
                f.streaming(); f.pump(); events.clear()
                observation236RedrawBarrier(f)
                assertTrue("Owner has not delivered queued GL callbacks", events.isEmpty())
                val beforeDelivery = System.nanoTime(); f.pump()
                assertTrue(events.isNotEmpty())
                assertTrue(events.all { it.callbackMonoNs <= beforeDelivery && it.callbackCompleted })
                val count = events.size
                observation236RedrawBarrier(f)
                observation236Install(null); f.pump()
                assertEquals("Detached observer must not receive queued evidence", count, events.size)
                assertEquals("STREAMING", f.owner.snapshot().state)
                observation236RedrawBarrier(f) // Disabled at actual callback receipt.
                observation236Install(WindowPresentationObserver { events.add(it) }); f.pump()
                assertEquals("Installing later must not relabel an earlier disabled callback", count, events.size)
            }
        } finally { observation236Install(null) }
    }

    @Test fun presentation236ReentrySnapshotsRemainOwned() {
        val events = observation236Events()
        observation236Install(WindowPresentationObserver { events.add(it) })
        try {
            Fixture(withFeature = true).use { f ->
                f.streaming(); f.pump(); events.clear()
                var reentered = false
                val replacementView = object : ReceiverSessionOwner.View {
                    override fun state(snapshot: ReceiverSessionOwner.Snapshot) {
                        if (snapshot.state == "STREAMING" && !reentered) {
                            reentered = true
                            f.owner.presentation(this, true, true)
                        }
                    }
                    override fun input(source: ProjectionSession, event: ReceiverInput) = Unit
                }
                f.owner.attach(replacementView, Surface())
                f.owner.presentation(replacementView, true, false)
                f.pump()
                assertTrue(reentered)
                val receipt = events.first { it.existingAcceptanceBranchEntered }
                assertEquals("LOCAL_PANEL", receipt.after?.actualReportedMode.toString())
                assertTrue(receipt.after?.localPanel == true)
                f.owner.presentation(replacementView, false, true)
                assertEquals("LOCAL_PANEL", receipt.after?.actualReportedMode.toString())
                assertTrue(receipt.after?.uiUsable == true)
                println("EVIDENCE reentry postDeliveryMode=LOCAL_PANEL atomicAcceptanceTuple=false")
            }
        } finally { observation236Install(null) }
    }

    @Test fun presentation236ObserverThrowPreservesProductAndPrimaryFailure() {
        val events = observation236Events()
        val observerFailure = AssertionError("owned observation failure")
        observation236Install(WindowPresentationObserver { events.add(it); throw observerFailure })
        try {
            Fixture().use { f ->
                f.streaming(); f.pump()
                assertTrue(events.isNotEmpty())
                assertEquals("STREAMING", f.owner.snapshot().state)
                assertEquals(listOf(true), f.resources)
                val primary = IllegalStateException("owned original view failure")
                var armed = false
                val throwingView = object : ReceiverSessionOwner.View {
                    override fun state(snapshot: ReceiverSessionOwner.Snapshot) {
                        if (armed && snapshot.state == "STREAMING") throw primary
                    }
                    override fun input(source: ProjectionSession, event: ReceiverInput) = Unit
                }
                f.owner.attach(throwingView, Surface()); armed = true
                var escaped: Throwable? = null
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
                while (escaped == null && System.nanoTime() < deadline) {
                    try { f.queue.poll(20, TimeUnit.MILLISECONDS)?.invoke() }
                    catch (error: Throwable) { escaped = error }
                }
                assertSame("Original failure must remain primary", primary, escaped)
                assertTrue(events.any { it.existingAcceptanceBranchEntered && !it.callbackCompleted })
                assertEquals(listOf(true), f.resources)
            }
        } finally { observation236Install(null) }
    }

    @Test fun presentation236RetainedDrawHasPacketAndDecoderPlateaus() {
        val events = observation236Events()
        observation236Install(WindowPresentationObserver { events.add(it) })
        try {
            Fixture().use { f ->
                f.streaming(); f.pump(); events.clear()
                val session = f.owner.session!!; val originalS = observation236Started(session)
                val packets = observation236Counter(session, "packets")
                val rendered = observation236Counter(session, "rendered")
                val updates = f.output.surface.texture!!.updates.get()
                f.owner.detach(f.view)
                f.owner.attach(f.view, Surface())
                f.pumpUntil("actual retained texture presents without decoder or packet progress") {
                    f.owner.snapshot().state == "STREAMING"
                }
                f.pump()
                assertEquals(packets, observation236Counter(session, "packets"))
                assertEquals(rendered, observation236Counter(session, "rendered"))
                assertEquals(updates, f.output.surface.texture!!.updates.get())
                assertTrue(events.any { it.existingAcceptanceBranchEntered && it.originalSessionStartedMonoNs == originalS })
                assertSame(session, f.owner.session)
                println("EVIDENCE retainedDraw packetPlateau=true decoderCallbackPlateau=true textureUpdatePlateau=true individualCallbackDrawOrigin=UNKNOWN")
            }
        } finally { observation236Install(null) }
    }

    @Test fun presentation236DisabledAndEnabledKeepProductRevisions() {
        fun sample(enabled: Boolean): List<Long> {
            var observed = 0
            observation236Install(if (enabled) WindowPresentationObserver { observed++ } else null)
            return try {
                Fixture(withFeature = true).use { f ->
                    f.streaming(); f.pump()
                    f.owner.presentation(f.view, true, false)
                    observation236RedrawBarrier(f); f.pump()
                    assertEquals("STREAMING", f.owner.snapshot().state)
                    if (enabled) assertTrue(observed > 0) else assertEquals(0, observed)
                    observation236Revisions(f)
                }
            } finally { observation236Install(null) }
        }
        assertEquals("Observation cannot allocate product input/report/usability revisions", sample(false), sample(true))
        println("EVIDENCE disabled productRevisionParity=true noMetadataAllocationProof=SOURCE_REVIEW_REQUIRED")
    }

    @Test fun presentation236LateInstallKeepsUnknownOriginalAndTruthfulCurrentSession() {
        observation236Install(null)
        val events = observation236Events()
        try {
            Fixture(withFeature = true).use { f ->
                f.streaming(); f.pump() // This output was constructed with observation disabled.
                val original = f.owner.session!!
                val currentS = observation236Started(original)
                val inputRevision = f.owner.inputRevision
                observation236Install(WindowPresentationObserver { events.add(it) })
                f.owner.detach(f.view)
                f.owner.attach(f.view, Surface())
                f.owner.presentation(f.view, true, false)
                f.pumpUntil("late observation does not gate existing window acceptance") {
                    f.owner.snapshot().state == "STREAMING"
                }
                f.pump()
                assertTrue(events.isNotEmpty())
                assertTrue(events.any { it.existingAcceptanceBranchEntered && it.callbackCompleted })
                assertTrue(events.all { it.originalSessionStartedMonoNs == null })
                assertTrue(events.all { it.before?.currentSessionMatchesOriginal == null && it.after?.currentSessionMatchesOriginal == null })
                assertTrue(events.all { it.before?.currentSessionStartedMonoNs == currentS && it.after?.currentSessionStartedMonoNs == currentS })
                assertTrue(events.all { it.after?.outputMatchesOriginal == true && it.after?.windowMatchesCallback == true })
                assertTrue(events.all { it.after?.actualReportedMode == "PROJECTED" && it.after?.actualUsabilityMode == "PROJECTED" })
                assertSame(original, f.owner.session)
                assertEquals(inputRevision, f.owner.inputRevision)
                assertEquals(listOf(true), f.resources)
                assertTrue(f.owner.session!!.navigate(Navigation.HOME))
                println("EVIDENCE lateInstall preexistingOutput=true originalSession=UNKNOWN currentSession=OBSERVED productAcceptance=true")
            }
        } finally { observation236Install(null) }
    }
'''

RUNNER = r'''package com.code2hack.eyeprojector.directrg
import org.junit.runner.JUnitCore
import org.junit.runner.Request
fun main(args: Array<String>) {
    var failed = false
    args.forEach { method ->
        val begin = System.nanoTime()
        val result = JUnitCore().run(Request.method(RetainedReceiverSessionTest::class.java, method))
        result.failures.forEach { println(it.trace) }
        println("CASE " + method + " run=" + result.runCount + " failed=" + result.failureCount +
            " elapsedMs=" + (System.nanoTime() - begin) / 1_000_000)
        failed = failed || !result.wasSuccessful()
    }
    val owned = Thread.getAllStackTraces().keys.filter { it.isAlive &&
        (it.name == "eyeprojector-video-output" || it.name.startsWith("eyeprojector-decoder") ||
         it.name.startsWith("pool-")) }
    println("OWNED_THREAD_CLOSURE live=" + owned.map { it.name }.sorted())
    failed = failed || owned.isNotEmpty()
    if (failed) kotlin.system.exitProcess(1)
}
'''


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def normalized(text):
    return re.sub(r"\s+", "", text)


def source_contract(parent, current, dto):
    """Exact original guard, side-effect order and allocator counts, not an alternate owner model."""
    original_guard = ("if (!closed && !currentDisplayed && output === nextOutput && window === surface "
                      "&& windowGeneration.get() == generation && mediaState == \"STREAMING\")")
    original_effects = "displayedGeneration = generation; displayed = true; session?.presentation(true); publish()"
    checks = {
        "currentRetainsOriginalAcceptanceGuard": normalized(original_guard) in normalized(current),
        "currentRetainsOriginalEffectOrder": normalized(original_effects) in normalized(current),
        "currentUsesOptionalObserver": "windowPresentationObserver" in current,
        "diagnosticOriginAllocationConditionalOnObserver": normalized("val originalSession = if (windowPresentationObserver == null) null else AtomicReference<ProjectionSession?>()") in normalized(current),
        "diagnosticOriginPublicationThreadSafe": normalized("originalSession?.get()") in normalized(current) and normalized("originalSession?.set(next)") in normalized(current),
        "dtoExportsNoSurfaceOrNativeIdentity": not re.search(r"val\s+\w+\s*:\s*(?:Surface|EGLSurface|EGLContext)\b|identityHashCode|val\s+nativeHandle", dto),
        "dtoDoesNotClaimOpticalOrFrameOrigin": not re.search(r"val\s+(?:opticalPass|freshFrame|cachedFrame)\s*:\s*Boolean", dto),
    }
    if parent is not None:
        checks["parentHasOriginalAcceptanceGuard"] = normalized(original_guard) in normalized(parent)
        checks["parentHasOriginalEffectOrder"] = normalized(original_effects) in normalized(parent)
        for operation in ["usabilityRevision.incrementAndGet()", "windowGeneration.incrementAndGet()", "++reportRevision", "++inputRevision"]:
            checks["unchangedAllocatorCount:" + operation] = normalized(parent).count(normalized(operation)) == normalized(current).count(normalized(operation))
    return checks


def owned_process(command, log_path, timeout, deadline):
    """Own one process group, bound its lifetime and reap it before returning."""
    allowance = min(timeout, deadline - time.monotonic())
    if allowance <= 0:
        return {"exit": None, "timedOut": True, "notStarted": "overall 120 second budget exhausted"}
    begin = time.monotonic()
    with log_path.open("w") as log:
        process = subprocess.Popen(command, stdout=log, stderr=subprocess.STDOUT,
                                   cwd=log_path.parent, start_new_session=True)
        result = {"pid": process.pid, "processGroup": process.pid, "timedOut": False}
        try:
            process.wait(timeout=allowance)
        except subprocess.TimeoutExpired:
            result["timedOut"] = True
            os.killpg(process.pid, signal.SIGTERM)
            try:
                process.wait(timeout=2)
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGKILL)
                process.wait(timeout=2)
        finally:
            # No compiler/worker belonging to this invocation survives its leader.
            try:
                os.killpg(process.pid, signal.SIGKILL)
                result["remainingGroupKilled"] = True
            except ProcessLookupError:
                result["remainingGroupKilled"] = False
        result.update(exit=process.returncode, reaped=True, elapsedSeconds=round(time.monotonic() - begin, 3))
        return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--parent-owner", type=Path, help="Exact preserved beforebody, never a Git mutation")
    parser.add_argument("--parent-session", type=Path, help="Optional exact preserved ProjectionSession beforebody")
    parser.add_argument("--parent-renderer", type=Path, help="Optional exact preserved read-only renderer beforebody")
    parser.add_argument("--source-mode", choices=["current", "parent"], default="current")
    args = parser.parse_args()
    if args.source_mode == "parent" and args.parent_owner is None:
        parser.error("--source-mode parent requires --parent-owner")
    root = Path(__file__).resolve().parents[2]
    output = args.output_dir.resolve()
    output.mkdir(parents=True, exist_ok=False)
    deadline = time.monotonic() + 116  # Four seconds reserved for owned process teardown.
    glass = root / "directrg/src/main/kotlin/com/code2hack/eyeprojector/directrg"
    protocol = root / "protocol/src/main/kotlin/com/code2hack/eyeprojector/protocol"
    tests = root / "directrg/src/test"
    owner = glass / "ReceiverSessionOwner.kt"
    dto = glass / "WindowPresentationObservation.kt"
    original_test = tests / "kotlin/com/code2hack/eyeprojector/directrg/RetainedReceiverSessionTest.kt"
    result = {
        "scope": "Actual owner/session/decoder/retained renderer with repository Android/EGL/codec/TLS doubles; passive observation only; no Android, optical, input or device pass",
        "sourceMode": args.source_mode,
        "drawOriginScope": "UNKNOWN per individual callback; explicit retained-fixture interval packet/decoder/texture plateaus reported separately",
        "clockScope": "System.nanoTime callback receipt after successful swap returned; no GPU or optical timestamp",
        "overallBudgetSeconds": 120,
        "inputs": [], "dependencies": [], "commands": {}, "processes": {},
        "limitations": ["No runtime allocation profiler; disabled clock/allocation behavior also requires source review", "Doubles do not model physical presentation or codec scheduling"],
    }
    try:
        parent_body = args.parent_owner.read_text() if args.parent_owner else None
        result["sourceContract"] = source_contract(parent_body, owner.read_text(), dto.read_text())
        if args.parent_renderer:
            result["sourceContract"]["retainedRendererUnchanged"] = digest(args.parent_renderer) == digest(glass / "RetainedVideoOutput.kt")
        if not all(result["sourceContract"].values()):
            raise ValueError("Original guard/effect/allocator or DTO source contract failed")
        fixture = original_test.read_text()
        closing = fixture.rfind("}")
        if closing < 0 or fixture[closing + 1:].strip():
            raise ValueError("Expected final actual test-class closing brace")
        generated_test = output / "RetainedReceiverSessionTest.kt"
        generated_test.write_text(fixture[:closing] + EXTRA_TESTS + fixture[closing:])
        runner = output / "Presentation236ChecksMain.kt"
        runner.write_text(RUNNER)
        names = ["ProjectionSession", "ReceiverSessionOwner", "WindowPresentationObservation", "ReceiverActivationStore",
                 "PairedSessionClient", "RetainedVideoOutput", "SurfaceDecoder", "SessionAttachment", "StatePublisher",
                 "VideoReadiness", "VideoClock", "HeldTouchTransport", "OrderedControlDispatcher", "LatestHeadPointer",
                 "CompletedReceiverInput", "SessionPresentation", "HeadPointerModel", "HeadOrientation", "HeldPointerDispatcher", "PadGestureRecognizer"]
        sources = [glass / (name + ".kt") for name in names]
        if args.source_mode == "parent":
            sources = [args.parent_owner.resolve() if p == owner else
                       args.parent_session.resolve() if p == glass / "ProjectionSession.kt" and args.parent_session else p
                       for p in sources]
        sources += [protocol / name for name in ["SessionControl.kt", "SessionState.kt", "ActivationControl.kt", "ReceiverUsability.kt", "Wire.kt", "PhoneInput.kt", "Touch.kt", "LayoutScale.kt"]]
        sources += sorted((tests / "kotlin/android").rglob("*.kt"))
        sources += sorted((tests / "kotlin/org/json").glob("*.kt"))
        sources += sorted((tests / "activation/doubles").glob("*.kt"))
        sources += [generated_test, runner]
        snapshots = []
        for number, source in enumerate(sources):
            dest = output / "source" / str(number) / source.name
            dest.parent.mkdir(parents=True)
            dest.write_bytes(source.read_bytes())
            result["inputs"].append({"path": str(source), "snapshot": str(dest), "sha256": digest(dest)})
            snapshots.append(dest)
        controls = [Path(__file__), original_test, owner, dto] + ([args.parent_owner] if args.parent_owner else []) + ([args.parent_session] if args.parent_session else []) + ([args.parent_renderer] if args.parent_renderer else [])
        for number, source in enumerate(controls):
            dest = output / "control-inputs" / str(number) / source.name
            dest.parent.mkdir(parents=True)
            dest.write_bytes(source.read_bytes())
            result["inputs"].append({"path": str(source.resolve()), "snapshot": str(dest), "sha256": digest(dest)})
        cache = Path.home() / ".gradle/caches/modules-2/files-2.1"
        coordinates = [("org.jetbrains.kotlin", "kotlin-compiler-embeddable", "2.2.21"),
                       ("org.jetbrains.kotlin", "kotlin-stdlib", "2.2.21"), ("org.jetbrains.kotlin", "kotlin-script-runtime", "2.2.21"),
                       ("org.jetbrains.kotlin", "kotlin-reflect", "1.6.10"), ("org.jetbrains.intellij.deps", "trove4j", "1.0.20200330"),
                       ("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", "1.8.0"), ("org.jetbrains", "annotations", "13.0"),
                       ("junit", "junit", "4.13.2"), ("org.hamcrest", "hamcrest-core", "1.3")]
        jars = []
        for group, artifact, version in coordinates:
            found = sorted((cache / group / artifact / version).glob("*/" + artifact + "-" + version + ".jar"))
            if len(found) != 1:
                raise ValueError("Missing/ambiguous existing cached dependency " + artifact)
            jars.append(found[0])
        result["dependencies"] = [{"path": str(p), "sha256": digest(p)} for p in jars]
        java = str(Path(os.environ["JAVA_HOME"]) / "bin/java") if os.environ.get("JAVA_HOME") else "/usr/lib/jvm/java-17-openjdk-amd64/bin/java"
        cp = os.pathsep.join(map(str, jars))
        jar = output / "presentation236.jar"
        compile_cmd = [java, "-Xmx768m", "-cp", cp, "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-stdlib", "-no-reflect", "-jvm-target", "17", "-classpath", cp, "-d", str(jar)] + list(map(str, snapshots))
        selected = METHODS if args.source_mode == "current" else METHODS[:1]
        run_cmd = [java, "-Xmx256m", "-cp", str(jar) + os.pathsep + cp, "com.code2hack.eyeprojector.directrg.Presentation236ChecksMainKt"] + selected
        result["commands"] = {"compile": compile_cmd, "run": run_cmd}
        result["selectedMethods"] = selected
        result["processes"]["compile"] = owned_process(compile_cmd, output / "compile.log", 85, deadline)
        if result["processes"]["compile"]["exit"] == 0:
            result["processes"]["run"] = owned_process(run_cmd, output / "run.log", 25, deadline)
        run = result["processes"].get("run", {})
        log = (output / "run.log").read_text() if (output / "run.log").exists() else ""
        result["cases"] = [{"method": match[0], "runCount": int(match[1]), "failureCount": int(match[2]), "elapsedMs": int(match[3])}
                           for match in re.findall(r"^CASE (\S+) run=(\d+) failed=(\d+) elapsedMs=(\d+)$", log, re.M)]
        result["evidence"] = re.findall(r"^EVIDENCE (.+)$", log, re.M)
        result["ownedThreadClosure"] = re.findall(r"^OWNED_THREAD_CLOSURE live=(.*)$", log, re.M)
        if args.source_mode == "parent":
            result["expectedOutcome"] = "RED: actual original Owner lacks the successful-window observation hook"
            result["expectationMet"] = (result["processes"]["compile"]["exit"] == 0 and run.get("exit") == 1
                and not run.get("timedOut") and len(result["cases"]) == 1
                and result["cases"][0]["failureCount"] == 1
                and "Actual successful swap callback must be observable" in log
                and result["ownedThreadClosure"] == ["[]"])
        else:
            result["expectedOutcome"] = "GREEN: current actual-source observation cases"
            result["expectationMet"] = (result["processes"]["compile"]["exit"] == 0 and run.get("exit") == 0
                and not run.get("timedOut") and len(result["cases"]) == len(selected)
                and all(case["runCount"] == 1 and case["failureCount"] == 0 for case in result["cases"])
                and result["ownedThreadClosure"] == ["[]"])
    except Exception as error:
        result["error"] = {"type": type(error).__name__, "message": str(error)}
        result["expectationMet"] = False
    finally:
        (output / "result.json").write_text(json.dumps(result, indent=2) + "\n")
        for name in ["compile.log", "run.log"]:
            if (output / name).exists():
                print((output / name).read_text(), end="")
        print(json.dumps({"result": str(output / "result.json"), "sourceMode": args.source_mode,
                          "expectationMet": result.get("expectationMet", False)}))
    return 0 if result.get("expectationMet") else 1


if __name__ == "__main__":
    raise SystemExit(main())
