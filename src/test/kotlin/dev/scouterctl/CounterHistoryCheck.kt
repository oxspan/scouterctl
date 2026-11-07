package dev.scouterctl

import dev.scouterctl.adapters.serialization.json
import dev.scouterctl.core.Endpoint
import kotlinx.serialization.json.*
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

internal fun verifyCounterHistory(work: Path, endpoint: Endpoint, password: String, objHash: Int, httpPort: Int,
    stopService: () -> Unit, stopCollector: () -> Unit, startCollector: () -> Unit) {
    val settings = listOf("conf/scouter.conf", "conf/account.xml", "agent.conf").associateWith { Files.readAllBytes(work.resolve(it)).toList() }
    HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build().use { http ->
        for (route in listOf("/poc/ok", "/poc/sql", "/poc/error", "/poc/slow")) {
            val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$httpPort$route")).timeout(Duration.ofSeconds(10)).GET().build()
            check(http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() == if (route == "/poc/error") 500 else 200)
        }
    }
    var midnight = utcMidnight(System.currentTimeMillis())
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45)
    var available = false
    while (!available && System.nanoTime() < deadline) {
        midnight = utcMidnight(System.currentTimeMillis())
        available = counterHistoryReference(endpoint, password, objHash, "HeapUsed", midnight, System.currentTimeMillis() + 1).size >= 2
        if (!available) Thread.sleep(200)
    }
    check(available) { "Stored counter samples did not arrive" }
    stopService()
    Thread.sleep(300)
    val to = minOf(System.currentTimeMillis() + 1, midnight + 86_400_000L)
    val reference = counterHistoryReference(endpoint, password, objHash, "HeapUsed", midnight, to)
    check(reference.size >= 2 && reference.all { it.value.toBigDecimal() > java.math.BigDecimal.ZERO })
    Files.writeString(work.resolve("counter-history-reference.tsv"), reference.joinToString("") { "${it.time}\t${it.value}\n" })
    val cli = AgentCli(work, endpoint.port, password)
    var commands = 0
    fun run(label: String, args: List<String>, expected: Int = 0): List<JsonObject> {
        commands++
        return cli.run("history-$label", args + listOf("--no-input"), expected)
    }
    val target = listOf("--object-hash", objHash.toString(), "--counter", "HeapUsed")
    val date = Instant.ofEpochMilli(midnight).atZone(ZoneOffset.UTC).toLocalDate().format(DateTimeFormatter.BASIC_ISO_DATE)
    fun window(from: Long, end: Long) = listOf("--date", date, "--from", Instant.ofEpochMilli(from).toString(),
        "--to", Instant.ofEpochMilli(end).toString(), "--timezone", "UTC")
    val history = listOf("counter", "history") + target + window(midnight, to)
    val full = run("samples", history)
    check(full.first()["schemaVersion"] == JsonPrimitive("scouterctl.counter-stream/v1"))
    check(full.first()["counter"]!!.jsonObject["unit"] == JsonPrimitive("MB"))
    check(full.first()["missingValues"] == JsonPrimitive("omitted") && full.first()["intervalMs"] == JsonNull)
    check(sampleRows(full) == reference)
    val boundary = reference.first().time
    val exact = run("boundary", listOf("counter", "history") + target + window(boundary, boundary + 1))
    check(sampleRows(exact) == reference.filter { it.time == boundary })
    val upper = reference.first { it.time > boundary }.time
    val prefix = run("exclusive-to", listOf("counter", "history") + target + window(boundary, upper))
    check(sampleRows(prefix) == reference.filter { it.time >= boundary && it.time < upper })
    val empty = run("empty", listOf("counter", "history") + target + window(boundary + 1, boundary + 2))
    check(reference.none { it.time in boundary + 1 until boundary + 2 } && sampleRows(empty).isEmpty())
    val missing = run("missing-counter", history.map { if (it == "HeapUsed") "scouterctl_missing_counter" else it })
    check(sampleRows(missing).isEmpty() && missing.first()["counter"]!!.jsonObject["unit"] == JsonNull)
    run("missing-object", history.map { if (it == objHash.toString()) "0" else it }, 7)
    AgentCli(work, endpoint.port, "wrong-$password").run("history-bad-auth", history + "--no-input", 3)
    commands++
    val directory = work.resolve("지표 자료")
    val export = listOf("counter", "export") + target + listOf("--from", Instant.ofEpochMilli(midnight - 30_000).toString(),
        "--to", Instant.ofEpochMilli(to).toString(), "--timezone", "UTC", "--chunk-minutes", "1440", "--directory", directory.toString())
    val first = run("export-first", export + listOf("--max-chunks", "1"), 8).single()["data"]!!.jsonObject
    check(first["executionComplete"] == JsonPrimitive(false))
    check(first["chunks"]!!.jsonArray.single().jsonObject["status"] == JsonPrimitive("object_unavailable"))
    val checkpoint = counterFiles(directory)
    val recovery = System.getenv("SCOUTER_COUNTER_RECOVERY") == "true"
    if (recovery) {
        // Collector's MemTimeBlock flushes every 4s on a 1s scheduler; a readable sample may still be memory-only.
        Thread.sleep(6000)
        stopCollector()
        run("offline", export + "--resume", 5)
        check(counterFiles(directory) == checkpoint)
        Files.writeString(directory.resolve("chunk-000002.jsonl.partial"), "incomplete")
        Files.writeString(directory.resolve("chunk-000002.jsonl"), "uncommitted")
        startCollector()
        val retained = counterHistoryReference(endpoint, password, objHash, "HeapUsed", midnight, to)
        check(retained == reference) { "Collector did not retain the reference samples after restart" }
        Files.writeString(work.resolve("counter-history-reference-after-restart.tsv"), retained.joinToString("") { "${it.time}\t${it.value}\n" })
    }
    val complete = run("export-resume", export + listOf("--resume", "--max-chunks", "10")).single()["data"]!!.jsonObject
    check(complete["executionComplete"] == JsonPrimitive(true))
    val chunks = complete["chunks"]!!.jsonArray.map { it.jsonObject }
    check(chunks.size == 2)
    check(Files.readAllBytes(directory.resolve("chunk-000001.jsonl")).toList() == checkpoint.getValue("chunk-000001.jsonl"))
    val captured = chunks.flatMap { chunk ->
        val file = chunk["file"]!!.jsonObject
        val content = Files.readAllBytes(directory.resolve(file["name"]!!.jsonPrimitive.content))
        check(hash(content) == file["sha256"]!!.jsonPrimitive.content)
        val rows = content.toString(Charsets.UTF_8).lineSequence().filter(String::isNotEmpty).map { json.parseToJsonElement(it).jsonObject }.toList()
        check(rows.size == file["records"]!!.jsonPrimitive.int)
        if (chunk["status"] == JsonPrimitive("available")) check(chunk["counter"]!!.jsonObject["unit"] == JsonPrimitive("MB"))
        rows.map(::sampleRow)
    }
    check(captured == reference)
    check(!Files.exists(directory.resolve("chunk-000002.jsonl.partial")))
    val finished = counterFiles(directory)
    run("export-repeat", export + "--resume")
    check(counterFiles(directory) == finished)
    if (recovery) {
        run("query-mismatch", export.map { if (it == "1440") "60" else it } + "--resume", 2)
        check(counterFiles(directory) == finished)
        val file = directory.resolve(chunks.last()["file"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        Files.writeString(file, "corrupt")
        val damaged = counterFiles(directory)
        run("corrupt", export + "--resume", 10)
        check(counterFiles(directory) == damaged)
        Files.write(file, finished.getValue(file.fileName.toString()).toByteArray())
    }
    for ((name, bytes) in settings) check(Files.readAllBytes(work.resolve(name)).toList() == bytes)
    val version = complete["source"]!!.jsonObject["version"]!!.jsonPrimitive.content
    Files.writeString(work.resolve("counter-history-summary.json"), buildJsonObject {
        put("collectorVersion", version); put("agentJava", Files.readString(work.resolve("http-port.java-version")))
        put("httpRequests", 4); put("historySamples", reference.size); put("capturedSamples", captured.size)
        put("chunks", chunks.size); put("commands", commands); put("heapUnit", "MB")
        put("halfOpenBoundary", true); put("emptyWindow", true); put("missingCounter", true)
        put("referenceMatch", true); put("checkpointBytesUnchanged", true); put("completedResumeUnchanged", true)
        put("configurationUnchanged", true); put("recoveryChecked", recovery)
    }.toString())
    println("PASS: counter history/export, $commands CLI commands, ${reference.size} stored samples match reference, 2 chunks; recovery=$recovery")
}

private fun utcMidnight(time: Long) = Instant.ofEpochMilli(time).atZone(ZoneOffset.UTC).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
private fun sampleRow(row: JsonObject): StoredCounterSample {
    check(row["availability"] == JsonPrimitive("available"))
    return StoredCounterSample(row["time"]!!.jsonPrimitive.long, row["value"]!!.jsonPrimitive.content)
}
private fun sampleRows(events: List<JsonObject>): List<StoredCounterSample> {
    check(events.last()["type"] == JsonPrimitive("complete") && events.last()["executionComplete"] == JsonPrimitive(true))
    val rows = events.filter { it["type"] == JsonPrimitive("record") }.map { sampleRow(it["data"]!!.jsonObject) }
    check(events.last()["records"]!!.jsonPrimitive.int == rows.size)
    return rows
}
private fun counterFiles(directory: Path): Map<String, List<Byte>> = Files.list(directory).use { paths ->
    paths.filter { it.fileName.toString() != ".lock" }.toList().associate { it.fileName.toString() to Files.readAllBytes(it).toList() }
}
private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
