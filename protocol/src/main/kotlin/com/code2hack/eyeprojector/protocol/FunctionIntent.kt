package com.code2hack.eyeprojector.protocol

/** Source compatibility for the historical transport names. Anchor owns these operations;
 * no physical Function recognizer is part of this contract. Wire payload bytes are unchanged. */
typealias FunctionIntentKind = AnchorIntentKind
typealias FunctionSource = AnchorSource
typealias FunctionIntentContext = AnchorIntentContext
typealias FunctionOriginal = AnchorOriginal
typealias FunctionIntent = AnchorIntent
typealias CapturedFunctionIntent<C> = CapturedAnchorIntent<C>
typealias FunctionIntentConsumers<C> = AnchorIntentConsumers<C>
