package dev.scouterctl

import dev.scouterctl.adapters.files.CounterCaptureStore
import dev.scouterctl.adapters.serialization.*
import dev.scouterctl.core.*
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.*
import scouter.net.RequestCmd
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private fun epoch(value: String) = Instant.parse(value).toEpochMilli()
private val exportArgs = listOf("counter", "export", "--object-hash", "42", "--counter", "HeapUsed",
    "--from", "2026-01-01T23:30:00Z", "--to", "2026-01-02T01:30:00Z", "--timezone", "UTC")
private fun snapshot(directory: Path) = Files.list(directory).use { paths -> paths.filter { it.fileName.toString() != ".lock" }
    .toList().associate { it.fileName.toString() to Files.readString(it) } }
private fun points(directory: Path): List<CounterPointDto> = Files.list(directory).use { paths ->
    paths.filter { it.fileName.toString().matches(Regex("chunk-[0-9]{6}.jsonl")) }.sorted().toList()
        .flatMap { Files.readAllLines(it).map { line -> json.decodeFromString<CounterPointDto>(line) } }
}

class CounterExportTest : FunSpec({
    test("export plans contiguous local-day windows across spring and autumn DST") {
        for ((from, to, expected) in listOf(
            Triple("2026-03-08T05:00:00Z", "2026-03-09T04:00:00Z", 23),
            Triple("2026-11-01T04:00:00Z", "2026-11-02T05:00:00Z", 25),
        )) {
            val windows = CounterExportQuery(epoch(from), epoch(to), "America/New_York", 42, "HeapUsed").windows()
            windows.size shouldBe expected
            windows.first().from shouldBe epoch(from)
            windows.last().to shouldBe epoch(to)
            windows.zipWithNext().all { (a, b) -> a.to == b.from } shouldBe true
            windows.forEach { it.validate() }
        }
        val split = CounterExportQuery(epoch("2026-01-01T23:30:00Z"), epoch("2026-01-02T01:30:00Z"), "UTC", 42, "HeapUsed").windows()
        split.map { it.date } shouldBe listOf("20260101", "20260102", "20260102")
        split.map { it.to - it.from } shouldBe listOf(1800000L, 3600000L, 1800000L)
    }
    test("export resumes a dated missing-object chunk without inventing samples or rewriting committed files") {
        val work = tempdir().toPath()
        val dir = work.resolve("자료 🚀")
        val cli = ContractCli(work)
        WireFixture(Fault.COUNTER_EXPORT).use { server ->
            val args = exportArgs + listOf("--directory", dir.toString())
            val first = cli.run(server, args + listOf("--max-chunks", "1"))
            first.exit shouldBe 8
            first.data()["executionComplete"] shouldBe JsonPrimitive(false)
            first.data()["chunks"]!!.jsonArray.single().jsonObject["status"] shouldBe JsonPrimitive("object_unavailable")
            Files.readString(dir.resolve("chunk-000001.jsonl")) shouldBe ""
            val committed = Files.readAllBytes(dir.resolve("chunk-000001.jsonl")).toList()
            val resumed = cli.run(server, args + "--resume")
            resumed.exit shouldBe 0
            resumed.data()["chunks"]!!.jsonArray.size shouldBe 3
            Files.readAllBytes(dir.resolve("chunk-000001.jsonl")).toList() shouldBe committed
            val rows = points(dir)
            rows.map { it.time } shouldBe listOf(1767312000000L, 1767315599999L, 1767315600000L, 1767317399999L)
            rows.map { it.value } shouldBe listOf("9007199254740993", "0", "9007199254740993", "0")
            val bytes = snapshot(dir)
            cli.run(server, args + "--resume").exit shouldBe 0
            snapshot(dir) shouldBe bytes
            cli.run(server, args + "--resume" + listOf("--chunk-minutes", "30")).exit shouldBe 2
            snapshot(dir) shouldBe bytes
            Files.writeString(dir.resolve("chunk-000002.jsonl"), "corrupt")
            val damaged = snapshot(dir)
            cli.run(server, args + "--resume").exit shouldBe 10
            snapshot(dir) shouldBe damaged
        }
    }
    test("export keeps its last checkpoint after transport interruption and overwrites only the uncommitted chunk") {
        val work = tempdir().toPath()
        val dir = work.resolve("capture")
        WireFixture(Fault.COUNTER_EXPORT).use { server ->
            RecoveryProxy(server.port, RequestCmd.COUNTER_PAST_TIME, 2).use { proxy ->
                val cli = ContractCli(work)
                val args = exportArgs.map { if (it == "2026-01-01T23:30:00Z") "2026-01-02T00:00:00Z" else it } +
                    listOf("--directory", dir.toString(), "--timeout-ms", "10000")
                val pool = Executors.newSingleThreadExecutor()
                val task = pool.submit<Outcome> { cli.run(proxy.port, args) }
                try {
                    proxy.awaitPaused()
                    val before = snapshot(dir)
                    json.parseToJsonElement(before.getValue("manifest.json")).jsonObject["chunks"]!!.jsonArray.size shouldBe 1
                    proxy.disconnectUpstream()
                    task.get(20, TimeUnit.SECONDS).exit shouldBe 10
                    snapshot(dir) shouldBe before
                    Files.writeString(dir.resolve("chunk-000002.jsonl.partial"), "incomplete")
                    Files.writeString(dir.resolve("chunk-000002.jsonl"), "orphan")
                    cli.run(proxy.port, args + "--resume").exit shouldBe 0
                    points(dir).map { it.time } shouldBe listOf(1767312000000L, 1767315599999L, 1767315600000L, 1767317399999L)
                    Files.readString(dir.resolve("chunk-000001.jsonl")) shouldBe before.getValue("chunk-000001.jsonl")
                    Files.exists(dir.resolve("chunk-000002.jsonl.partial")) shouldBe false
                    proxy.transportFailures shouldBe 1
                } finally {
                    proxy.release()
                    pool.shutdownNow()
                    pool.awaitTermination(20, TimeUnit.SECONDS) shouldBe true
                }
            }
        }
    }
    test("counter capture rejects concurrent writers until the owner closes its lock") {
        val dir = tempdir().toPath().resolve("locked")
        val manifest = CounterCaptureManifest(source = portSource, endpoint = "memory",
            query = CounterExportQuery(portQuery.from, portQuery.to, "UTC", 42, "HeapUsed"))
        CounterCaptureStore(dir).use { owner ->
            owner.create()
            owner.save(manifest)
            CounterCaptureStore(dir).use { other ->
                shouldThrow<OperationFailure> { other.load() }.code shouldBe ErrorCode.IO_ERROR
            }
        }
        CounterCaptureStore(dir).use { reopened ->
            reopened.load() shouldBe manifest
        }
    }
    test("export rejects invalid sizes, unsupported options and excessive ranges before connecting") {
        val cli = ContractCli(tempdir().toPath())
        for (args in listOf(
            exportArgs + listOf("--directory", "target", "--chunk-minutes", "0"),
            exportArgs + listOf("--directory", "target", "--max-chunks", "0"),
            exportArgs + listOf("--directory", "target", "--date", "20260102"),
            exportArgs.map { if (it == "2026-01-02T01:30:00Z") "2028-01-01T00:00:00Z" else it } + listOf("--directory", "target"),
            listOf("doctor", "--chunk-minutes", "60"),
        )) cli.run(args).exit shouldBe 2
    }
})
