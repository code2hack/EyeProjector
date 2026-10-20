package org.json

class JSONObject {
    private val values = linkedMapOf<String, Any?>()
    fun put(key: String, value: Any?): JSONObject { values[key] = value; return this }
    fun get(key: String): Any = values[key] ?: error("No key $key")
    fun opt(key: String) = values[key]
    fun has(key: String) = values.containsKey(key)
    fun getJSONObject(key: String) = get(key) as JSONObject
    fun optJSONObject(key: String) = values[key] as? JSONObject
    fun getJSONArray(key: String) = get(key) as JSONArray
    fun getString(key: String) = get(key) as String
    fun getInt(key: String) = (get(key) as Number).toInt()
    fun getLong(key: String) = (get(key) as Number).toLong()
    fun getBoolean(key: String) = get(key) as Boolean
    fun optLong(key: String, fallback: Long) = (values[key] as? Number)?.toLong() ?: fallback
    fun optInt(key: String, fallback: Int = 0) = (values[key] as? Number)?.toInt() ?: fallback
    fun optString(key: String) = values[key]?.toString() ?: ""
    fun optBoolean(key: String, fallback: Boolean = false) = values[key] as? Boolean ?: fallback
    override fun toString() = values.toString()
    companion object { val NULL = Any() }
}
class JSONArray(values: Collection<Any?> = emptyList()) {
    private val values = values.toMutableList()
    fun put(value: Any?): JSONArray { values += value; return this }
    fun length() = values.size
    fun get(index: Int) = values[index]
    override fun toString() = values.toString()
}
