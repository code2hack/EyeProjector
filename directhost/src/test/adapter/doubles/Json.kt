package org.json
class JSONObject(val json: String) {
 private fun value(key: String) = json.substringAfter("\"$key\":", "").substringBefore(',').substringBefore('}').trim().trim('"')
 fun getString(key: String): String = value(key)
 fun optString(key: String): String = value(key)
 fun optBoolean(key: String): Boolean = value(key) == "true"
}
