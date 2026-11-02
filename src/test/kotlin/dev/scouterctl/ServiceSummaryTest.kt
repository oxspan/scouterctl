package dev.scouterctl

import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path

class ServiceSummaryTest : FunSpec({
    test("zero-error service has no error representative and totals do not overflow 32 bits") {
        val work = tempdir().toPath()
        val directory = searchFixture(work, text = false, empty = true)
        val store = EvidenceStore(directory)
        val manifest = store.load()
        val rows = listOf(Int.MAX_VALUE, Int.MAX_VALUE, 0).mapIndexed { index, elapsed ->
            val id = (Long.MAX_VALUE - index).toString()
            EvidenceRecord(XLogRecord(id, "0", "0", manifest.query.from, 42, 7, elapsed, 0),
                ProfileAvailability.AVAILABLE, ProfileRecord(id, emptyList()))
        }
        store.save(manifest.copy(pages = rows.chunked(2).mapIndexed { index, page -> store.writePage(index, page) }))
        val result = summaryCli(work, directory)
        result.exit shouldBe 0
        val group = result.data()["services"]!!.jsonArray.single().jsonObject
        group["elapsedTotalMs"]!!.jsonPrimitive.long shouldBe 4294967294L
        group["averageElapsedMs"]!!.jsonPrimitive.double shouldBe (4294967294.0 / 3)
        group["errorRate"]!!.jsonPrimitive.double shouldBe 0.0
        group["slowestError"] shouldBe JsonNull
        group["slowest"]!!.jsonObject["txid"]!!.jsonPrimitive.content shouldBe "9223372036854775806"
    }

    test("too many service groups fail explicitly instead of publishing incomplete aggregates") {
        val work = tempdir().toPath()
        val directory = searchFixture(work, text = false, empty = true)
        val store = EvidenceStore(directory)
        val manifest = store.load()
        val rows = (1..10001).map { id -> EvidenceRecord(
            XLogRecord(id.toString(), "0", "0", manifest.query.from, 42, id, 0, 0),
            ProfileAvailability.AVAILABLE, ProfileRecord(id.toString(), emptyList())) }
        store.save(manifest.copy(query = manifest.query.copy(pageSize = 100),
            pages = rows.chunked(100).mapIndexed { index, page -> store.writePage(index, page) }))
        val result = summaryCli(work, directory, listOf("--limit", "1"))
        result.exit shouldBe 8
        result.events.single()["error"]!!.jsonObject["code"]!!.jsonPrimitive.content shouldBe "PARTIAL_RESULT"
        result.events.single().containsKey("data") shouldBe false
    }

    test("groups all captured requests before limiting and links deterministic representatives to inspect") {
        val work = tempdir().toPath()
        val directory = summaryFixture(work)
        val result = summaryCli(work, directory, listOf("--limit", "1"))
        result.exit shouldBe 8
        result.stderr shouldBe ""
        val data = result.data()
        data["basis"]!!.jsonPrimitive.content shouldBe "captured-xlogs"
        data["capturedRecords"]!!.jsonPrimitive.int shouldBe 7
        data["capturedErrors"]!!.jsonPrimitive.int shouldBe 4
        data["totalServices"]!!.jsonPrimitive.int shouldBe 3
        data["unavailableProfiles"]!!.jsonPrimitive.int shouldBe 1
        data["unknownServiceNames"]!!.jsonPrimitive.int shouldBe 2
        data["truncated"]!!.jsonPrimitive.boolean shouldBe true
        data["upstreamCoverage"]!!.jsonPrimitive.content shouldBe "unknown"
        data["sampling"]!!.jsonPrimitive.content shouldBe "unknown"
        val group = data["services"]!!.jsonArray.single().jsonObject
        group["serviceHash"]!!.jsonPrimitive.int shouldBe 30
        group["requests"]!!.jsonPrimitive.int shouldBe 2
        group["errors"]!!.jsonPrimitive.int shouldBe 2
        group["errorRate"]!!.jsonPrimitive.double shouldBe 1.0
        group["elapsedTotalMs"]!!.jsonPrimitive.long shouldBe 60
        group["averageElapsedMs"]!!.jsonPrimitive.double shouldBe 30.0
        group["maxElapsedMs"]!!.jsonPrimitive.int shouldBe 30
        group["slowest"]!!.jsonObject["txid"]!!.jsonPrimitive.content shouldBe "9"
        group["slowestError"]!!.jsonObject["txid"]!!.jsonPrimitive.content shouldBe "9"
        result.output shouldNotContain "/checkout"
        result.output shouldNotContain "/third"
        val detail = runOfflineCli(work, listOf("inspect", "--directory", directory.toString(), "--txid", "9"))
        detail.exit shouldBe 8
        detail.data()["record"]!!.jsonObject["xlog"]!!.jsonObject["serviceHash"]!!.jsonPrimitive.int shouldBe 30
    }

    test("ordering changes service priority without changing means ratios or missing-profile counts") {
        val work = tempdir().toPath()
        val directory = summaryFixture(work)
        val expected = mapOf("errors" to listOf(30, 10, 20), "elapsed" to listOf(10, 20, 30), "requests" to listOf(10, 30, 20))
        expected.forEach { (sort, hashes) ->
            val data = summaryCli(work, directory, listOf("--sort", sort, "--include-text")).data()
            val groups = data["services"]!!.jsonArray.map { it.jsonObject }
            groups.map { it["serviceHash"]!!.jsonPrimitive.int } shouldBe hashes
            val checkout = groups.single { it["serviceHash"]!!.jsonPrimitive.int == 10 }
            checkout["requests"]!!.jsonPrimitive.int shouldBe 3
            checkout["errors"]!!.jsonPrimitive.int shouldBe 1
            checkout["errorRate"]!!.jsonPrimitive.double shouldBe (1.0 / 3)
            checkout["averageElapsedMs"]!!.jsonPrimitive.double shouldBe (1100.0 / 3)
            checkout["serviceName"]!!.jsonPrimitive.content shouldBe "/checkout<GET>"
            checkout["slowest"]!!.jsonObject["txid"]!!.jsonPrimitive.content shouldBe "-9"
            checkout["slowestError"]!!.jsonObject["txid"]!!.jsonPrimitive.content shouldBe "2"
            val refund = groups.single { it["serviceHash"]!!.jsonPrimitive.int == 20 }
            refund["averageElapsedMs"]!!.jsonPrimitive.double shouldBe 255.0
            refund["errorRate"]!!.jsonPrimitive.double shouldBe 0.5
            refund["unavailableProfiles"]!!.jsonPrimitive.int shouldBe 1
            refund["slowest"]!!.jsonObject["profileStatus"]!!.jsonPrimitive.content shouldBe "unavailable"
        }
    }

    test("empty complete and partial captures preserve execution status without inventing traffic coverage") {
        val work = tempdir().toPath()
        val directory = searchFixture(work, empty = true)
        val empty = summaryCli(work, directory)
        empty.exit shouldBe 0
        empty.data()["services"]!!.jsonArray shouldBe JsonArray(emptyList())
        empty.data()["capturedRecords"]!!.jsonPrimitive.int shouldBe 0
        empty.data()["truncated"]!!.jsonPrimitive.boolean shouldBe false
        val store = EvidenceStore(directory)
        listOf(false, true).forEach { complete ->
            store.save(store.load().copy(executionComplete = complete, lastFailure = ErrorCode.CONNECTION_FAILED))
            val result = summaryCli(work, directory)
            result.exit shouldBe 8
            result.data()["captureExecutionComplete"]!!.jsonPrimitive.boolean shouldBe complete
            result.data()["lastFailure"]!!.jsonPrimitive.content shouldBe "CONNECTION_FAILED"
            result.events.single()["executionComplete"]!!.jsonPrimitive.boolean shouldBe false
        }
    }

    test("conflicting names stay one hash group with explicit ambiguity and text opt-in is enforced") {
        val work = tempdir().toPath()
        val directory = searchFixture(work)
        val store = EvidenceStore(directory)
        val manifest = store.load()
        val rows = store.readPage(manifest, 0).map { row ->
            if (row.xlog.serviceHash != 10) row else row.copy(profile = row.profile!!.copy(
                transaction = ProfileTransaction("available", DictionaryText(10, "/conflicting"))))
        }
        store.save(manifest.copy(pages = listOf(store.writePage(0, rows)) + manifest.pages.drop(1)))
        val group = summaryCli(work, directory, listOf("--include-text")).data()["services"]!!.jsonArray
            .map { it.jsonObject }.single { it["serviceHash"]!!.jsonPrimitive.int == 10 }
        group["serviceName"] shouldBe JsonNull
        group["nameConflict"]!!.jsonPrimitive.boolean shouldBe true
        val structural = searchFixture(tempdir().toPath(), text = false)
        summaryCli(work, structural, listOf("--include-text")).exit shouldBe 2
        summaryCli(work, structural).data()["unknownServiceNames"]!!.jsonPrimitive.int shouldBe 5
    }

    test("summary rejects search filters online options and invalid sort or limit") {
        val work = tempdir().toPath()
        val directory = searchFixture(work)
        listOf(listOf("--errors-only"), listOf("--min-elapsed-ms", "1"), listOf("--service-hash", "10"),
            listOf("--txid", "2"), listOf("--resume"), listOf("--host", "127.0.0.1"),
            listOf("--sort", "unknown"), listOf("--limit", "0"), listOf("--limit", "1001")).forEach { flags ->
            summaryCli(work, directory, flags).exit shouldBe 2
        }
        runOfflineCli(work, listOf("inspect", "search", "--directory", directory.toString(), "--sort", "errors")).exit shouldBe 2
    }

    listOf("checksum", "duplicate", "negative-elapsed").forEach { damage ->
        test("summary validates later pages beyond output limit with $damage") {
            val work = tempdir().toPath()
            val directory = searchFixture(work)
            val store = EvidenceStore(directory)
            val manifest = store.load()
            when (damage) {
                "checksum" -> Files.writeString(directory.resolve(manifest.pages.last().name), "corrupt")
                "duplicate" -> store.save(manifest.copy(pages = manifest.pages.dropLast(1) + store.writePage(2, store.readPage(manifest, 0).take(1))))
                "negative-elapsed" -> {
                    val last = store.readPage(manifest, 2).single()
                    store.save(manifest.copy(pages = manifest.pages.dropLast(1) + store.writePage(2, listOf(last.copy(xlog = last.xlog.copy(elapsed = -1))))))
                }
            }
            summaryCli(work, directory, listOf("--limit", "1")).exit shouldBe 10
        }
    }
})

private fun summaryCli(work: Path, directory: Path, flags: List<String> = emptyList()): Outcome =
    runOfflineCli(work, listOf("inspect", "summary", "--directory", directory.toString()) + flags)

private fun summaryFixture(work: Path): Path {
    val directory = searchFixture(work)
    val store = EvidenceStore(directory)
    val manifest = store.load()
    val rows = listOf("11", "9").map { id ->
        EvidenceRecord(XLogRecord(id, "0", "0", manifest.query.from + 1, 42, 30, 30, 8), ProfileAvailability.AVAILABLE,
            ProfileRecord(id, emptyList(), transaction = ProfileTransaction("available", DictionaryText(30, "/third"))))
    }
    store.save(manifest.copy(pages = manifest.pages + store.writePage(manifest.pages.size, rows)))
    return directory
}
