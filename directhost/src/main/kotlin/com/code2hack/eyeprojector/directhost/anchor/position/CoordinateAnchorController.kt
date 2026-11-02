package com.code2hack.eyeprojector.directhost.anchor.position

import com.code2hack.eyeprojector.protocol.AnchorInteractionMode
import com.code2hack.eyeprojector.protocol.AnchorPosition
import com.code2hack.eyeprojector.protocol.CoordinateAnchorState
import java.io.IOException

sealed interface AnchorModeAction {
    /** Null identifies EyeProjector Home, regardless of its Android package. */
    data class AppChanged(val packageName: String?) : AnchorModeAction
    data object CycleMode : AnchorModeAction
    data object ScrollUp : AnchorModeAction
    data object ScrollDown : AnchorModeAction
    data class Tap(val position: AnchorPosition) : AnchorModeAction
    data object Back : AnchorModeAction
}

/** Unconsumed Tap/Back/Scroll continues through ordinary input ownership, including held Tap/drag.
 * A failed Settings write is consumed, reports its error, and retains the last committed state.
 * A failed app load reports the NEW package in Normal with no selected/saved positions. */
data class AnchorModeResult(
    val state: CoordinateAnchorState,
    val consumed: Boolean,
    val positionsSaved: Boolean = false,
    val failure: IOException? = null,
)

/** One process-instance owner shared by native and Phone-HID routes. No contact/timing ownership.
 * Storage errors are returned explicitly. A failed foreground load discards the old foreground
 * presentation; its empty Normal state is NOT a successfully loaded list. Subsequent actions retry
 * that load before mode changes/edits, so unread original bytes cannot be overwritten by an edit.
 * Ordinary Normal input can continue when loading fails. AppChanged(null) always selects Home.
 * Per-app mode/selection is memory only; reconstructing resets it.
 * Callers publish each dispatch result in their input order; snapshot does not replay actions. */
class CoordinateAnchorController(private val storage: AnchorPositionStorage) {
    private val applications = mutableMapOf<String, CoordinateAnchorState>()
    private var current = CoordinateAnchorState()

    @Synchronized fun snapshot(): CoordinateAnchorState = current.copy(positions = current.positions.toList())

    @Synchronized fun dispatch(action: AnchorModeAction): AnchorModeResult {
        if (action is AnchorModeAction.AppChanged) {
            return changeApp(action.packageName)
        }
        val packageName = current.packageName
        if (packageName != null && packageName !in applications) {
            val loaded = changeApp(packageName)
            if (loaded.failure != null) return result(consumed = action == AnchorModeAction.CycleMode,
                failure = loaded.failure)
        }
        val mode = current.mode
        return when (action) {
            AnchorModeAction.CycleMode -> {
                if (current.packageName != null) {
                    val next = when (mode) {
                        AnchorInteractionMode.NORMAL -> if (current.positions.isEmpty()) AnchorInteractionMode.ANCHOR_SETTINGS
                            else AnchorInteractionMode.ANCHOR
                        AnchorInteractionMode.ANCHOR -> AnchorInteractionMode.ANCHOR_SETTINGS
                        AnchorInteractionMode.ANCHOR_SETTINGS -> AnchorInteractionMode.NORMAL
                    }
                    update(current.copy(mode = next, selectedIndex = if (next == AnchorInteractionMode.ANCHOR) 0 else null))
                }
                result(consumed = true)
            }
            AnchorModeAction.ScrollUp, AnchorModeAction.ScrollDown -> {
                if (mode == AnchorInteractionMode.ANCHOR) {
                    val delta = if (action == AnchorModeAction.ScrollDown) 1 else -1
                    val selected = Math.floorMod(checkNotNull(current.selectedIndex) + delta, current.positions.size)
                    update(current.copy(selectedIndex = selected))
                }
                result(consumed = mode != AnchorInteractionMode.NORMAL)
            }
            is AnchorModeAction.Tap -> if (mode == AnchorInteractionMode.ANCHOR_SETTINGS)
                save(current.positions + action.position) else result(consumed = false)
            AnchorModeAction.Back -> if (mode == AnchorInteractionMode.ANCHOR_SETTINGS) {
                if (current.positions.isEmpty()) result(consumed = true) else save(current.positions.dropLast(1))
            } else result(consumed = false)
            is AnchorModeAction.AppChanged -> error("App change already handled")
        }
    }

    private fun changeApp(packageName: String?): AnchorModeResult {
        if (packageName == null) {
            current = CoordinateAnchorState()
            return result(consumed = true)
        }
        current = CoordinateAnchorState(packageName)
        val loaded = try {
            applications[packageName] ?: CoordinateAnchorState(packageName,
                positions = storage.read(packageName).toList()).also { applications[packageName] = it }
        } catch (failure: IOException) { return result(consumed = true, failure = failure) }
        current = loaded
        return result(consumed = true)
    }

    private fun save(positions: List<AnchorPosition>): AnchorModeResult {
        try { storage.write(checkNotNull(current.packageName), positions) }
        catch (failure: IOException) { return result(consumed = true, failure = failure) }
        update(current.copy(positions = positions))
        return result(consumed = true, saved = true)
    }

    private fun update(next: CoordinateAnchorState) {
        current = next
        next.packageName?.let { applications[it] = next }
    }

    private fun result(consumed: Boolean, saved: Boolean = false, failure: IOException? = null) =
        AnchorModeResult(snapshot(), consumed, saved, failure)
}
