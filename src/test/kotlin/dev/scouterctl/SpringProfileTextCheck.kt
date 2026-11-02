package dev.scouterctl

import kotlinx.serialization.json.*

class SpringProfileTextCheck(private val cli: AgentCli, private val date: String) {
    fun verify(row: XLogRecord, route: String) {
        val event = cli.run("text-profile-${row.txid}",
            listOf("profile", "get", "--date", date, "--txid", row.txid, "--include-text")).single()
        val profile = json.decodeFromJsonElement<ProfileRecord>(event["data"]!!)
        val transaction = checkNotNull(profile.transaction)
        val serviceName = if (route == "/error") route else "$route<GET>"
        check(profile.txid == row.txid)
        check(transaction.status == "available")
        check(transaction.service == DictionaryText(row.serviceHash, serviceName))
        when (route) {
            "/poc/app-error" -> {
                val error = checkNotNull(transaction.error)
                check(error.hash == row.errorHash && error.status == "resolved")
                check(checkNotNull(error.text).contains("private-application-error-marker"))
            }
            "/poc/sql-error" -> {
                val error = checkNotNull(transaction.error)
                check(error.hash == row.errorHash && error.status == "resolved")
                check(checkNotNull(error.text).contains("Unique index or primary key violation"))
            }
            else -> check(transaction.error == null)
        }
        val sql = profile.steps.mapNotNull { it.sql }
        when (route) {
            "/poc/sql", "/poc/app-error", "/poc/sql-error", "/poc/slow-sql" -> {
                val statement = sql.single()
                val expected = expectedSpringSql(route)
                check(statement.statement == DictionaryText(statement.hash, expected))
                if (route == "/poc/sql-error") check(statement.error == transaction.error)
                else check(statement.error == null)
            }
            else -> check(sql.isEmpty())
        }
        val output = event.toString()
        check(!output.contains("\"param\"") && !output.contains("\"message\""))
        if (route == "/poc/sql") check(!output.contains("private-bind-marker"))
    }
}

internal fun expectedSpringSql(route: String): String = when (route) {
    "/poc/sql-error" -> "insert into fixture_rows(id, marker) values (?, ?)"
    "/poc/slow-sql" -> "call fixture_delay(?)"
    else -> "select marker from fixture_rows where id = ?"
}
