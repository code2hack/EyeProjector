package com.code2hack.eyeprojector.directhost

import java.io.ByteArrayOutputStream
import java.util.Properties

/** Optional mission observations for a new normal backend, separate from input authority. */
data class PhoneInputTraceConfig(val path: String, val run: String = "") {
    companion object {
        private const val PATH = "observation.input.path"
        private const val RUN = "observation.input.run"
        fun endpoint(original: ByteArray, config: PhoneInputTraceConfig?): ByteArray {
            val values = Properties().apply { original.inputStream().use { load(it) } }
            values.remove(PATH); values.remove(RUN)
            if (config != null) { values.setProperty(PATH, config.path); values.setProperty(RUN, config.run) }
            return ByteArrayOutputStream().also { values.store(it, null) }.toByteArray()
        }
        fun fromEndpoint(values: Properties): PhoneInputTraceConfig? = values.getProperty(PATH)?.let {
            PhoneInputTraceConfig(it, values.getProperty(RUN).orEmpty())
        }
    }
}
