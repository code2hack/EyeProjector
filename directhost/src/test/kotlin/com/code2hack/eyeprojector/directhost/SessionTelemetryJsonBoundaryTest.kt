package com.code2hack.eyeprojector.directhost

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SessionTelemetryJsonBoundaryTest {
    @Test fun fluentEncoderRetainsNestedTelemetryNumbersArraysAndExplicitNull() {
        val phases = JSONObject().put("closeMs", 12L)
        val array = JSONArray().put("stage").put(true).put(2.5).put(null)
        val value = JSONObject()
        assertSame(value, value.put("phases", phases).put("steps", array).put("missing", JSONObject.NULL))
        assertEquals("{\"phases\":{\"closeMs\":12},\"steps\":[\"stage\",true,2.5,null],\"missing\":null}", value.toString())
    }
    @Test fun nullRemovesKeyAndStringsAreEscapedRatherThanDropped() {
        val value = JSONObject().put("retired", 4).put("retired", null).put("reason", "a\"b\\c\n\t")
        assertEquals("{\"reason\":\"a\\\"b\\\\c\\n\\t\"}", value.toString())
        assertEquals(71L, JSONObject().put("count", "71").optLong("count", -1))
        assertEquals(-1L, value.optLong("absent", -1))
    }
}
