package io.nekohasekai.sagernet.outbound.json

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

/**
 * INPUT side only: parses JSON text through org.json and converts it into [JsonObject]/[JsonArray].
 * org.json output (`toString()`) must never be used for contract strings because it escapes `/`.
 */
object JsonInput {

    /** Collections nested deeper are refused, as in the JSON editor (JsonTree): org.json reads them by recursion. */
    const val MAX_DEPTH = 96

    /** QString2QJsonObject (Utils.cpp:100-104): an empty object on any failure or when the text is not an object. */
    @JvmStatic
    fun parseObject(text: String): JsonObject = parseObjectOrNull(text) ?: JsonObject()

    @JvmStatic
    fun parseObjectOrNull(text: String): JsonObject? = try {
        // JSONObject(text) is JSONTokener(text).nextValue() checked to be an object.
        (DepthTokener(text).nextValue() as? JSONObject)?.let(::fromOrgJson)
    } catch (e: Exception) {
        null
    }

    /** The top-level value of [text] (object, array, string, number, boolean or [JsonNull]); null when unparsable. */
    @JvmStatic
    fun parseValue(text: String): Any? = try {
        fromOrgJsonValue(DepthTokener(text).nextValue())
    } catch (e: Exception) {
        null
    }

    /** Keys are inserted in sorted order because a QJsonObject is a sorted map and the desktop iterates it as such. */
    @JvmStatic
    fun fromOrgJson(obj: JSONObject): JsonObject {
        val out = JsonObject()
        val keys = ArrayList<String>()
        val it = obj.keys()
        while (it.hasNext()) keys.add(it.next())
        keys.sort()
        for (key in keys) out[key] = fromOrgJsonValue(obj.opt(key))
        return out
    }

    @JvmStatic
    fun fromOrgJson(arr: JSONArray): JsonArray {
        val out = JsonArray()
        for (i in 0 until arr.length()) out.add(fromOrgJsonValue(arr.opt(i)))
        return out
    }

    @JvmStatic
    fun fromOrgJsonValue(value: Any?): Any = when {
        value == null || value === JSONObject.NULL -> JsonNull
        value is JSONObject -> fromOrgJson(value)
        value is JSONArray -> fromOrgJson(value)
        else -> JsonValues.normalize(value)
    }

    /** JSONTokener with [MAX_DEPTH]: a nested value fails before the recursion can exhaust the thread's stack. */
    private class DepthTokener(text: String) : JSONTokener(text) {
        /** The collections around the next value. */
        private var level = 0

        override fun nextValue(): Any? {
            if (level == MAX_DEPTH) {
                // Inside the deepest collection allowed a scalar may follow, another collection may not.
                val c = nextClean()
                if (c == '{' || c == '[') throw JSONException("The document is nested too deeply")
                if (c != '\u0000') back()
            }
            level++
            try {
                return super.nextValue()
            } finally {
                level--
            }
        }
    }
}
