package dev.scouterctl

import dev.scouterctl.adapters.files.EvidenceStore
import dev.scouterctl.application.CaptureOptions
import dev.scouterctl.application.captureEvidence
import dev.scouterctl.core.*
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import java.nio.file.Files

class CaptureContinuationTest : FunSpec({
    test("continued profile capture validates its existing prefix once and retains IDs across the total budget") {
        val directory = tempdir().toPath().resolve("capture")
        val store = EvidenceStore(directory)
        val profiles = mutableListOf<Long>()
        val source = MemorySource({ cursor ->
            val first = cursor.txid.toLong() + 1
            val last = first + 1
            XLogPage(listOf(portRow(first), portRow(last)), Cursor(portQuery.from + last, last.toString()), last < 16)
        }, { id -> profiles += id; ProfileRecord(id.toString(), emptyList()) })
        val checkpoint = captureEvidence(source, portQuery, CaptureOptions(false, 2, "memory"), store)
        val expectedReads = checkpoint.pages.associate { directory.resolve(it.name) to Files.size(directory.resolve(it.name)) }

        val measured = measureResumeReads(directory) {
            captureEvidence(source, portQuery, CaptureOptions(true, 2, "memory", maxTotalPages = 6), store)
        }

        measured.bytes shouldBe expectedReads
        measured.result.executionComplete shouldBe true
        measured.result.pages.size shouldBe 8
        profiles shouldBe (1L..16L).toList()
    }

    test("total budget stops after its exact number of new pages and leaves a resumable checkpoint") {
        val store = EvidenceStore(tempdir().toPath().resolve("capture"))
        val source = MemorySource({ cursor ->
            val id = cursor.txid.toLong() + 1
            XLogPage(listOf(portRow(id)), Cursor(portQuery.from + id, id.toString()), id < 5)
        })

        val result = captureEvidence(source, portQuery, CaptureOptions(false, 1, "memory", maxTotalPages = 3), store)

        result.executionComplete shouldBe false
        store.load().pages.size shouldBe 3
        store.load().cursor.txid shouldBe "3"
        captureEvidence(source, portQuery, CaptureOptions(true, 1, "memory", maxTotalPages = 3), store).executionComplete shouldBe true
    }

    test("cancellation during a profile page preserves the previous cursor and resume completes without duplicate records") {
        val store = EvidenceStore(tempdir().toPath().resolve("capture"))
        var cancelled = false
        val source = MemorySource({ cursor ->
            val id = cursor.txid.toLong() + 1
            XLogPage(listOf(portRow(id)), Cursor(portQuery.from + id, id.toString()), id < 4)
        }, { id ->
            if (id == 3L) cancelled = true
            ProfileRecord(id.toString(), emptyList())
        })

        shouldThrow<OperationFailure> {
            captureEvidence(source, portQuery, CaptureOptions(false, 1, "memory", maxTotalPages = 4, cancelled = { cancelled }), store)
        }.code shouldBe ErrorCode.PARTIAL_RESULT

        val checkpoint = store.load()
        checkpoint.pages.size shouldBe 2
        checkpoint.cursor.txid shouldBe "2"
        val resumed = captureEvidence(source, portQuery, CaptureOptions(true, 1, "memory", maxTotalPages = 4), store)
        resumed.executionComplete shouldBe true
        resumed.pages.flatMapIndexed { index, _ -> store.readPage(resumed, index) }.map { it.xlog.txid } shouldBe listOf("1", "2", "3", "4")
    }

    test("a competing profile writer is rejected and the lock is released when the owner finishes") {
        val directory = tempdir().toPath().resolve("capture")
        val source = MemorySource({
            shouldThrow<OperationFailure> {
                captureEvidence(MemorySource({ error("Competing writer reached the Collector") }), portQuery,
                    CaptureOptions(true, 1, "memory"), EvidenceStore(directory))
            }.message shouldBe "Profile capture writer lock is unavailable; stop other writers and check write permissions"
            XLogPage(listOf(portRow(1)), Cursor(portQuery.from + 1, "1"), false)
        })

        captureEvidence(source, portQuery, CaptureOptions(false, 1, "memory"), EvidenceStore(directory)).executionComplete shouldBe true
        captureEvidence(MemorySource({ error("Completed capture fetched a new page") }), portQuery,
            CaptureOptions(true, 1, "memory"), EvidenceStore(directory)).executionComplete shouldBe true
    }

    test("an externally changed manifest is preserved instead of overwritten at the next checkpoint") {
        val directory = tempdir().toPath().resolve("capture")
        val source = MemorySource({
            Files.writeString(directory.resolve("manifest.json"), "external change")
            XLogPage(listOf(portRow(1)), Cursor(portQuery.from + 1, "1"), false)
        })

        shouldThrow<OperationFailure> {
            captureEvidence(source, portQuery, CaptureOptions(false, 1, "memory", maxTotalPages = 2), EvidenceStore(directory))
        }.code shouldBe ErrorCode.IO_ERROR

        Files.readString(directory.resolve("manifest.json")) shouldBe "external change"
    }

    for (budget in listOf(0, 10001)) {
        test("invalid total budget $budget is rejected before a capture directory is created") {
            val directory = tempdir().toPath().resolve("capture")
            shouldThrow<OperationFailure> {
                captureEvidence(MemorySource({ error("Invalid budget queried the Collector") }), portQuery,
                    CaptureOptions(false, 1, "memory", maxTotalPages = budget), EvidenceStore(directory))
            }.code shouldBe ErrorCode.INVALID_ARGUMENT
            Files.exists(directory) shouldBe false
        }
    }

    test("packaged CLI extends the profile capture budget only when explicitly requested") {
        val work = tempdir().toPath()
        WireFixture().use { server ->
            val result = ContractCli(work).run(server, listOf("capture", "--include-profiles", "--max-pages", "1",
                "--max-total-pages", "3", "--directory", work.resolve("capture").toString()) + evidenceWindow)
            result.exit shouldBe 0
            EvidenceStore(work.resolve("capture")).load().executionComplete shouldBe true
        }
    }
})
