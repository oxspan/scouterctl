package dev.scouterctl.adapters.serialization

import dev.scouterctl.core.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable
data class CollectorTimezoneDto(
    val id: String, val utcOffsetSeconds: Int, val referenceTimeEpochMs: Long,
    val idSource: String = "jvm-property", val offsetSource: String = "client-zone-rules",
)

private fun CollectorTimezone?.toJson(): JsonElement = this?.let {
    json.encodeToJsonElement(CollectorTimezoneDto(it.id, it.utcOffsetSeconds, it.referenceTimeEpochMs))
} ?: JsonNull

fun QueryTimezoneCheck.toJson(): JsonObject = buildJsonObject {
    put("status", if (collectorTimezone == null) "unavailable" else "reported-offset-match")
    put("collectorTimezone", collectorTimezone.toJson())
}

fun ServerInfo.toLiveJson(timezone: CollectorTimezone?): JsonObject =
    JsonObject(json.encodeToJsonElement(toDto()).jsonObject + ("timezone" to timezone.toJson()))

fun CollectorStatus.toLiveJson(info: ServerInfo, timezone: CollectorTimezone?): JsonObject =
    JsonObject(json.encodeToJsonElement(toDto(info)).jsonObject + ("collector" to info.toLiveJson(timezone)))
