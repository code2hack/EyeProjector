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
/** Flat synthetic chosen-app arrays only; not Android JSON/platform parsing proof. */
class JSONArray(json:String?=null) {
 private val values=mutableListOf<JSONObject>()
 init { if(json!=null){require(json.startsWith("["));Regex("\\{[^{}]*\\}").findAll(json).forEach{values.add(JSONObject(it.value))}} }
 fun length()=values.size
 fun getJSONObject(index:Int)=values[index]
 fun put(value:JSONObject):JSONArray{values.add(value);return this}
 override fun toString()=values.joinToString(",","[","]")
}
