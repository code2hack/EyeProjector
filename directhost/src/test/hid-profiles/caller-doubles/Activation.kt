package com.code2hack.eyeprojector.directhost.activation

internal enum class ActivationCause { PHONE_START, GLASSES_LAUNCH, HID_ATTACHMENT, HID_PRESS, NOTIFICATION }
internal data class ActivationHandle(val id: String = "fixture")
