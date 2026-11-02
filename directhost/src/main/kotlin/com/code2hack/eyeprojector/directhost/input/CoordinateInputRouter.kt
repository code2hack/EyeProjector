package com.code2hack.eyeprojector.directhost.input

import com.code2hack.eyeprojector.protocol.*

/** The Session FIFO invokes this router. Decisions precede effects, including every contact DOWN. */
internal class CoordinateInputRouter(
    private val decide: (CoordinateOperation,Tap?) -> CoordinateModeResult,
    private val outputTouch: (Touch) -> Boolean,
    private val outputTap: (Tap) -> Boolean,
    private val outputScroll: (Scroll) -> Boolean,
) {
    private val contacts=mutableMapOf<Long,CoordinateModeResult>()
    private fun pinned(target: Tap,state: CoordinateAnchorState): Tap = state.selectedPosition?.let {
        Tap(it.x.toInt().coerceIn(0,target.geometry.width-1),it.y.toInt().coerceIn(0,target.geometry.height-1),target.geometry)
    } ?: target
    fun touch(event: Touch): Boolean {
        val decision=if(event.phase == Touch.Phase.DOWN) decide(CoordinateOperation.TAP,event.target).also {
            contacts[event.contactId]=it
        } else contacts[event.contactId] ?: return event.phase == Touch.Phase.CANCEL
        if(event.phase == Touch.Phase.UP || event.phase == Touch.Phase.CANCEL) contacts.remove(event.contactId)
        return decision.consumed || outputTouch(event.copy(target=pinned(event.target,decision.state)))
    }
    fun cancel() { contacts.clear() }
    fun tap(target: Tap): Boolean {
        val result=decide(CoordinateOperation.TAP,target)
        return result.consumed || outputTap(pinned(target,result.state))
    }
    fun scroll(event: Scroll,passive: Boolean=false): Boolean {
        val result=decide(if(passive) CoordinateOperation.SNAPSHOT else
            if(event.pixels>0) CoordinateOperation.SCROLL_DOWN else CoordinateOperation.SCROLL_UP,null)
        return if(passive && result.state.mode != AnchorInteractionMode.NORMAL || !passive && result.consumed) true
            else outputScroll(event)
    }
    fun back(): Boolean = decide(CoordinateOperation.BACK,null).consumed
}
