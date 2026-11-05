package dev.scouterctl

import dev.scouterctl.core.*
import dev.scouterctl.adapters.files.*
import dev.scouterctl.adapters.serialization.*

import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.*
import java.nio.file.Files

class EvidenceSearchFailureTest : FunSpec({
    listOf(
        listOf("--limit", "0"), listOf("--limit", "1001"), listOf("--min-elapsed-ms", "-1"),
        listOf("--service-name", "/checkout<GET>"), listOf("--service-name", " ", "--include-text"),
        listOf("--txid", "2"), listOf("--resume"),
    ).forEach { flags ->
        test("search rejects invalid option combination $flags") {
            val work = tempdir().toPath()
            val directory = searchFixture(work)
            runOfflineCli(work, listOf("inspect", "search", "--directory", directory.toString()) + flags).exit shouldBe 2
        }
    }

    test("search rejects text opt-in when text was not captured") {
        val work = tempdir().toPath()
        val directory = searchFixture(work, text = false)
        runOfflineCli(work, listOf("inspect", "search", "--directory", directory.toString(), "--include-text")).exit shouldBe 2
    }

    test("search filters cannot silently change a single transaction inspect") {
        val work = tempdir().toPath()
        val directory = searchFixture(work)
        runOfflineCli(work, listOf("inspect", "--directory", directory.toString(), "--txid", "2", "--errors-only")).exit shouldBe 2
    }

    listOf("checksum", "duplicate").forEach { damage ->
        test("search validates later pages even after top N is full with $damage") {
            val work = tempdir().toPath()
            val directory = searchFixture(work)
            val store = EvidenceStore(directory)
            val manifest = store.load()
            if (damage == "checksum") Files.writeString(directory.resolve(manifest.pages.last().name), "corrupt")
            else store.save(manifest.copy(pages = manifest.pages.dropLast(1) + store.writePage(2, store.readPage(manifest, 0).take(1))))
            val result = runOfflineCli(work, listOf("inspect", "search", "--directory", directory.toString(), "--limit", "1"))
            result.exit shouldBe 10
            result.events.single()["type"]!!.jsonPrimitive.content shouldBe "error"
        }
    }

    listOf(false, true).forEach { finished ->
        test("search preserves partial capture and last failure metadata when executionComplete is $finished") {
            val work = tempdir().toPath()
            val directory = searchFixture(work, empty = true)
            val store = EvidenceStore(directory)
            store.save(store.load().copy(executionComplete = finished, lastFailure = ErrorCode.CONNECTION_FAILED))
            val result = runOfflineCli(work, listOf("inspect", "search", "--directory", directory.toString()))
            result.exit shouldBe 8
            result.events.single()["executionComplete"]!!.jsonPrimitive.boolean shouldBe false
            val search = json.decodeFromJsonElement<EvidenceSearchDto>(result.data()).toModel()
            search.captureExecutionComplete shouldBe finished
            search.lastFailure shouldBe ErrorCode.CONNECTION_FAILED
            search.summary.capturedRecords shouldBe 0
        }
    }
})
