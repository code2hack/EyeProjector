package org.json
class JSONObject {
    private val values = linkedMapOf<String, Any?>()
    fun put(key: String, value: Any?): JSONObject { values[key] = value; return this }
    override fun toString() = values.entries.joinToString(",", "{", "}") { (k,v) -> "\"$k\":" + when(v) {
        null, NULL -> "null"
        is Number, is Boolean, is JSONObject, is JSONArray -> v.toString()
        else -> "\"" + v.toString().replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    } }
    companion object { val NULL = Any() }
}
class JSONArray {
    private val values = mutableListOf<Any?>()
    fun put(value: Any?): JSONArray { values.add(value); return this }
    override fun toString() = values.joinToString(",", "[", "]") { it?.toString() ?: "null" }
}
