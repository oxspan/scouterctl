package dev.scouterctl

import dev.scouterctl.core.*
import dev.scouterctl.adapters.files.*

import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.*
import java.nio.file.Files

class CaptureComparisonFailureTest : FunSpec({
    test("comparison rejects missing inputs unsupported options and invalid limits before online access") {
        val work = tempdir().toPath()
        comparisonFixture(work, true)
        comparisonFixture(work, false)
        listOf(listOf("--host", "127.0.0.1"), listOf("--context", "missing"), listOf("--interactive"),
            listOf("--directory", work.toString()), listOf("--sort", "errors"), listOf("--errors-only"),
            listOf("--txid", "1"), listOf("--limit", "0"), listOf("--limit", "1001"), listOf("--resume")).forEach { flags ->
            val result = compareCli(work, flags)
            result.exit shouldBe 2
            result.events.single()["error"]!!.jsonObject["code"]!!.jsonPrimitive.content shouldBe "INVALID_ARGUMENT"
        }
        listOf(listOf("inspect", "compare"), listOf("inspect", "compare", "--before", work.resolve("before").toString()),
            listOf("doctor", "--before", work.toString()), listOf("inspect", "summary", "--after", work.toString())).forEach { args ->
            ContractCli(work).run(args, null).exit shouldBe 2
        }
        compareCli(work, listOf("--no-input")).exit shouldBe 0
    }

    for (before in listOf(true, false)) for (damage in listOf("checksum", "duplicate", "negative-elapsed")) {
        test("comparison validates all pages beyond output limit on before=$before with $damage") {
            val work = tempdir().toPath()
            val left = comparisonFixture(work, true)
            val right = comparisonFixture(work, false)
            val directory = if (before) left else right
            val store = EvidenceStore(directory)
            val manifest = store.load()
            when (damage) {
                "checksum" -> Files.writeString(directory.resolve(manifest.pages.last().name), "corrupt")
                "duplicate" -> store.save(manifest.copy(pages = manifest.pages.dropLast(1) +
                    store.writePage(manifest.pages.lastIndex, store.readPage(manifest, 0).take(1))))
                "negative-elapsed" -> replaceComparisonRows(directory, comparisonRows(directory).mapIndexed { index, record ->
                    if (index == 2) record.copy(xlog = record.xlog.copy(elapsed = -1)) else record
                })
            }
            val result = compareCli(work, listOf("--limit", "1"))
            result.exit shouldBe 10
            result.events.single()["error"]!!.jsonObject["code"]!!.jsonPrimitive.content shouldBe "IO_ERROR"
            result.events.single().containsKey("data") shouldBe false
        }
    }

    test("comparison refuses truncated source aggregation instead of treating unselected services as absent") {
        val work = tempdir().toPath()
        val before = comparisonFixture(work, true)
        comparisonFixture(work, false)
        val store = EvidenceStore(before)
        val manifest = store.load()
        store.save(manifest.copy(query = manifest.query.copy(pageSize = 100), pages = emptyList()))
        val rows = (1..1001).map { id ->
            EvidenceRecord(XLogRecord(id.toString(), "0", "0", manifest.query.from, 42, id, 1, 0),
                ProfileAvailability.AVAILABLE, ProfileRecord(id.toString(), emptyList()))
        }
        replaceComparisonRows(before, rows)
        val result = compareCli(work, listOf("--limit", "1"))
        result.exit shouldBe 8
        result.events.single()["error"]!!.jsonObject["code"]!!.jsonPrimitive.content shouldBe "PARTIAL_RESULT"
        result.events.single().containsKey("data") shouldBe false
    }
})
