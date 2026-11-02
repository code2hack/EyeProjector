package org.json

/** JVM boundary for the session's telemetry encoder. It retains values and produces JSON;
 * parsing is outside this boundary and deliberately has no substitute implementation. */
class JSONObject {
    private val values = linkedMapOf<String, Any>()
    fun put(key: String, value: Any?): JSONObject {
        if (value == null) values.remove(key) else { checkedNumber(value); values[key] = value }
        return this
    }
    fun put(key: String, value: Int) = put(key, value as Any)
    fun put(key: String, value: Long) = put(key, value as Any)
    fun put(key: String, value: Double) = put(key, value as Any)
    fun put(key: String, value: Boolean) = put(key, value as Any)
    fun optLong(key: String, fallback: Long): Long = when (val value = values[key]) {
        is Number -> value.toLong()
        is String -> value.toDoubleOrNull()?.toLong() ?: fallback
        else -> fallback
    }
    fun optLong(key: String) = optLong(key, 0)
    override fun toString() = values.entries.joinToString(",", "{", "}") { (key, value) -> quote(key) + ":" + encode(value) }
    companion object {
        @JvmField val NULL: Any = object {
            override fun equals(other: Any?) = other == null || other === this
            override fun hashCode() = 0
            override fun toString() = "null"
        }
    }
}
class JSONArray {
    private val values = mutableListOf<Any>()
    fun put(value: Any?): JSONArray { checkedNumber(value); values.add(value ?: JSONObject.NULL); return this }
    fun put(value: Int) = put(value as Any)
    fun put(value: Long) = put(value as Any)
    fun put(value: Double) = put(value as Any)
    fun put(value: Boolean) = put(value as Any)
    override fun toString() = values.joinToString(",", "[", "]", transform = ::encode)
}
private fun checkedNumber(value: Any?) {
    require(value !is Double || value.isFinite()) { "JSON number must be finite" }
    require(value !is Float || value.isFinite()) { "JSON number must be finite" }
}
private fun encode(value: Any): String = when {
    value === JSONObject.NULL -> "null"
    value is JSONObject || value is JSONArray || value is Number || value is Boolean -> value.toString()
    else -> quote(value.toString())
}
private fun quote(value: String): String = buildString {
    append('"')
    for (c in value) when (c) {
        '"' -> append("\\\""); '\\' -> append("\\\\"); '\n' -> append("\\n"); '\r' -> append("\\r")
        '\t' -> append("\\t"); '\b' -> append("\\b"); '\u000c' -> append("\\f")
        else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
    }
    append('"')
}
