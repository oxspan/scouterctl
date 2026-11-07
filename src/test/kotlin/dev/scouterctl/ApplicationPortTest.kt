package dev.scouterctl

import dev.scouterctl.application.*
import dev.scouterctl.core.*
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ApplicationPortTest : FunSpec({
    test("next page failure preserves the committed cursor and records its typed failure") {
        val store = MemoryEvidence()
        val checkpoint = Cursor(portQuery.from + 1, "1")
        val source = MemorySource({ cursor ->
            if (cursor != Cursor()) throw OperationFailure(ErrorCode.CONNECTION_FAILED, "connection lost")
            XLogPage(listOf(portRow(1)), checkpoint, true)
        })
        shouldThrow<OperationFailure> {
            captureEvidence(source, portQuery, CaptureOptions(false, 2, "memory"), store)
        }.code shouldBe ErrorCode.CONNECTION_FAILED
        store.load().cursor shouldBe checkpoint
        store.load().pages.sumOf { it.records } shouldBe 1
        store.load().lastFailure shouldBe ErrorCode.CONNECTION_FAILED
        store.records[0]!!.single().xlog.txid shouldBe "1"
    }
    test("translated unexpected decoding failure does not alter the last committed manifest") {
        val store = MemoryEvidence()
        val checkpoint = Cursor(portQuery.from + 1, "1")
        val source = MemorySource({ cursor ->
            if (cursor != Cursor()) throw UnexpectedFailure(ErrorCode.PROTOCOL_ERROR, "Response could not be decoded")
            XLogPage(listOf(portRow(1)), checkpoint, true)
        })
        shouldThrow<OperationFailure> { captureEvidence(source, portQuery, CaptureOptions(false, 2, "memory"), store) }
        store.load().cursor shouldBe checkpoint
        store.load().lastFailure shouldBe null
    }
    test("resume retries unavailable profiles without fetching completed XLog pages") {
        val store = MemoryEvidence()
        val missing = MemorySource({ XLogPage(listOf(portRow(1)), Cursor(portQuery.from + 1, "1"), false) },
            { throw OperationFailure(ErrorCode.NOT_FOUND, "Profile unavailable") })
        captureEvidence(missing, portQuery, CaptureOptions(false, 1, "memory"), store).complete shouldBe false
        val retry = MemorySource({ error("Completed capture must not fetch pages") })
        val result = captureEvidence(retry, portQuery, CaptureOptions(true, 1, "memory"), store)
        result.complete shouldBe true
        store.readPage(result, 0).single().profile shouldBe ProfileRecord("1", emptyList())
    }
    test("resume mismatch and integrity failure do not overwrite a checkpoint") {
        val store = MemoryEvidence()
        val source = MemorySource({ XLogPage(listOf(portRow(1)), Cursor(), false) })
        val original = captureEvidence(source, portQuery, CaptureOptions(false, 1, "memory"), store)
        store.rejectSave = true
        shouldThrow<OperationFailure> {
            captureEvidence(source, portQuery, CaptureOptions(true, 1, "different"), store)
        }.code shouldBe ErrorCode.INVALID_ARGUMENT
        store.brokenPage = 0
        shouldThrow<OperationFailure> {
            captureEvidence(source, portQuery, CaptureOptions(true, 1, "memory"), store)
        }.code shouldBe ErrorCode.IO_ERROR
        store.load() shouldBe original
    }
    test("bounded search still traverses and rejects a damaged later page") {
        val store = MemoryEvidence()
        val pages = listOf(1L, 2L).mapIndexed { index, id ->
            store.writePage(index, listOf(EvidenceRecord(portRow(id, 100), ProfileAvailability.AVAILABLE, ProfileRecord(id.toString(), emptyList()))))
        }
        store.save(EvidenceManifest(source = portSource, endpoint = "memory", query = portQuery, textIncluded = false, pages = pages, executionComplete = true))
        store.brokenPage = 1
        shouldThrow<OperationFailure> { searchCapture(store, EvidenceFilter(limit = 1), false) }.message shouldBe "broken late page"
    }
    test("context mutation evaluates its rules against the value provided by the transaction") {
        val existing = SavedConnection("existing", 6100, "guest")
        val repository = object : ContextRepository {
            override fun read() = ContextConfig()
            override fun update(change: (ContextConfig) -> ContextConfig) = change(ContextConfig(contexts = mapOf("existing" to existing)))
        }
        val updated = Contexts(repository).add("new", SavedConnection("new", 6101, "reader"))
        updated.contexts.keys shouldBe setOf("existing", "new")
        updated.contexts["existing"] shouldBe existing
    }
})
