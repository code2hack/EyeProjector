package com.code2hack.eyeprojector.directhost.activation.snapshot

import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import rikka.shizuku.Shizuku

/** Executes actual NativeSnapshotChecks + actual SnapshotWord; every platform/SDK/native boundary is explicit. */
object ProbeReadinessChecks {
    private val workers = ArrayList<Thread>()
    private class Run(route: String = "shizuku") {
        val result = Bundle()
        private val task = FutureTask<Throwable?> {
            try { NativeSnapshotChecks.run(Context(), Bundle().apply { putString("snapshot-route", route) }, result); null }
            catch (failure: Throwable) { failure }
        }
        val thread = Thread(task, "instrumentation-worker").apply { isDaemon = true; workers.add(this); start() }
        fun finish(): Throwable? = task.get(3, TimeUnit.SECONDS)
        fun pending() = !task.isDone
    }
    private fun await(latch: CountDownLatch) { check(latch.await(3, TimeUnit.SECONDS)) }
    private fun ready() { Shizuku.rawBinder = true; Shizuku.metadataReady = true; Shizuku.attached = true }
    private fun noAdmission() {
        check(Shizuku.permissionCalls == 0 && Shizuku.bindCalls == 0 && ProbeBoundary.installCalls == 0)
    }
    private fun cleaned(removed: Boolean = true) {
        check(Shizuku.listeners.isEmpty())
        check(Shizuku.removed.size == if (removed) 1 else 0)
        check(ProbeBoundary.clearCalls >= 1)
    }
    private fun success(run: Run, shizuku: Boolean = true) {
        check(run.finish() == null)
        check(run.result["snapshotWords"] == "6,8,14,17")
        check(run.result["snapshotRegion"] == "original-region")
        check(run.result["snapshotOwner"] == "original-probe-owner")
        check(run.result["snapshotWriterPid"] != run.result["snapshotReaderPid"])
        check(ProbeBoundary.installCalls == 1 && ProbeBoundary.retireCalls == 1)
        check(ProbeBoundary.readsAfterClose == 1 && ProbeBoundary.clearCalls == 2)
        check(ProbeBoundary.endpoint!!.closed && ProbeBoundary.endpoint!!.closeCalls == 2)
        if (shizuku) {
            check(run.result["snapshotShizukuCallbackReady"] == true)
            check(run.result["snapshotShizukuPermissionStatus"] == "RETURNED")
            check(run.result["snapshotShizukuPermissionResult"] == 0)
            check(run.result["snapshotShizukuListenerRemoved"] == true)
            check(Shizuku.permissionCalls == 1 && Shizuku.bindCalls == 1 && Shizuku.unbindCalls == 1)
            check(!Shizuku.bindBeforeReady)
            check(Shizuku.lastArgs!!.tagValue.startsWith("eyeprojector-snapshot-probe-"))
            check(Shizuku.detachCalls == 1 && Shizuku.localConnections.isEmpty() && Shizuku.remoteServices.isEmpty())
            check(!Shizuku.lastArgs!!.daemonValue && Shizuku.lastArgs!!.suffix == "snapshot_probe")
            check(Shizuku.callbackThreads.isNotEmpty() && Shizuku.callbackThreads.all { it == "fixture-main" })
            cleaned()
        }
    }
    private fun scenario(name: String, body: () -> Unit) {
        ProbeBoundary.reset(); Shizuku.reset(); SystemClock.advanceNanos = 0L; workers.clear()
        try { body(); println("PASS " + name) }
        finally {
            workers.forEach { it.interrupt() }
            workers.forEach { it.join(1000); check(!it.isAlive) }
            ProbeBoundary.main.shutdownNow()
            check(ProbeBoundary.main.awaitTermination(3, TimeUnit.SECONDS))
        }
    }
    @JvmStatic fun main(args: Array<String>) {
        if (args.singleOrNull() == "cleanup-parent") { cleanupParent(); return }
        if (args.singleOrNull() == "parent") {
            scenario("parent_raw_binder_granted_admits_without_received_callback") {
                Shizuku.rawBinder = true
                val run = Run()
                check(run.finish() == null && Shizuku.bindBeforeReady)
                check(Shizuku.permissionCalls == 1 && Shizuku.bindCalls == 1)
                check(Shizuku.removed.isEmpty() && Shizuku.callbackThreads.isEmpty())
                println("KNOWN_PARENT_COUNTEREXAMPLE raw Binder true/granted binds before callback; NOT proven118 cause or native runtime proof")
            }
            scenario("parent_initial_raw_binder_absence_fails_without_waiting_for_delivery") {
                val run = Run(); check(run.finish() is IllegalStateException)
                noAdmission(); check(Shizuku.listeners.isEmpty())
                Shizuku.publishAttached()
                check(Shizuku.bindCalls == 0)
                println("KNOWN_PARENT_COUNTEREXAMPLE immediate pre-bind failure can precede delivery; NOT proven118 cause")
            }
            return
        }
        scenario("already_received_sticky_callback_posts_to_free_main") {
            ready(); success(Run())
        }
        scenario("raw_absent_then_callback_ready_with_current_grant") {
            val run = Run(); await(Shizuku.registered); await(Shizuku.pingSampled)
            check(run.pending()); noAdmission()
            Shizuku.publishAttached(); success(run)
            check(run.result["snapshotShizukuBinderPingBeforeWait"] == false)
            check(run.result["snapshotShizukuBinderPingAfterWait"] == true)
        }
        scenario("raw_present_and_granted_still_waits_for_received_callback") {
            Shizuku.rawBinder = true
            val run = Run(); await(Shizuku.registered); await(Shizuku.pingSampled)
            check(run.pending()); noAdmission()
            Shizuku.publishAttached(); success(run)
            check(run.result["snapshotShizukuBinderPingBeforeWait"] == true)
        }
        scenario("callback_during_registration_is_not_lost_by_probe") {
            Shizuku.afterRegistration = { Shizuku.publishAttached() }
            success(Run())
        }
        scenario("duplicate_received_callbacks_bind_once") {
            Shizuku.afterRegistration = { repeat(3) { Shizuku.publishAttached() } }
            success(Run())
        }
        scenario("received_callback_preserves_actual_permission_denial") {
            ready(); Shizuku.permission = -1
            val run = Run(); check(run.finish()!!.message!!.contains("permission denied"))
            check(run.result["snapshotShizukuCallbackReady"] == true)
            check(run.result["snapshotShizukuBinderPingAfterWait"] == true)
            check(run.result["snapshotShizukuPermissionResult"] == -1)
            check(run.result["snapshotShizukuPermissionStatus"] == "RETURNED")
            check(Shizuku.bindCalls == 0 && Shizuku.unbindCalls == 0); cleaned()
        }
        scenario("permission_exception_is_distinct_from_denied_or_unchecked") {
            ready(); Shizuku.permissionFailure = SecurityException("fixture actual API exception")
            val run = Run(); check(run.finish() is SecurityException)
            check(run.result["snapshotShizukuPermissionStatus"] == "EXCEPTION")
            check(run.result["snapshotShizukuPermissionResult"] == null)
            check(run.result["snapshotShizukuPermissionException"].toString().contains("SecurityException"))
            check(Shizuku.bindCalls == 0); cleaned()
        }
        scenario("received_callback_then_dead_binder_is_not_permission_denial") {
            Shizuku.attached = true
            val run = Run(); check(run.finish()!!.message!!.contains("Binder is unavailable"))
            check(run.result["snapshotShizukuCallbackReady"] == true)
            check(run.result["snapshotShizukuBinderPingAfterWait"] == false)
            check(run.result["snapshotShizukuPermissionStatus"] == "NOT_CHECKED")
            noAdmission(); cleaned()
        }
        scenario("missing_callback_raw_ping_true_reports_unobserved_not_denied") {
            Shizuku.rawBinder = true
            Shizuku.afterRegistration = { SystemClock.advanceNanos = TimeUnit.SECONDS.toNanos(15) }
            val run = Run(); check(run.finish()!!.message!!.contains("callback did not arrive"))
            check(run.result["snapshotShizukuCallbackReady"] == false)
            check(run.result["snapshotShizukuBinderPingBeforeWait"] == true)
            check(run.result["snapshotShizukuBinderPingAfterWait"] == true)
            check(run.result["snapshotShizukuPermissionStatus"] == "NOT_CHECKED")
            check(run.result["snapshotShizukuPermissionResult"] == null)
            noAdmission(); cleaned()
        }
        scenario("sdk_sticky_ready_sample_insertion_gap_remains_truthful_timeout") {
            Shizuku.rawBinder = true
            Shizuku.afterStickySample = {
                Shizuku.publishAttached()
                SystemClock.advanceNanos = TimeUnit.SECONDS.toNanos(15)
            }
            val run = Run(); check(run.finish()!!.message!!.contains("callback did not arrive"))
            check(Shizuku.attached && Shizuku.callbackThreads.isEmpty())
            noAdmission(); cleaned()
        }
        scenario("already_posted_callback_after_removal_has_no_feature_effect") {
            val blocked = CountDownLatch(1); val release = CountDownLatch(1)
            ProbeBoundary.main.execute { blocked.countDown(); release.await() }; await(blocked)
            ready(); Shizuku.afterRegistration = { SystemClock.advanceNanos = TimeUnit.SECONDS.toNanos(15) }
            val run = Run(); check(run.finish()!!.message!!.contains("callback did not arrive"))
            val finalResult = run.result.snapshot(); noAdmission(); cleaned()
            release.countDown(); ProbeBoundary.main.submit {}.get(3, TimeUnit.SECONDS)
            check(Shizuku.callbackThreads.size == 1 && run.result.snapshot() == finalResult)
            noAdmission()
        }
        scenario("callback_and_service_wait_share_original_fifteen_second_budget") {
            Shizuku.afterRegistration = {
                SystemClock.advanceNanos = TimeUnit.SECONDS.toNanos(14); Shizuku.publishAttached()
            }
            Shizuku.beforeBind = {
                SystemClock.advanceNanos += TimeUnit.SECONDS.toNanos(1); ProbeBoundary.deliversService = false
            }
            val run = Run(); check(run.finish()!!.message!!.contains("Owned probe did not bind"))
            check(run.result["snapshotShizukuCallbackReady"] == true)
            check(Shizuku.bindCalls == 1 && Shizuku.unbindCalls == 1)
            check(ProbeBoundary.installCalls == 0); cleaned()
        }
        scenario("post_bind_descriptor_failure_keeps_original_owned_cleanup") {
            ready(); ProbeBoundary.descriptorFailure = IllegalArgumentException("fixture descriptor failure")
            val run = Run(); check(run.finish() is IllegalArgumentException)
            check(Shizuku.unbindCalls == 1 && ProbeBoundary.endpoint!!.closeCalls == 1)
            check(ProbeBoundary.installCalls == 0); cleaned()
        }
        scenario("registration_partial_failure_removes_exact_installed_listener") {
            Shizuku.afterRegistration = { throw IllegalArgumentException("fixture registration failure") }
            val run = Run(); check(run.finish() is IllegalArgumentException)
            noAdmission(); cleaned()
            check(run.result["snapshotShizukuListenerRemoved"] == true)
            check(run.result["snapshotShizukuCallbackReady"] == null) // Registration did not reach a wait result.
        }
        scenario("interrupted_callback_wait_removes_listener_without_service") {
            val run = Run(); await(Shizuku.registered); await(Shizuku.pingSampled)
            run.thread.interrupt(); check(run.finish() is InterruptedException)
            noAdmission(); cleaned()
            check(run.result["snapshotShizukuCallbackReady"] == null) // Interrupted wait is not a timeout result.
        }
        scenario("android_route_keeps_existing_service_word_context_fd_cleanup") {
            val run = Run("android"); success(run, false)
            check(Shizuku.permissionCalls == 0 && Shizuku.bindCalls == 0 && Shizuku.removed.isEmpty())
            check(ProbeBoundary.androidBinds == 1 && ProbeBoundary.androidUnbinds == 1)
            check(run.result["snapshotShizukuCallbackReady"] == null)
            cleaned(false)
        }
        if (args.singleOrNull() == "cleanup") cleanupCurrent()
    }

