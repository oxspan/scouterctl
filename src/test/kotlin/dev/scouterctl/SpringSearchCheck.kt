package dev.scouterctl

import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path

internal class SpringSearchCheck(private val work: Path, private val directory: Path) {
    fun verify() {
        val checks = json.parseToJsonElement(Files.readString(work.resolve("profile-checks.json"))).jsonArray.map { it.jsonObject }
        fun ids(route: String) = checks.filter { it["route"]!!.jsonPrimitive.content == route }
            .map { it["txid"]!!.jsonPrimitive.content }.toSet()
        val slowIds = ids("/poc/slow-sql")
        val errorIds = ids("/poc/app-error") + ids("/poc/sql-error")
        val top = search("slow", listOf("--min-elapsed-ms", "300", "--limit", "3", "--service-name", "/poc/slow-sql<GET>", "--include-text"))
        check(top.records.map { it.xlog.txid }.toSet() == slowIds && slowIds.size == 3)
        check(top.records.map { it.xlog.elapsed } == top.records.map { it.xlog.elapsed }.sortedDescending())
        check(top.summary.capturedRecords == 21 && top.summary.capturedErrors == 6 && top.summary.matchedRecords == 3)
        top.records.forEach { row ->
            val detail = inspect(row.xlog.txid)
            val sql = checkNotNull(detail.record.profile).steps.mapNotNull { it.sql }.single()
            check(sql.elapsedMs >= 300 && sql.statement?.text == "call fixture_delay(?)")
        }
        val errors = search("errors", listOf("--errors-only"))
        check(errors.records.map { it.xlog.txid }.toSet() == errorIds && errorIds.size == 6)
        check(errors.summary.matchedErrors == 6 && errors.records.all { it.service == null })
        errors.records.forEach { row ->
            val message = checkNotNull(inspect(row.xlog.txid).record.profile?.transaction?.error?.text)
            check(message.contains(if (row.xlog.txid in ids("/poc/app-error")) "private-application-error-marker" else "Unique index or primary key violation"))
        }
        val allTop = search("top", listOf("--limit", "3"))
        check(allTop.records.size == 3 && allTop.truncated && allTop.summary.matchedRecords == 21)
        val online = Files.readAllLines(work.resolve("xlogs.jsonl")).map { json.parseToJsonElement(it).jsonObject }
            .filter { it["type"]?.jsonPrimitive?.content == "record" }.map { json.decodeFromJsonElement<XLogRecord>(it["data"]!!) }
        check(allTop.records.map { it.xlog.elapsed } == online.map { it.elapsed }.sortedDescending().take(3))
        val service = search("service", listOf("--service-name", "/poc/sql<GET>", "--include-text"))
        check(service.records.map { it.xlog.txid }.toSet() == ids("/poc/sql"))
        Files.writeString(work.resolve("search-summary.json"), buildJsonObject {
            put("collectorStoppedBeforeSearch", true); put("credentialsProvided", false)
            put("searches", 4); put("slowSqlFound", 3); put("errorRequestsFound", 6)
            put("searchToInspectChecks", 9); put("topMatchesOnlineDurations", true)
        }.toString())
        println("PASS: offline search finds 3 slow SQL requests and 6 errors; 9 search-to-inspect detail checks")
    }

    private fun search(name: String, filters: List<String>): EvidenceSearch {
        val result = runOfflineCli(work, listOf("inspect", "search", "--directory", directory.toString()) + filters)
        check(result.exit == 0 && result.stderr.isEmpty())
        Files.writeString(work.resolve("search-$name.json"), result.output)
        return json.decodeFromJsonElement(result.data())
    }

    private fun inspect(txid: String): Inspection {
        val result = runOfflineCli(work, listOf("inspect", "--directory", directory.toString(), "--txid", txid, "--include-text"))
        check(result.exit == 0 && result.stderr.isEmpty())
        return json.decodeFromJsonElement(result.data())
    }
}
