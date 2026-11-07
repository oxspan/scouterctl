package dev.scouterctl

import dev.scouterctl.core.*
import dev.scouterctl.application.*
import dev.scouterctl.adapters.files.*
import dev.scouterctl.adapters.serialization.*

import kotlinx.serialization.json.*
import scouter.util.HashUtil
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

internal class SpringComparisonCheck(private val work: Path) {
    private val serviceHash = HashUtil.hash("/poc/comparison<GET>")

    fun capture(cli: AgentCli, httpPort: Int, objectHash: Int) {
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build().use { http ->
            for ((phase, failures) in listOf("before" to 1, "after" to 3)) {
                val from = System.currentTimeMillis()
                val date = Instant.ofEpochMilli(from).atZone(ZoneOffset.UTC).toLocalDate().format(DateTimeFormatter.BASIC_ISO_DATE)
                repeat(4) { index ->
                    val fail = index < failures
                    val delay = if (phase == "before") 0 else 80
                    val uri = URI("http://127.0.0.1:$httpPort/poc/comparison?fail=$fail&delayMs=$delay")
                    val response = http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).GET().build(),
                        HttpResponse.BodyHandlers.ofString())
                    check(response.statusCode() == if (fail) 500 else 200)
                }
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45)
                var window: List<String>
                while (true) {
                    window = listOf("--date", date, "--from", Instant.ofEpochMilli(from).toString(),
                        "--to", Instant.now().plusMillis(1).toString(), "--timezone", "UTC", "--object-hash", objectHash.toString())
                    val rows = cli.run("comparison-$phase-xlogs", listOf("xlog", "search") + window)
                        .filter { it["type"]?.jsonPrimitive?.content == "record" }
                        .map { json.decodeFromJsonElement<XLogRecordDto>(it["data"]!!).toModel() }
                    if (rows.size == 4 + failures) {
                        check(rows.count { it.serviceHash == serviceHash } == 4)
                        check(rows.count { it.errorHash != 0 } == failures)
                        break
                    }
                    check(System.nanoTime() < deadline) { "Comparison $phase XLogs did not arrive" }
                    Thread.sleep(100)
                }
                cli.run("comparison-$phase-capture", listOf("capture", "--include-profiles", "--include-text",
                    "--directory", work.resolve("comparison-$phase").toString()) + window)
            }
        }
    }

    fun verify() {
        val before = work.resolve("comparison-before")
        val after = work.resolve("comparison-after")
        val original = listOf(before, after).associateWith { fingerprints(it) }
        val args = listOf("inspect", "compare", "--before", before.toString(), "--after", after.toString())
        val resolved = runOfflineCli(work, args + "--include-text")
        check(resolved.exit == 0 && resolved.stderr.isEmpty())
        val comparison = json.decodeFromJsonElement<CaptureComparisonDto>(resolved.data()).toModel()
        check(comparison.before.capturedRecords == 5 && comparison.after.capturedRecords == 7)
        check(comparison.before.capturedErrors == 1 && comparison.after.capturedErrors == 3)
        check(comparison.totalServices == 2 && comparison.complete)
        val service = comparison.services.single { it.serviceHash == serviceHash }
        check(service.before!!.serviceName == "/poc/comparison<GET>" && service.after!!.serviceName == "/poc/comparison<GET>")
        check(service.before.requests == 4 && service.after.requests == 4)
        check(service.before.errorRate == 0.25 && service.after.errorRate == 0.75)
        check(service.delta!!.requests == 0 && service.delta.errors == 2 && service.delta.errorRatePercentagePoints == 50.0)
        val rows = listOf(before, after).map { directory ->
            val store = EvidenceStore(directory)
            val manifest = store.load()
            manifest.pages.indices.flatMap { store.readPage(manifest, it) }.filter { it.xlog.serviceHash == serviceHash }
        }
        check(rows.flatten().all { row -> row.profile!!.steps.count { it.sql != null } == 1 })
        val expectedAverageDelta = rows[1].sumOf { it.xlog.elapsed.toLong() } / 4.0 - rows[0].sumOf { it.xlog.elapsed.toLong() } / 4.0
        check(service.delta.averageElapsedMs == expectedAverageDelta)
        check(service.after.averageElapsedMs >= 80)
        val structural = runOfflineCli(work, args)
        check(structural.exit == 0 && !structural.output.contains("/poc/comparison"))
        val identity = runOfflineCli(work, listOf("inspect", "compare", "--before", before.toString(), "--after", before.toString()))
        check(identity.exit == 0)
        check(json.decodeFromJsonElement<CaptureComparisonDto>(identity.data()).toModel().services.all {
            it.delta == ServiceDelta(0, 0, 0.0, 0L, 0.0, 0L)
        })
        check(listOf(before, after).all { fingerprints(it) == original[it] })
        Files.writeString(work.resolve("comparison-result.json"), resolved.output)
        Files.writeString(work.resolve("comparison-checks.json"), buildJsonObject {
            put("collectorStopped", true); put("credentialsProvided", false)
            put("httpRequests", 8); put("beforeXLogs", 5); put("afterXLogs", 7)
            put("beforeErrorRate", 0.25); put("afterErrorRate", 0.75); put("errorRatePercentagePoints", 50)
            put("sqlProfiles", 8); put("offlineComparisons", 3); put("capturesUnchanged", true)
        }.toString())
        println("PASS: two real Spring captures, 25% -> 75% errors (+50 percentage points), 8 SQL profiles, 3 offline comparisons")
    }

    private fun fingerprints(directory: Path): Map<String, List<Byte>> = Files.list(directory).use { paths ->
        paths.toList().associate { it.fileName.toString() to
            java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(it)).toList() }
    }
}
