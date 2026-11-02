package com.code2hack.eyeprojector.directhost.input

import com.code2hack.eyeprojector.directhost.CapturedProjection
import com.code2hack.eyeprojector.protocol.AnchorIntentConsumers

/** Per-original-Session entrypoints for registration and activation; no target or contact owner. */
internal interface AnchorIntentRoute {
    val anchorIntents: AnchorIntentConsumers<CapturedProjection>
}
