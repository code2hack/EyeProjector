package com.code2hack.eyeprojector.directhost

import java.io.EOFException
import java.io.IOException
import java.net.SocketTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SessionReasonTest {
    @Test fun failuresKeepTheirLaneAndNeverExposeMessages() {
        val cases = listOf(
            Triple(EOFException("PRIVATE_SENTINEL"), SessionStopReason.CONTROL_EOF, SessionStopReason.VIDEO_EOF),
            Triple(SocketTimeoutException("PRIVATE_SENTINEL"), SessionStopReason.CONTROL_TIMEOUT, SessionStopReason.VIDEO_TIMEOUT),
            Triple(IOException("PRIVATE_SENTINEL"), SessionStopReason.CONTROL_IO, SessionStopReason.VIDEO_IO),
            Triple(IllegalArgumentException("PRIVATE_SENTINEL"), SessionStopReason.CONTROL_ERROR, SessionStopReason.VIDEO_ERROR),
            Triple(SecurityException("PRIVATE_SENTINEL"), SessionStopReason.CONTROL_ERROR, SessionStopReason.VIDEO_ERROR),
        )
        for ((error, control, video) in cases) {
            assertEquals(control, sessionFailureReason(error, control = true))
            assertEquals(video, sessionFailureReason(error, control = false))
            assertFalse(sessionFailureReason(error, control = true).name.contains("PRIVATE_SENTINEL"))
            assertFalse(sessionFailureReason(error, control = false).name.contains("PRIVATE_SENTINEL"))
        }
    }
}
