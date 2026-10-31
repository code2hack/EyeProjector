package com.code2hack.eyeprojector.directhost.input

import com.code2hack.eyeprojector.directhost.CapturedProjection
import com.code2hack.eyeprojector.protocol.FunctionIntentConsumers

/** Per-original-Session entrypoints for registration and activation; no target or contact owner. */
internal interface FunctionIntentRoute {
    val functionIntents: FunctionIntentConsumers<CapturedProjection>
}