    private fun partialFailure(synchronous: Boolean): Pair<Run, Throwable> {
        ready(); Shizuku.synchronousConnection = synchronous
        if (!synchronous) ProbeBoundary.deliversService = false
        val primary = AssertionError("original uncertain bind reply")
        Shizuku.afterBindSideEffects = { throw primary }
        return Run() to primary
    }
    private fun cleanupParent() {
        for (synchronous in listOf(false, true)) scenario("parent130_bind_sideeffects_then_throw_" + if (synchronous) "with_handle" else "no_handle") {
            val (run, primary) = partialFailure(synchronous)
            check(run.finish() === primary)
            check(Shizuku.unbindCalls == 0 && Shizuku.detachCalls == 0)
            check(Shizuku.remoteServices.size == 1 && Shizuku.localConnections.size == 1)
            check(ProbeBoundary.installCalls == 0)
            if (synchronous) check(ProbeBoundary.endpoint!!.closed)
            println("KNOWN_PARENT_COUNTEREXAMPLE C121-01 original SDK connection/remote effect unreconciled after throw; source model only")
        }
    }
    private fun cleanupCurrent() {
        for (synchronous in listOf(false, true)) scenario("cleanup130_bind_sideeffects_then_throw_" + if (synchronous) "with_handle" else "no_handle") {
            val (run, primary) = partialFailure(synchronous)
            check(run.finish() === primary && primary.suppressed.isEmpty())
            check(Shizuku.unbindCalls == 1 && Shizuku.detachCalls == 1)
            check(Shizuku.localConnections.isEmpty() && Shizuku.remoteServices.isEmpty())
            check(ProbeBoundary.installCalls == 0)
            check(run.result["snapshotShizukuBindInvoked"] == true && run.result["snapshotShizukuBindReturned"] == null)
            check(run.result["snapshotCleanupRegion"] == if (synchronous) "RETURNED" else "NO_HANDLE")
            if (synchronous) check(ProbeBoundary.endpoint!!.closed)
            cleaned()
        }
        scenario("cleanup130_local_registration_throw_before_remote") {
            ready(); val primary = IllegalArgumentException("before remote")
            Shizuku.beforeBind = { throw primary }
            val run = Run(); check(run.finish() === primary)
            check(Shizuku.unbindCalls == 1 && Shizuku.detachCalls == 1)
            check(Shizuku.localConnections.isEmpty() && Shizuku.remoteServices.isEmpty())
            check(run.result["snapshotCleanupRegion"] == "NO_HANDLE"); cleaned()
        }
        scenario("cleanup130_queued_connection_after_retirement_has_no_rpc_or_result_effect") {
            ready(); val blocked = CountDownLatch(1); val release = CountDownLatch(1)
            Shizuku.beforeBind = {
                ProbeBoundary.main.execute { blocked.countDown(); release.await() }; await(blocked)
            }
            val primary = IllegalStateException("reply failed")
            Shizuku.afterBindSideEffects = { throw primary }
            val run = Run(); check(run.finish() === primary)
            val result = run.result.snapshot(); val calls = Shizuku.cleanupArgs.size
            check(run.result["snapshotCleanupRegion"] == "NO_HANDLE")
            release.countDown(); ProbeBoundary.main.submit {}.get(3, TimeUnit.SECONDS)
            check(run.result.snapshot() == result && Shizuku.cleanupArgs.size == calls)
            check(ProbeBoundary.installCalls == 0 && ProbeBoundary.endpoint!!.closeCalls == 0)
            cleaned()
        }
        scenario("cleanup130_second_proxy_cannot_replace_original_cleanup_handle") {
            ready(); Shizuku.synchronousConnection = true
            val successor = Endpoint(); val primary = IllegalStateException("after duplicate callback")
            Shizuku.afterBindSideEffects = {
                val args = Shizuku.lastArgs!!
                Shizuku.lastConnection!!.onServiceConnected(android.content.ComponentName(args.component.context, NativeSnapshotProbeService::class.java), successor)
                throw primary
            }
            val run = Run(); check(run.finish() === primary)
            check(ProbeBoundary.endpoint!!.closed && successor.closeCalls == 0)
            check(ProbeBoundary.installCalls == 0); cleaned()
        }
        scenario("cleanup130_all_cleanup_failures_preserve_primary_error_and_continue") {
            ready(); Shizuku.synchronousConnection = true
            val primary = AssertionError("primary Error")
            val failures = listOf(IllegalStateException("listener"), IllegalStateException("clear"), IllegalStateException("region"), IllegalStateException("remove"), IllegalStateException("detach"))
            Shizuku.afterBindSideEffects = { throw primary }
            Shizuku.listenerRemoveFailure = failures[0]; ProbeBoundary.clearFailure = failures[1]
            ProbeBoundary.closeFailure = failures[2]; Shizuku.remoteRemoveFailure = failures[3]; Shizuku.localDetachFailure = failures[4]
            val run = Run(); check(run.finish() === primary)
            check(primary.suppressed.toList() == failures)
            for (stage in listOf("Listener", "ReadLane", "Region", "RemoteRemove", "LocalDetach")) check(run.result["snapshotCleanup" + stage] == "EXCEPTION")
            check(Shizuku.unbindCalls == 1 && Shizuku.detachCalls == 1)
            check(Shizuku.localConnections.isNotEmpty() && Shizuku.remoteServices.isNotEmpty())
        }
        for (stage in listOf("Listener", "ReadLane", "Region", "RemoteRemove", "LocalDetach")) scenario("cleanup130_cleanup_only_failure_visible_" + stage) {
            ready(); val failure = IllegalStateException("cleanup only " + stage)
            when (stage) {
                "Listener" -> Shizuku.listenerRemoveFailure = failure
                "ReadLane" -> { ProbeBoundary.clearFailure = failure; ProbeBoundary.clearFailureAtCall = 2 }
                "Region" -> { ProbeBoundary.closeFailure = failure; ProbeBoundary.closeFailureAtCall = 2 }
                "RemoteRemove" -> Shizuku.remoteRemoveFailure = failure
                "LocalDetach" -> Shizuku.localDetachFailure = failure
            }
            val run = Run(); check(run.finish() === failure)
            check(run.result["snapshotWords"] == "6,8,14,17")
            check(run.result["snapshotCleanup" + stage] == "EXCEPTION")
            check(Shizuku.unbindCalls == 1 && Shizuku.detachCalls == 1)
            if (stage == "RemoteRemove") check(Shizuku.remoteServices.isNotEmpty() && Shizuku.localConnections.isEmpty())
            if (stage == "LocalDetach") check(Shizuku.remoteServices.isEmpty() && Shizuku.localConnections.isNotEmpty())
        }
        scenario("cleanup130_android_unbind_failure_does_not_mask_primary") {
            val primary = AssertionError("Android descriptor")
            val cleanup = IllegalStateException("Android unbind")
            ProbeBoundary.descriptorFailure = primary; ProbeBoundary.androidUnbindFailure = cleanup
            val run = Run("android"); check(run.finish() === primary && primary.suppressed.single() === cleanup)
            check(ProbeBoundary.androidUnbinds == 1 && Shizuku.unbindCalls == 0)
            check(run.result["snapshotCleanupAndroidUnbind"] == "EXCEPTION"); cleaned(false)
        }
        scenario("cleanup130_distinct_attempt_namespaces_and_original_args") {
            ready(); val first = Run(); check(first.finish() == null)
            val original = Shizuku.lastArgs!!; val oldConnection = Shizuku.lastConnection!!
            val second = Run(); check(second.finish() == null)
            val next = Shizuku.lastArgs!!
            check(original.tagValue != next.tagValue)
            check(Shizuku.cleanupArgs.take(2).all { it === original } && Shizuku.cleanupArgs.takeLast(2).all { it === next })
            val count = Shizuku.cleanupArgs.size; val state = second.result.snapshot()
            oldConnection.onServiceConnected(original.component, Endpoint())
            check(Shizuku.cleanupArgs.size == count && second.result.snapshot() == state)
        }
        scenario("cleanup130_listener_not_found_is_reported_without_claiming_removal") {
            ready(); Shizuku.afterBindSideEffects = { Shizuku.listeners.clear() }
            val run = Run(); check(run.finish() == null)
            check(run.result["snapshotShizukuListenerRemoved"] == false)
            check(run.result["snapshotShizukuListenerOutcome"] == "NOT_FOUND")
            check(Shizuku.unbindCalls == 1 && Shizuku.detachCalls == 1)
        }
        scenario("cleanup130_returned_current_endpoint_request_is_not_original_teardown_proof") {
            ready(); ProbeBoundary.deliversService = false
            val primary = IllegalStateException("original reply failed after endpoint replacement")
            Shizuku.afterBindSideEffects = { Shizuku.currentEndpoint++; throw primary }
            val run = Run(); check(run.finish() === primary)
            check(run.result["snapshotCleanupRemoteRemove"] == "RETURNED")
            check(run.result["snapshotCleanupLocalDetach"] == "RETURNED")
            check(Shizuku.remoteServices.contains(Shizuku.lastArgs!!.tagValue))
            check(Shizuku.localConnections.isEmpty()) // Original app callback entry detached; remote outcome unproved.
            check(run.result["snapshotCleanupRegion"] == "NO_HANDLE")
        }
    }
}
