package org.json
/** JSON output double for the actual Session telemetry; no display/power observations are invented. */
class JSONObject {
    private val fields = linkedMapOf<String, Any?>()
    fun put(key: String, value: Any?): JSONObject { fields[key] = value; return this }
    override fun toString(): String = fields.entries.joinToString(",", "{", "}") { (key, value) ->
        "\"$key\":" + when (value) {
            null, NULL -> "null"
            is String -> "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""
            else -> value.toString()
        }
    }
    companion object { val NULL = Any() }
}
