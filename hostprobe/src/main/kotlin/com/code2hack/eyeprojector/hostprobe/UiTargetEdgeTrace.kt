package com.code2hack.eyeprojector.hostprobe

import java.io.Closeable
import java.io.File
import java.io.FileOutputStream

/** Controlled fixture only. Append each original row; latest counters remain a separate file. */
internal class UiTargetEdgeTrace(val file: File, private val runId: String, val instance: String,
                                 private val encode: (Map<String, Any?>) -> String) : Closeable {
    private val writer = FileOutputStream(file, true).bufferedWriter(Charsets.UTF_8)
    private var closed = false
    var sequence = 0L; private set
    var failures = 0L; private set
    var failureType: String? = null; private set

    fun append(kind: String, caseId: String, phase: String, fields: Map<String, Any?>) {
        if (closed) return
        try {
            val row = fields + mapOf("schema" to "eyeprojector.ui-target-edges.v1", "runId" to runId,
                "instance" to instance, "sequence" to ++sequence, "kind" to kind,
                "caseId" to caseId, "phase" to phase)
            writer.write(encode(row)); writer.newLine(); writer.flush()
        } catch (error: Exception) { failures++; failureType = error.javaClass.simpleName }
    }

    override fun close() {
        if (closed) return
        closed = true
        try { writer.close() }
        catch (error: Exception) { failures++; failureType = error.javaClass.simpleName }
    }
}
