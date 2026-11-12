package dev.scouterctl.adapters.serialization

import dev.scouterctl.core.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable
data class SqlAggregateDto(
    val hash: Int, val statement: DictionaryTextDto?, val statementConflict: Boolean,
    val count: Int, val elapsedTotalMs: Long, val maxElapsedMs: Int, val errors: Int,
)

@Serializable
data class ProfileSqlSummaryDto(
    val txid: String, val completeness: ProfileCompletenessDto, val textIncluded: Boolean,
    val totalSteps: Int, val sqlSteps: Int, val nonSqlSteps: Int, val groups: List<SqlAggregateDto>,
    val schemaVersion: String = "scouterctl.profile-sql-summary/v1", val basis: String = "decoded-profile-steps",
)

fun ProfileSqlSummary.toDto(): ProfileSqlSummaryDto = ProfileSqlSummaryDto(txid,
    when (completeness) { ProfileCompleteness.UNKNOWN -> ProfileCompletenessDto.UNKNOWN; ProfileCompleteness.TRUNCATED -> ProfileCompletenessDto.TRUNCATED },
    textIncluded, totalSteps, sqlSteps, nonSqlSteps, groups.map {
        SqlAggregateDto(it.hash, it.statement?.toDto(), it.statementConflict, it.count, it.elapsedTotalMs, it.maxElapsedMs, it.errors)
    })

fun Inspection.toSqlSummaryJson(summary: ProfileSqlSummary?): JsonObject {
    val base = json.encodeToJsonElement(copy(record = record.copy(profile = null)).toDto()).jsonObject
    val record = base.getValue("record").jsonObject
    val grouped = summary?.let { json.encodeToJsonElement(it.toDto()) } ?: JsonNull
    return JsonObject(base + ("record" to JsonObject(record + ("profile" to grouped))))
}
