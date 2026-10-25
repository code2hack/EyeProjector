package com.code2hack.eyeprojector.directrg

import android.content.Context
import android.content.Intent
import android.os.PowerManager
import com.code2hack.eyeprojector.protocol.*

/** Candidate public stock adapter. Submission/power acquisition is never a presentation acknowledgment. */
internal class ReceiverWake(private val context: Context, private val feature: ReceiverActivationStore) {
    private var held: PowerManager.WakeLock? = null
    private var current: ReceiverDemand? = null
    private var retryable = false
    private var fulfilled: ReceiverDemand? = null
    var outcome = "STANDBY"; private set
    fun request(directive: ActivationDirective) {
        if (!feature.permits(directive)) return
        val key = feature.demand() ?: return
        if (current == key) return
        release(); current = key
        perform(directive)
    }
    fun retryPending() {
        val d = feature.directive ?: return
        if (retryable && feature.permits(d) && current == feature.demand()) perform(d)
    }
    private fun perform(directive: ActivationDirective) {
        val demand = feature.demand() ?: return
        if (demand != current || !feature.permits(directive)) return
        try {
            @Suppress("DEPRECATION")
            val lock = context.getSystemService(PowerManager::class.java).newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "${context.packageName}:activation")
            held = lock
            if (!context.getSystemService(PowerManager::class.java).isInteractive) lock.acquire()
            outcome = "SCREEN_WAKE_SUBMITTED"
            if (feature.demand() != demand || !feature.permits(directive)) return
            context.startActivity(intent(context, demand))
            outcome = "ACTIVITY_LAUNCH_SUBMITTED"; retryable = false
        } catch (_: Exception) { outcome = "WAKE_OR_ACTIVITY_UNAVAILABLE_RETRYING"; retryable = true }
        finally { release() } // Wake submission is the transition; do not hold a screen lock awaiting visibility.
    }
    fun reconcile(report: PresentationReport?, failed: Boolean) {
        val d = feature.directive
        val presented = d != null && report?.presented == true &&
            (d.goal == ActivationGoal.USABLE || report.mode == ReceiverUsabilityMode.PROJECTED) && report.activationId == d.activationId &&
            report.wakeRevision == d.wakeRevision && report.ownerEpoch == d.ownerEpoch && report.incarnation == feature.incarnation
        if (presented) { outcome = "PRESENTATION_OBSERVED"; fulfilled = feature.demand(); retryable = false; release() }
        else if (d == null || !feature.permits(d)) { retryable = false; release() }
        else {
            // Returning from startActivity is submission only. Keep the existing service
            // reconciliation tick eligible until current presentation is actually observed.
            // A fulfilled wake may sleep normally. A NEW original demand revision is required;
            // an unfulfilled submission keeps retrying, including silent launch denial.
            retryable = fulfilled != feature.demand()
            if (failed) release()
        }
    }
    fun release() { held?.let { if (it.isHeld) it.release() }; held = null }
    companion object {
        const val ACTIVATION = "eyeprojector.activation"
        const val REVISION = "eyeprojector.wakeRevision"
        const val INCARNATION = "eyeprojector.receiverIncarnation"
        const val SEQUENCE = "eyeprojector.receiverSequence"
        const val OWNER = "eyeprojector.backendOwner"
        const val GOAL = "eyeprojector.presentationGoal"
        fun intent(context: Context, demand: ReceiverDemand) = Intent(context, ReceiverActivity::class.java)
            .putExtra(ACTIVATION, demand.activationId).putExtra(REVISION, demand.wakeRevision)
            .putExtra(INCARNATION, demand.incarnation.id).putExtra(SEQUENCE, demand.incarnation.sequence)
            .putExtra(OWNER, demand.ownerEpoch).putExtra(GOAL, demand.goal.name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        fun readIntent(intent: Intent): ReceiverDemand? {
            val h = intent.getStringExtra(ACTIVATION) ?: return null
            val i = intent.getStringExtra(INCARNATION) ?: return null
            val owner = intent.getStringExtra(OWNER) ?: return null
            val goal = ActivationGoal.entries.firstOrNull { it.name == intent.getStringExtra(GOAL) } ?: return null
            return ReceiverDemand(h, owner, intent.getLongExtra(REVISION, -1),
                ReceiverIncarnation(i, intent.getLongExtra(SEQUENCE, -1)), goal)
        }
    }
}
