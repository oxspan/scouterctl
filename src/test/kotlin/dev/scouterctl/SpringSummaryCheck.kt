package dev.scouterctl

import dev.scouterctl.core.*
import dev.scouterctl.adapters.serialization.*

import kotlinx.serialization.json.*
import scouter.util.HashUtil
import java.nio.file.Files
import java.nio.file.Path

internal class SpringSummaryCheck(private val work: Path, private val directory: Path) {
    fun verify() {
        val online = Files.readAllLines(work.resolve("xlogs.jsonl"))
            .map { json.parseToJsonElement(it).jsonObject }
            .filter { it["type"]?.jsonPrimitive?.content == "record" }
            .map { json.decodeFromJsonElement<XLogRecordDto>(it["data"]!!).toModel() }
        val counts = mapOf("/poc/ok<GET>" to 3, "/poc/sql<GET>" to 3, "/poc/app-error<GET>" to 3,
            "/poc/sql-error<GET>" to 3, "/poc/slow-sql<GET>" to 3, "/error" to 6)
        val summary = summarize("errors", listOf("--include-text"))
        check(summary.basis == "captured-xlogs" && summary.capturedRecords == 21 && summary.capturedErrors == 6)
        check(summary.totalServices == 6 && summary.unavailableProfiles == 0 && summary.unknownServiceNames == 0)
        check(summary.upstreamCoverage == "unknown" && summary.sampling == "unknown")
        check(summary.services.map { it.errors } == listOf(3, 3, 0, 0, 0, 0))
        check(summary.services.map { it.serviceName }.toSet() == counts.keys)
        var details = 0
        counts.forEach { (name, expected) ->
            val hash = HashUtil.hash(name)
            val actual = summary.services.single { it.serviceHash == hash }
            val rows = online.filter { it.serviceHash == hash }
            val errorCount = if (name == "/poc/app-error<GET>" || name == "/poc/sql-error<GET>") 3 else 0
            check(actual.requests == expected && actual.errors == errorCount && !actual.nameConflict)
            check(actual.errorRate == errorCount.toDouble() / expected)
            check(actual.elapsedTotalMs == rows.sumOf { it.elapsed.toLong() })
            check(actual.averageElapsedMs == rows.sumOf { it.elapsed.toLong() }.toDouble() / expected)
            check(actual.maxElapsedMs == rows.maxOf { it.elapsed })
            check(actual.slowest.txid in rows.filter { it.elapsed == actual.maxElapsedMs }.map { it.txid })
            if (name == "/poc/slow-sql<GET>") check(actual.averageElapsedMs >= 300)
            listOfNotNull(actual.slowest, actual.slowestError).forEach { representative ->
                val result = runOfflineCli(work, listOf("inspect", "--directory", directory.toString(),
                    "--txid", representative.txid, "--include-text"))
                check(result.exit == 0 && result.stderr.isEmpty())
                val inspection = json.decodeFromJsonElement<InspectionDto>(result.data()).toModel()
                check(inspection.record.xlog.serviceHash == hash && inspection.record.profile != null)
                details++
            }
        }
        val latency = summarize("elapsed", listOf("--include-text"))
        check(latency.services.map { it.maxElapsedMs } == summary.services.map { it.maxElapsedMs }.sortedDescending())
        val volume = summarize("requests", listOf("--include-text"))
        check(volume.services.first().serviceName == "/error" && volume.services.first().requests == 6)
        val limited = summarize("errors", listOf("--limit", "1", "--include-text"))
        check(limited.truncated && limited.capturedRecords == 21 && limited.capturedErrors == 6 && limited.totalServices == 6)
        check(limited.services == summary.services.take(1))
        val structural = summarize("errors", emptyList())
        check(structural.services.all { it.serviceName == null } && !structural.textIncluded)
        check(details == 8)
        Files.writeString(work.resolve("service-summary-checks.json"), buildJsonObject {
            put("collectorStopped", true); put("credentialsProvided", false); put("summaries", 5)
            put("services", 6); put("capturedXLogs", 21); put("httpRequests", 15)
            put("errorDispatchXLogs", 6); put("errors", 6); put("representativeDetailChecks", details)
            put("countsMatchHttpScenarios", true); put("durationsMatchOnlineXLogs", true)
        }.toString())
        println("PASS: 6 service aggregates match HTTP scenarios and online durations; 5 summaries and 8 representative inspections")
    }

    private fun summarize(sort: String, flags: List<String>): ServiceSummary {
        val result = runOfflineCli(work, listOf("inspect", "summary", "--directory", directory.toString(), "--sort", sort) + flags)
        check(result.exit == 0 && result.stderr.isEmpty())
        Files.writeString(work.resolve("service-summary-$sort-${flags.size}.json"), result.output)
        return json.decodeFromJsonElement<ServiceSummaryDto>(result.data()).toModel()
    }
}
