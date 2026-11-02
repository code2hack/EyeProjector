package com.code2hack.eyeprojector.directhost.anchor.execution

// Transitional v0.3 admission object only. No Anchor implementation or platform is modeled.
internal enum class AnchorControlDisposition { NORMAL, CONSUMED, RETIRED }
internal class AnchorControlAdmission {
    var disposition = AnchorControlDisposition.NORMAL
    val terminal get() = disposition != AnchorControlDisposition.NORMAL
}
