package dev.scouterctl.adapters.serialization

import dev.scouterctl.core.ProfileRecord
import kotlinx.serialization.json.*

internal fun ProfileRecord.toLiveJson(): JsonObject {
    val record = json.encodeToJsonElement(toDto()).jsonObject
    val liveSteps = record.getValue("steps").jsonArray.mapIndexed { index, step ->
        val details = steps[index].details ?: return@mapIndexed step
        buildJsonObject {
            step.jsonObject.forEach { (key, value) -> put(key, value) }
            put("startTimeMs", details.startTimeMs)
            put("parent", details.parent)
            details.elapsedMs?.let { put("elapsedMs", it) }
            details.errorHash?.let { put("errorHash", it) }
            details.target?.let { target ->
                putJsonObject("target") {
                    target.address?.let { put("address", it) }
                    target.port?.let { put("port", it) }
                }
            }
        }
    }
    return JsonObject(record + ("steps" to JsonArray(liveSteps)))
}
