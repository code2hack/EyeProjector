package org.json
/** Minimal input/output API double; no Android/device observations are invented. */
class JSONObject(private val json: String = "") {
 private val fields = linkedMapOf<String, Any?>()
 private fun value(key: String): String {
  val match = Regex(""""${Regex.escape(key)}"\s*:\s*("[^"]*"|[^,}]+)""").findAll(json).lastOrNull()
  return match?.groupValues?.get(1)?.trim()?.trim('"').orEmpty()
 }
 fun getString(key: String): String = value(key)
 fun optString(key: String): String = value(key)
 fun optBoolean(key: String): Boolean = value(key) == "true"
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
