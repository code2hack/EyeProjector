package com.code2hack.eyeprojector.directrg

import com.code2hack.eyeprojector.protocol.AnchorIntentKind

internal data class ReceiverAnchorOriginal(val source: ProjectionSession,
    val context: com.code2hack.eyeprojector.protocol.AnchorIntentContext, val userRevision: Long)

/** Semantic operation and original ownership passed to Anchor by a native input adapter.
 * Phone-originated operations arrive through the shared AnchorIntent protocol. */
internal data class AnchorGesture(val kind: AnchorIntentKind, val gestureId: Long, val sequence: Long,
    val original: Any, val physicalHeld: Boolean = false)
