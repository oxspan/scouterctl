package dev.scouterctl.application

import dev.scouterctl.core.*

fun summarizeSql(profile: ProfileRecord, includeText: Boolean): ProfileSqlSummary {
    val sql = profile.steps.mapNotNull { it.sql }
    val groups = sql.groupBy { it.hash }.map { (hash, steps) ->
        val texts = if (includeText) steps.mapNotNull { it.statement?.takeIf { text -> text.hash == hash }?.text }.distinct()
            else emptyList()
        SqlAggregate(hash, if (includeText) DictionaryText(hash, texts.singleOrNull()) else null, texts.size > 1,
            steps.size, steps.sumOf { it.elapsedMs.toLong() }, steps.maxOf { it.elapsedMs }, steps.count { it.errorHash != 0 })
    }.sortedWith(compareByDescending<SqlAggregate> { it.elapsedTotalMs }.thenBy { it.hash })
    return ProfileSqlSummary(profile.txid, profile.completeness, includeText, profile.steps.size, sql.size,
        profile.steps.size - sql.size, groups)
}
