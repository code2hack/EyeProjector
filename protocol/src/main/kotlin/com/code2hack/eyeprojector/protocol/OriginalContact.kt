package com.code2hack.eyeprojector.protocol

/**
 * An execution owner's immutable original contact, shared by Click and Hold execution.
 * [context] retains the actual local route/session/window token chosen by that owner. The owner
 * sends all edges through that context, and retires it through its existing input lifecycle.
 * An UP or CANCEL uses this target and contact ID without another target lookup. CANCEL is
 * retirement, not successful completion. Physical Function UP is not an edge of this contact.
 *
 * This value contains no held-state latch, resolver, saved preference, timeout or replay logic.
 */
class OriginalContact<C : Any>(val context: C, val contactId: Long, val target: Tap) {
    init { require(contactId >= 0) }
    fun down() = Touch(Touch.Phase.DOWN, contactId, target)
    fun up() = Touch(Touch.Phase.UP, contactId, target)
    fun cancel() = Touch(Touch.Phase.CANCEL, contactId, target)
}
