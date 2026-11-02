package dev.scouterctl

import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.*

class EvidenceSearchTest : FunSpec({
    test("offline top N scans every page and orders ties by time then numeric txid") {
        val work = tempdir().toPath()
        val directory = searchFixture(work)
        val result = runOfflineCli(work, listOf("inspect", "search", "--directory", directory.toString(), "--limit", "2"))
        result.exit shouldBe 8
        result.stderr shouldBe ""
        val search = json.decodeFromJsonElement<EvidenceSearch>(result.data())
        search.records.map { it.xlog.txid } shouldBe listOf("-9", "2")
        search.summary shouldBe EvidenceSummary(5, 2, 1, 2, 5, 2, 1610, 500)
        search.truncated shouldBe true
        search.textStored shouldBe true
        search.textIncluded shouldBe false
        search.records.map { it.service } shouldBe listOf(null, null)
        result.output shouldNotContain "/checkout"
    }

    data class Selection(val flags: List<String>, val ids: List<String>, val elapsed: Long, val errors: Int)
    listOf(
        Selection(listOf("--errors-only"), listOf("2", "10"), 1000, 2),
        Selection(listOf("--service-hash", "10"), listOf("-9", "2", "9007199254740993"), 1100, 1),
        Selection(listOf("--min-elapsed-ms", "500"), listOf("-9", "2", "10"), 1500, 2),
        Selection(listOf("--errors-only", "--service-hash", "10", "--min-elapsed-ms", "500"), listOf("2"), 500, 1),
        Selection(listOf("--service-name", "/checkout<GET>", "--include-text"), listOf("2", "9007199254740993"), 600, 1),
        Selection(listOf("--service-hash", "20", "--service-name", "/checkout<GET>", "--include-text"), emptyList(), 0, 0),
        Selection(listOf("--min-elapsed-ms", "501"), emptyList(), 0, 0),
    ).forEach { selection ->
        test("filters apply before ranking and totals for ${selection.flags}") {
            val work = tempdir().toPath()
            val directory = searchFixture(work)
            val result = runOfflineCli(work, listOf("inspect", "search", "--directory", directory.toString()) + selection.flags)
            result.exit shouldBe 8
            val search = json.decodeFromJsonElement<EvidenceSearch>(result.data())
            search.records.map { it.xlog.txid } shouldBe selection.ids
            search.summary.matchedRecords shouldBe selection.ids.size
            search.summary.matchedElapsedTotalMs shouldBe selection.elapsed
            search.summary.matchedErrors shouldBe selection.errors
            search.truncated shouldBe false
            if (selection.flags.contains("--service-name")) {
                search.records.map { it.service?.text } shouldBe selection.ids.map { "/checkout<GET>" }
                search.summary.unknownServiceNames shouldBe 2
            }
        }
    }

    test("complete empty capture returns a successful empty summary") {
        val work = tempdir().toPath()
        val directory = searchFixture(work, empty = true)
        val result = runOfflineCli(work, listOf("inspect", "search", "--directory", directory.toString()))
        result.exit shouldBe 0
        val search = json.decodeFromJsonElement<EvidenceSearch>(result.data())
        search.summary shouldBe EvidenceSummary(0, 0, 0, 0, 0, 0, 0, null)
        search.records shouldBe emptyList()
        search.truncated shouldBe false
    }

    test("structural-only capture supports hash filtering without credentials") {
        val work = tempdir().toPath()
        val directory = searchFixture(work, text = false)
        val result = runOfflineCli(work, listOf("inspect", "search", "--directory", directory.toString(), "--service-hash", "10"))
        result.exit shouldBe 8
        val search = json.decodeFromJsonElement<EvidenceSearch>(result.data())
        search.records.map { it.xlog.txid } shouldBe listOf("-9", "2", "9007199254740993")
        search.summary.unknownServiceNames shouldBe 5
        search.textStored shouldBe false
    }
})
