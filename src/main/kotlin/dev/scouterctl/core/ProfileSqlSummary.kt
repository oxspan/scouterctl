package dev.scouterctl.core

data class SqlAggregate(
    val hash: Int, val statement: DictionaryText?, val statementConflict: Boolean,
    val count: Int, val elapsedTotalMs: Long, val maxElapsedMs: Int, val errors: Int,
)

data class ProfileSqlSummary(
    val txid: String, val completeness: ProfileCompleteness, val textIncluded: Boolean,
    val totalSteps: Int, val sqlSteps: Int, val nonSqlSteps: Int, val groups: List<SqlAggregate>,
)
