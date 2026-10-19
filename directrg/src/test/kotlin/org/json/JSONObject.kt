package org.json

/** Only records session telemetry in these codec-lifetime tests; no JSON parsing. */
class JSONObject {
    private val values = linkedMapOf<String, Any?>()
    fun put(key: String, value: Any?): JSONObject { values[key] = value; return this }
    fun put(key: String, value: Int) = put(key, value as Any)
    fun put(key: String, value: Long) = put(key, value as Any)
    fun put(key: String, value: Double) = put(key, value as Any)
    fun put(key: String, value: Boolean) = put(key, value as Any)
    fun optLong(key: String, fallback: Long) = (values[key] as? Number)?.toLong() ?: fallback
    override fun toString() = values.toString()
}
class JSONArray { fun put(value: Any?) = this }
