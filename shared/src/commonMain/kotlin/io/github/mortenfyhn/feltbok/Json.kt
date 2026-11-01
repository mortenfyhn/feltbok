package io.github.mortenfyhn.feltbok

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

// org.json-style field access over kotlinx.serialization's JSON tree. The app's files and the API
// responses were parsed with Android's org.json, so these keep its leniency: numbers coerce
// (3.0 reads as a Long, "12" as a number), optional getters fall back to a default, and required
// ones throw. Unlike Android's optString, JSON null reads as absent, not as the string "null".

private fun JsonObject.prim(key: String): JsonPrimitive? = (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }

private fun JsonPrimitive.asLong(): Long? = longOrNull ?: doubleOrNull?.toLong()

private fun missing(key: String) = IllegalArgumentException("missing or mistyped \"$key\"")

internal fun JsonObject.has(key: String) = prim(key) != null

internal fun JsonObject.getLong(key: String): Long = prim(key)?.asLong() ?: throw missing(key)

internal fun JsonObject.optLong(key: String, fallback: Long = 0L): Long = prim(key)?.asLong() ?: fallback

internal fun JsonObject.getInt(key: String): Int = getLong(key).toInt()

internal fun JsonObject.optInt(key: String, fallback: Int = 0): Int = optLong(key, fallback.toLong()).toInt()

internal fun JsonObject.getDouble(key: String): Double = prim(key)?.doubleOrNull ?: throw missing(key)

internal fun JsonObject.optDouble(key: String, fallback: Double = Double.NaN): Double = prim(key)?.doubleOrNull ?: fallback

internal fun JsonObject.getString(key: String): String = prim(key)?.content ?: throw missing(key)

internal fun JsonObject.optString(key: String, fallback: String = ""): String = prim(key)?.content ?: fallback

internal fun JsonObject.optBoolean(key: String, fallback: Boolean = false): Boolean = prim(key)?.booleanOrNull ?: fallback

internal fun JsonObject.optArray(key: String): JsonArray? = this[key] as? JsonArray

internal fun JsonObject.optObject(key: String): JsonObject? = this[key] as? JsonObject

internal fun parseJson(text: String): JsonElement = Json.parseToJsonElement(text)

/** "[[lon,lat],...]" (WGS84; a JSON array, or a string holding one) -> [lat,lon] vertices, matching
 *  our polygon convention. Empty when missing or malformed. */
internal fun lonLatRingToVertices(ring: JsonElement?): List<DoubleArray> {
    val a = when {
        ring is JsonArray -> ring
        ring is JsonPrimitive && ring !is JsonNull && ring.content.isNotBlank() ->
            runCatching { parseJson(ring.content).jsonArray }.getOrNull() ?: return emptyList()
        else -> return emptyList()
    }
    return runCatching {
        a.map { val p = it.jsonArray; doubleArrayOf(p[1].jsonPrimitive.double, p[0].jsonPrimitive.double) }
    }.getOrDefault(emptyList())
}
