package dev.scouterctl

import dev.scouterctl.adapters.collector.CollectorSource
import dev.scouterctl.adapters.files.CaptureStore
import dev.scouterctl.adapters.files.EvidenceStore
import dev.scouterctl.adapters.serialization.*
import dev.scouterctl.bootstrap.BuildInfo
import dev.scouterctl.core.*
import kotlinx.serialization.json.*
import scouter.net.RequestCmd
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal fun verifySpringRecovery(
    work: Path, port: Int, password: String, httpPort: Int, window: List<String>, expected: List<XLogRecord>,
    stopCollector: () -> Unit, restartCollector: () -> Unit,
) {
    val query = window.toMutableList().apply { set(indexOf("--page-size") + 1, "2") }
    val date = query[query.indexOf("--date") + 1]
    val profiles = CollectorSource.connect(Endpoint("127.0.0.1", port, "poc", 1500), password, BuildInfo.version).use { source ->
        expected.associate { it.txid to source.profile(date, it.txid.toLong()) }
    }
    val results = mutableListOf<JsonObject>()
    for (withProfiles in listOf(false, true)) {
        val label = if (withProfiles) "recovery-profiles" else "recovery-v1"
        val directory = work.resolve(label)
        val command = if (withProfiles) RequestCmd.TRANX_PROFILE else RequestCmd.TRANX_LOAD_TIME_GROUP_V2
        RecoveryProxy(port, command, if (withProfiles) 4 else 2).use { proxy ->
            val cli = AgentCli(work, proxy.port, password)
            val args = listOf("capture", "--directory", directory.toString(), "--timeout-ms", "15000") + query +
                if (withProfiles) listOf("--include-profiles") else emptyList()
            val executor = Executors.newSingleThreadExecutor()
            val interrupted = executor.submit<List<JsonObject>> { cli.run("$label-interrupted", args, 10) }
            try {
                proxy.awaitPaused()
                val checkpoint = readRecoveryCapture(directory, withProfiles)
                check(!checkpoint.complete && checkpoint.rows.size == 2)
                val savedPages = snapshotRecoveryPages(directory)
                stopCollector()
                val unavailable = AgentCli(work, port, password).run("$label-down", listOf("doctor"), 5).single()
                check(unavailable["error"]!!.jsonObject["code"]!!.jsonPrimitive.content == "CONNECTION_FAILED")
                // These requests are outside the frozen query and are not promised recoverable.
                HttpClient.newHttpClient().use { http ->
                    repeat(2) {
                        val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$httpPort/poc/ok"))
                            .timeout(Duration.ofSeconds(5)).GET().build()
                        check(http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() == 200)
                    }
                }
                proxy.release()
                val error = interrupted.get(20, TimeUnit.SECONDS).single()
                check(error["error"]!!.jsonObject["code"]!!.jsonPrimitive.content == "IO_ERROR")
                check(proxy.transportFailures == 1)
                check(readRecoveryCapture(directory, withProfiles) == checkpoint)
                check(snapshotRecoveryPages(directory) == savedPages)
                if (withProfiles) check(EvidenceStore(directory).load().lastFailure == ErrorCode.IO_ERROR)
                restartCollector()
                cli.run("$label-resumed", args + "--resume")
                val completed = readRecoveryCapture(directory, withProfiles)
                check(completed.complete)
                check(completed.rows.size == expected.size)
                check(completed.rows.map { it.txid }.toSet().size == expected.size)
                check(completed.rows.associateBy { it.txid } == expected.associateBy { it.txid })
                if (withProfiles) {
                    val store = EvidenceStore(directory)
                    val manifest = store.load()
                    check(manifest.lastFailure == null && manifest.complete)
                    store.forEachRecord(manifest) { check(it.profile == profiles[it.xlog.txid]) }
                }
                val completeBytes = snapshotRecoveryFiles(directory)
                cli.run("$label-repeat-resume", args + "--resume")
                check(snapshotRecoveryFiles(directory) == completeBytes)
                results += buildJsonObject {
                    put("schema", if (withProfiles) EvidenceStore(directory).load().schemaVersion else CaptureStore(directory).load().schemaVersion)
                    put("checkpointRecords", 2); put("recoveredRecords", completed.rows.size)
                    put("uniqueRecords", completed.rows.map { it.txid }.toSet().size)
                    put("checkpointUnchanged", true); put("completedResumeUnchanged", true)
                    put("profilesMatchBeforeRestart", withProfiles); put("transportFailures", proxy.transportFailures)
                    put("requestsDuringOutage", 2); put("outageTrafficCoverage", "unknown")
                }
            } finally {
                proxy.release()
                executor.shutdownNow()
                check(executor.awaitTermination(25, TimeUnit.SECONDS))
            }
        }
    }
    Files.writeString(work.resolve("recovery-summary.json"), JsonArray(results).toString())
    println("PASS: real Collector stop/restart, transaction/profile capture checkpoints, exact recovered IDs/profiles and idempotent resume")
}

internal data class RecoveryCapture(val rows: List<XLogRecord>, val complete: Boolean, val cursor: Cursor)

internal fun readRecoveryCapture(directory: Path, withProfiles: Boolean): RecoveryCapture = if (withProfiles) {
    val store = EvidenceStore(directory)
    val manifest = store.load()
    val rows = mutableListOf<XLogRecord>()
    store.forEachRecord(manifest) { rows += it.xlog }
    RecoveryCapture(rows, manifest.executionComplete, manifest.cursor)
} else {
    val store = CaptureStore(directory)
    val manifest = store.load()
    store.validate(manifest)
    val rows = manifest.files.flatMap { page ->
        Files.readAllLines(directory.resolve(page.name)).map { json.decodeFromString<XLogRecordDto>(it).toModel() }
    }
    RecoveryCapture(rows, manifest.executionComplete, manifest.cursor)
}

internal fun snapshotRecoveryPages(directory: Path): Map<String, String> = snapshotRecoveryFiles(directory)
    .filterKeys { it.endsWith(".jsonl") }

internal fun snapshotRecoveryFiles(directory: Path): Map<String, String> = Files.list(directory).use { files ->
    files.filter { Files.isRegularFile(it) }.toList().associate { it.fileName.toString() to Files.readString(it) }
}
