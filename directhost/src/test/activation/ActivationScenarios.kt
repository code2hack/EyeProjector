package com.code2hack.eyeprojector.directhost.activation

/** Only an immutable target seam. No Phone session/backend/context implementation is compiled here. */
private data class Target(val owner: String, val projection: String)

fun activationScenarios(): Map<String, () -> Unit> = linkedMapOf(
    "local activation overlap joins one intent and one attempt" to {
        val owner = PhoneActivationOwner<Target>()
        val handle = owner.requestActivation(ActivationCause.HID_ATTACHMENT)
        val first = owner.beginAttempt(handle)!!
        check(owner.requestActivation(ActivationCause.NOTIFICATION) === handle)
        check(owner.beginAttempt(handle) == null)
        owner.setupRequired(first, "AUTHORIZATION_REQUIRED")
        check(owner.state(handle).phase == ActivationPhase.SETUP_REQUIRED)
        check(owner.state(handle).detail == "AUTHORIZATION_REQUIRED")
        check(!owner.ready(first, Target("unobserved", "unobserved")))
        check(owner.state(handle).attempt === first)
        check(owner.beginAttempt(handle) == null)
    },
    "local retry retains intent and waits for actual release" to {
        val owner = PhoneActivationOwner<Target>()
        val handle = owner.requestActivation(ActivationCause.GLASSES_LAUNCH)
        var previous = owner.beginAttempt(handle)!!
        // Repeated recovery is scheduled by the external owner, with no retry count in this helper.
        repeat(12) {
            check(owner.releasing(previous, "NETWORK_FAILURE"))
            check(!owner.awaitingPresentation(previous))
            check(!owner.ready(previous, Target("old", "old")))
            check(owner.beginAttempt(handle) == null)
            check(owner.released(previous))
            val next = owner.beginAttempt(handle)!!
            check(next !== previous)
            check(!owner.released(previous))
            check(!owner.ready(previous, Target("old", "old")))
            check(owner.state(handle).attempt === next)
            previous = next
        }
    },
    "cancel before admission makes queued old starts inert" to {
        val owner = PhoneActivationOwner<Target>()
        val old = owner.requestActivation(ActivationCause.HID_PRESS)
        check(owner.cancelActivation(old) == null)
        val next = owner.requestActivation(ActivationCause.NOTIFICATION)
        check(next !== old && owner.beginAttempt(old) == null)
        check(owner.state(old).phase == ActivationPhase.CANCELED)
        check(owner.beginAttempt(next) != null)
    },
    "canceled attempt release cannot retarget or cancel the replacement" to {
        val owner = PhoneActivationOwner<Target>()
        val old = owner.requestActivation(ActivationCause.PHONE_START)
        val attempt = owner.beginAttempt(old)!!
        check(owner.cancelActivation(old) === attempt)
        val next = owner.requestActivation(ActivationCause.HID_PRESS)
        check(owner.beginAttempt(next) == null) // Cancel submission alone is not actual release.
        check(!owner.ready(attempt, Target("old", "old")))
        check(owner.released(attempt))
        val replacement = owner.beginAttempt(next)!!
        check(owner.cancelActivation(old) == null)
        check(!owner.released(attempt))
        check(!owner.awaitingPresentation(attempt))
        check(owner.state(next).attempt === replacement)
    },
    "ready callback captures target once and tolerates Stop and new-request reentry" to {
        val owner = PhoneActivationOwner<Target>()
        val handle = owner.requestActivation(ActivationCause.NOTIFICATION)
        val attempt = owner.beginAttempt(handle)!!
        val target = Target("backend-A", "projection-A")
        val receipts = mutableListOf<ActivationReady<Target>>()
        var newer: ActivationHandle? = null
        owner.whenReady(handle) { receipt ->
            receipts.add(receipt)
            owner.cancelActivation(handle)
            newer = owner.requestActivation(ActivationCause.HID_ATTACHMENT)
        }
        check(owner.awaitingPresentation(attempt))
        check(receipts.isEmpty())
        check(owner.ready(attempt, target))
        check(receipts.size == 1 && receipts.single().target === target)
        check(receipts.single().attempt === attempt && receipts.single().handle === handle)
        check(owner.state(handle).phase == ActivationPhase.CANCELED)
        check(owner.state(newer!!).phase == ActivationPhase.PENDING)
        check(!owner.ready(attempt, Target("backend-B", "projection-B")))
        check(receipts.size == 1)
    },
    "observer reentry suppresses stale ready and attempt callbacks" to {
        val owner = PhoneActivationOwner<Target>()
        val handle = owner.requestActivation(ActivationCause.PHONE_START)
        val values = mutableListOf<ActivationReady<Target>>()
        owner.observe(handle) { value ->
            if (value.phase == ActivationPhase.READY) owner.cancelActivation(handle)
        }
        owner.whenReady(handle, values::add)
        val attempt = owner.beginAttempt(handle)!!
        owner.ready(attempt, Target("A", "A"))
        check(values.isEmpty()) // First observer canceled before the next callback was eligible.
        val next = owner.requestActivation(ActivationCause.PHONE_START)
        owner.observe(next) { value -> if (value.phase == ActivationPhase.STARTING) owner.cancelActivation(next) }
        check(owner.released(attempt))
        check(owner.beginAttempt(next) == null) // Reentrant cancellation prevents returning an admitted token.
        val final = owner.requestActivation(ActivationCause.HID_PRESS)
        check(owner.beginAttempt(final) != null) // Undispatched construction leaves no phantom release barrier.
    },
    "presentation loss invalidates ready without allocating another backend" to {
        val owner = PhoneActivationOwner<Target>()
        val handle = owner.requestActivation(ActivationCause.PHONE_START)
        val attempt = owner.beginAttempt(handle)!!
        val first = Target("same-backend", "same-projection")
        check(owner.ready(attempt, first))
        var immediate: ActivationReady<Target>? = null
        owner.whenReady(handle) { immediate = it }
        check(immediate?.target === first)
        check(owner.awaitingPresentation(attempt))
        val later = mutableListOf<ActivationReady<Target>>()
        val subscription = owner.whenReady(handle, later::add)
        check(owner.requestActivation(ActivationCause.HID_PRESS) === handle)
        check(owner.beginAttempt(handle) == null && owner.state(handle).target == null)
        check(later.isEmpty())
        subscription.close()
        check(!owner.ready(attempt, Target("another-owner", "another-projection")))
        check(owner.ready(attempt, first.copy()))
        check(owner.state(handle).target === first)
        check(later.isEmpty())
    },
)
