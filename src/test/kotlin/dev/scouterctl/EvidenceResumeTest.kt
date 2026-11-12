package dev.scouterctl

import dev.scouterctl.adapters.files.EvidenceStore
import dev.scouterctl.application.CaptureOptions
import dev.scouterctl.application.captureEvidence
import dev.scouterctl.core.*
import io.kotest.core.spec.style.FunSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import java.nio.file.Files

class EvidenceResumeTest : FunSpec({
    test("issue 74 resume reads each committed page once while retaining IDs across page budgets") {
        val directory = tempdir().toPath().resolve("capture")
        val store = EvidenceStore(directory)
        val profileIds = mutableListOf<Long>()
        val source = MemorySource({ cursor ->
            val first = cursor.txid.toLong() + 1
            val last = first + 1
            XLogPage(listOf(portRow(first), portRow(last)), Cursor(portQuery.from + last, last.toString()), last < 12)
        }, { id -> profileIds += id; ProfileRecord(id.toString(), emptyList()) })
        var manifest = captureEvidence(source, portQuery, CaptureOptions(false, 2, "memory"), store)

        repeat(2) {
            val expectedBytes = manifest.pages.associate { directory.resolve(it.name) to Files.size(directory.resolve(it.name)) }
            val measured = measureResumeReads(directory) {
                captureEvidence(source, portQuery, CaptureOptions(true, 2, "memory"), store)
            }
            measured.bytes shouldBe expectedBytes
            manifest = measured.result
        }

        manifest.complete shouldBe true
        profileIds shouldBe (1L..12L).toList()
        manifest.pages.flatMapIndexed { index, _ -> store.readPage(manifest, index) }.map { it.xlog.txid } shouldBe
            (1L..12L).map(Long::toString)
    }

    test("resume rejects an incoming transaction already committed before fetching or appending its profile") {
        val store = EvidenceStore(tempdir().toPath().resolve("capture"))
        var profiles = 0
        val source = MemorySource({ XLogPage(listOf(portRow(1)), Cursor(portQuery.from + 1, "1"), true) },
            { id -> profiles++; ProfileRecord(id.toString(), emptyList()) })
        val checkpoint = captureEvidence(source, portQuery, CaptureOptions(false, 1, "memory"), store)

        shouldThrow<OperationFailure> {
            captureEvidence(source, portQuery, CaptureOptions(true, 1, "memory"), store)
        }.code shouldBe ErrorCode.PROTOCOL_ERROR

        profiles shouldBe 1
        store.load().pages shouldBe checkpoint.pages
        store.load().cursor shouldBe checkpoint.cursor
    }
})
