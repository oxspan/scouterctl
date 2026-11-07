package dev.scouterctl

import dev.scouterctl.core.*
import dev.scouterctl.adapters.files.*
import dev.scouterctl.adapters.serialization.*

import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.*
import java.nio.file.Files

internal val evidenceWindow = listOf("--date", "20261101", "--from", "2026-11-01T00:00:00Z", "--to", "2026-11-01T00:01:00Z",
    "--timezone", "UTC", "--object-hash", "42", "--page-size", "2")

class OfflineCaptureTest : FunSpec({
    listOf(false, true).forEach { text ->
        test("capture preserves profiles offline with text policy $text and never requires credentials for inspect") {
            val work = tempdir().toPath()
            val directory = work.resolve("capture")
            val cli = ContractCli(work)
            val textArgs = if (text) listOf("--include-text") else emptyList()
            WireFixture().use { server ->
                cli.run(server, listOf("capture", "--include-profiles", "--directory", directory.toString()) + evidenceWindow + textArgs).exit shouldBe 0
            }
            WireFixture.ids.forEach { txid ->
                val args = listOf("inspect", "--directory", directory.toString(), "--txid", txid.toString())
                val result = runOfflineCli(work, args)
                result.exit shouldBe 0
                result.stderr shouldBe ""
                val inspection = json.decodeFromJsonElement<InspectionDto>(result.data()).toModel()
                inspection.record.xlog.txid shouldBe txid.toString()
                inspection.record.profileStatus shouldBe ProfileAvailability.AVAILABLE
                inspection.record.profile!!.steps.mapNotNull { it.sql } shouldBe listOf(
                    SqlProfile(123, 7, 0, 1), SqlProfile(456, 11, -789, -3))
                result.output shouldNotContain "select marker"
                if (text) {
                    val resolved = runOfflineCli(work, args + "--include-text")
                    resolved.exit shouldBe 0
                    val profile = json.decodeFromJsonElement<InspectionDto>(resolved.data()).toModel().record.profile!!
                    profile.transaction!!.service shouldBe DictionaryText(10, "/fixture<GET>")
                    profile.steps[1].sql!!.statement shouldBe DictionaryText(123, "select marker from fixture where id = ?")
                    profile.steps[2].sql!!.error shouldBe DictionaryText(-789, "fixture duplicate key error")
                } else runOfflineCli(work, args + "--include-text").exit shouldBe 2
            }
            Files.list(directory).use { files -> files.forEach {
                Files.readString(it) shouldNotContain "secret-token"
                Files.readString(it) shouldNotContain "fixture-password"
            } }
        }
    }

    test("page budget retains partial scope and resumes without duplicate transactions") {
        val work = tempdir().toPath()
        val directory = work.resolve("capture")
        val cli = ContractCli(work)
        WireFixture().use { server ->
            val args = listOf("capture", "--include-profiles", "--directory", directory.toString(), "--max-pages", "1") + evidenceWindow
            cli.run(server, args).exit shouldBe 8
            Files.writeString(directory.resolve("unreferenced.partial"), "unfinished page data")
            runOfflineCli(work, listOf("inspect", "--directory", directory.toString(), "--txid", WireFixture.ids.first().toString())).exit shouldBe 8
            runOfflineCli(work, listOf("inspect", "--directory", directory.toString(), "--txid", WireFixture.ids.last().toString())).exit shouldBe 7
            cli.run(server, args + "--resume").exit shouldBe 0
            val store = EvidenceStore(directory)
            val manifest = store.load()
            manifest.pages.flatMapIndexed { index, _ -> store.readPage(manifest, index) }.map { it.xlog.txid } shouldBe WireFixture.ids.map(Long::toString)
        }
    }

    listOf(Fault.DISCONNECT, Fault.PROFILE_DISCONNECT).forEach { fault ->
        test("interrupted $fault capture keeps committed pages and resumes profiles") {
            val work = tempdir().toPath()
            val directory = work.resolve("capture")
            val cli = ContractCli(work)
            WireFixture(fault).use { server ->
                val args = listOf("capture", "--include-profiles", "--directory", directory.toString()) + evidenceWindow
                cli.run(server, args).exit shouldBe 10
                val checkpoint = EvidenceStore(directory).load()
                checkpoint.pages.sumOf { it.records } shouldBe 2
                checkpoint.executionComplete shouldBe false
                checkpoint.lastFailure shouldBe ErrorCode.IO_ERROR
                cli.run(server, args + "--resume").exit shouldBe 0
                EvidenceStore(directory).load().pages.sumOf { it.records } shouldBe 3
            }
            runOfflineCli(work, listOf("inspect", "--directory", directory.toString(), "--txid", WireFixture.ids.last().toString())).exit shouldBe 0
        }
    }

    listOf(Fault.PROFILE_MISSING, Fault.PROFILE_MISSING_ONCE).forEach { fault ->
        test("missing profile is recorded explicitly and retried on resume with $fault") {
            val work = tempdir().toPath()
            val directory = work.resolve("capture")
            val cli = ContractCli(work)
            WireFixture(fault).use { server ->
                val args = listOf("capture", "--include-profiles", "--directory", directory.toString()) + evidenceWindow
                cli.run(server, args).exit shouldBe 8
                val missing = runOfflineCli(work, listOf("inspect", "--directory", directory.toString(), "--txid", WireFixture.ids[1].toString()))
                missing.exit shouldBe 8
                val inspection = json.decodeFromJsonElement<InspectionDto>(missing.data()).toModel()
                inspection.captureExecutionComplete shouldBe true
                inspection.unavailableProfiles shouldBe 1
                inspection.record.profileStatus shouldBe ProfileAvailability.UNAVAILABLE
                inspection.record.profile shouldBe null
                val expected = if (fault == Fault.PROFILE_MISSING_ONCE) 0 else 8
                cli.run(server, args + "--resume").exit shouldBe expected
                runOfflineCli(work, listOf("inspect", "--directory", directory.toString(), "--txid", WireFixture.ids[1].toString())).exit shouldBe expected
            }
        }
    }

    test("resume refuses a changed text policy or query") {
        val work = tempdir().toPath()
        val directory = work.resolve("capture")
        val cli = ContractCli(work)
        WireFixture().use { server ->
            val args = listOf("capture", "--include-profiles", "--directory", directory.toString()) + evidenceWindow
            cli.run(server, args).exit shouldBe 0
            cli.run(server, args + listOf("--resume", "--include-text")).exit shouldBe 2
            val changed = args.toMutableList().apply { set(indexOf("--object-hash") + 1, "43") }
            cli.run(server, changed + "--resume").exit shouldBe 2
        }
    }
})
