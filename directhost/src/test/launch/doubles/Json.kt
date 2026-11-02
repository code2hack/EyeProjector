package org.json
class JSONObject {
    private val values = linkedMapOf<String, Any?>()
    fun put(key: String, value: Any?): JSONObject { values[key] = value; return this }
    fun put(key: String, value: Int) = put(key, value as Any)
    fun put(key: String, value: Long) = put(key, value as Any)
    fun put(key: String, value: Double) = put(key, value as Any)
    fun put(key: String, value: Boolean) = put(key, value as Any)
    override fun toString() = values.entries.joinToString(",", "{", "}") { (k,v) -> "\"$k\":" + when(v) {
        null, NULL -> "null"
        is Number, is Boolean, is JSONObject, is JSONArray -> v.toString()
        else -> "\"" + v.toString().replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    } }
    companion object { @JvmField val NULL = Any() }
}
class JSONArray {
    private val values = mutableListOf<Any?>()
    fun put(value: Any?): JSONArray { values.add(value); return this }
    fun put(value: Int) = put(value as Any)
    fun put(value: Long) = put(value as Any)
    fun put(value: Double) = put(value as Any)
    fun put(value: Boolean) = put(value as Any)
    override fun toString() = values.joinToString(",", "[", "]") { it?.toString() ?: "null" }
}
