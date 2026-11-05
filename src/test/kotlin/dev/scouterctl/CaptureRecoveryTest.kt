package dev.scouterctl

import dev.scouterctl.adapters.files.EvidenceStore
import dev.scouterctl.application.*
import dev.scouterctl.core.*
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import scouter.net.RequestCmd
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class CaptureRecoveryTest : FunSpec({
    test("profile interruption discards the incomplete page and retries it from the committed cursor") {
        val store = MemoryEvidence()
        val checkpoint = Cursor(portQuery.from + 2, "2")
        val completed = Cursor(portQuery.from + 4, "4")
        val queries = mutableListOf<Cursor>()
        val fetched = mutableListOf<Long>()
        var fail = true
        val source = MemorySource({ cursor ->
            queries += cursor
            if (cursor == Cursor()) XLogPage(listOf(portRow(1), portRow(2)), checkpoint, true)
            else XLogPage(listOf(portRow(3), portRow(4)), completed, false)
        }, { id ->
            fetched += id
            if (id == 4L && fail) throw OperationFailure(ErrorCode.CONNECTION_FAILED, "Collector stopped")
            ProfileRecord(id.toString(), emptyList())
        })
        shouldThrow<OperationFailure> { captureEvidence(source, portQuery, CaptureOptions(false, 10, "memory"), store) }
            .code shouldBe ErrorCode.CONNECTION_FAILED
        store.load().cursor shouldBe checkpoint
        store.records.keys shouldBe setOf(0)
        fail = false
        val resumed = captureEvidence(source, portQuery, CaptureOptions(true, 10, "memory"), store)
        resumed.complete shouldBe true
        resumed.lastFailure shouldBe null
        queries shouldBe listOf(Cursor(), checkpoint, checkpoint)
        fetched shouldBe listOf(1L, 2L, 3L, 4L, 3L, 4L)
        store.records.values.flatten().map { it.xlog.txid } shouldBe listOf("1", "2", "3", "4")
    }

    listOf(false, true).forEach { profiles ->
        test("packaged v${if (profiles) 2 else 1} capture survives connection loss and preserves completed bytes") {
            val work = tempdir().toPath()
            val directory = work.resolve("capture")
            WireFixture().use { server ->
                RecoveryProxy(server.port, if (profiles) RequestCmd.TRANX_PROFILE else RequestCmd.TRANX_LOAD_TIME_GROUP_V2,
                    if (profiles) 3 else 2).use { proxy ->
                    val cli = AgentCli(work, proxy.port, "fixture-password")
                    val args = listOf("capture", "--directory", directory.toString()) + evidenceWindow +
                        if (profiles) listOf("--include-profiles") else emptyList()
                    val executor = Executors.newSingleThreadExecutor()
                    val capture = executor.submit { cli.run("interrupted", args, 10) }
                    try {
                        proxy.awaitPaused()
                        val checkpoint = readRecoveryCapture(directory, profiles)
                        checkpoint.rows.size shouldBe 2
                        val pages = snapshotRecoveryPages(directory)
                        proxy.disconnectUpstream()
                        capture.get(20, TimeUnit.SECONDS)
                        readRecoveryCapture(directory, profiles) shouldBe checkpoint
                        snapshotRecoveryPages(directory) shouldBe pages
                        Files.writeString(directory.resolve("uncommitted.partial"), "incomplete write")
                        cli.run("resumed", args + "--resume")
                        val complete = readRecoveryCapture(directory, profiles)
                        complete.complete shouldBe true
                        complete.rows.map { it.txid } shouldBe WireFixture.ids.map(Long::toString)
                        proxy.transportFailures shouldBe 1
                        if (profiles) EvidenceStore(directory).load().lastFailure shouldBe null
                        val bytes = snapshotRecoveryFiles(directory)
                        cli.run("repeat", args + "--resume")
                        snapshotRecoveryFiles(directory) shouldBe bytes
                        val committed = snapshotRecoveryPages(directory).keys.first()
                        Files.writeString(directory.resolve(committed), "corrupt page")
                        val corrupt = snapshotRecoveryFiles(directory)
                        cli.run("corrupt", args + "--resume", 10)
                        snapshotRecoveryFiles(directory) shouldBe corrupt
                    } finally {
                        proxy.release()
                        executor.shutdownNow()
                        executor.awaitTermination(25, TimeUnit.SECONDS) shouldBe true
                    }
                }
            }
        }
    }
})
