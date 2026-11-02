package com.code2hack.eyeprojector.directhost.input

import android.content.Context
import com.code2hack.eyeprojector.directhost.*
import com.code2hack.eyeprojector.directhost.anchor.position.*
import com.code2hack.eyeprojector.protocol.*

/** App-process persistence/mode authority. Shell callers serialize commands with input effects. */
internal class PhoneModeOwner(context: Context,private val controller: CoordinateAnchorController =
    CoordinateAnchorPreferences.open(context)) : IPhoneModeOwner.Stub() {
    private class Source(val context: ProjectionContext, val session: IPhoneInputSession)
    @Volatile private var source: Source? = null
    @Synchronized override fun onSession(ownerEpoch: String, projectionToken: String, session: IPhoneInputSession) {
        source = Source(ProjectionContext(ownerEpoch, projectionToken), session)
    }
    @Synchronized fun invalidateBinding() { source = null }
    fun captureOriginal(): PhoneHidOriginal {
        val captured = source
        return PhoneHidOriginal(captured?.session, captured?.context ?: ProjectionContext()) { source === captured }
    }
    @Synchronized override fun decide(expectedOwnerEpoch: String,expectedProjectionToken: String,
        packageName: String?,operation: Int,x: Double,y: Double): ByteArray? {
        if(source?.context != ProjectionContext(expectedOwnerEpoch,expectedProjectionToken)) return null
        val changed = controller.dispatch(AnchorModeAction.AppChanged(packageName))
        val action = when (CoordinateOperation.entries[operation]) {
            CoordinateOperation.SNAPSHOT -> null
            CoordinateOperation.CYCLE -> AnchorModeAction.CycleMode
            CoordinateOperation.TAP -> AnchorModeAction.Tap(AnchorPosition(x,y))
            CoordinateOperation.BACK -> AnchorModeAction.Back
            CoordinateOperation.SCROLL_UP -> AnchorModeAction.ScrollUp
            CoordinateOperation.SCROLL_DOWN -> AnchorModeAction.ScrollDown
        }
        val result = action?.let(controller::dispatch) ?: changed
        return CoordinateModeResult(result.state, result.consumed, if(result.failure != null || changed.failure != null) "Could not read or save Anchor positions." else null).bytes()
    }
}
