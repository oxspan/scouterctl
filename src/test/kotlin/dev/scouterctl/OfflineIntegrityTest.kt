package dev.scouterctl

import dev.scouterctl.core.*
import dev.scouterctl.adapters.files.*
import dev.scouterctl.adapters.serialization.*

import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.*
import java.nio.file.Files

class OfflineIntegrityTest : FunSpec({
    listOf("checksum", "missing-file", "traversal", "symlink", "profile-id", "duplicate", "record-count", "object-scope", "availability").forEach { damage ->
        test("inspect and resume reject $damage before returning trusted data") {
            val work = tempdir().toPath()
            val directory = work.resolve("capture")
            val cli = ContractCli(work)
            WireFixture().use { server ->
                val args = listOf("capture", "--include-profiles", "--directory", directory.toString()) + evidenceWindow
                cli.run(server, args).exit shouldBe 0
                val store = EvidenceStore(directory)
                val manifest = store.load()
                val first = directory.resolve(manifest.pages.first().name)
                when (damage) {
                    "checksum" -> Files.writeString(first, "corrupted")
                    "missing-file" -> Files.delete(first)
                    "traversal" -> store.save(manifest.copy(pages = manifest.pages.mapIndexed { i, page ->
                        if (i == 0) page.copy(name = "../outside.jsonl") else page
                    }))
                    "symlink" -> {
                        val outside = work.resolve("outside.jsonl")
                        Files.move(first, outside)
                        Files.createSymbolicLink(first, outside)
                    }
                    "profile-id" -> {
                        val rows = store.readPage(manifest, 0).map { it.copy(profile = it.profile!!.copy(txid = "99")) }
                        store.save(manifest.copy(pages = listOf(store.writePage(0, rows)) + manifest.pages.drop(1)))
                    }
                    "object-scope" -> {
                        val rows = store.readPage(manifest, 0).map { it.copy(xlog = it.xlog.copy(objHash = 99)) }
                        store.save(manifest.copy(pages = listOf(store.writePage(0, rows)) + manifest.pages.drop(1)))
                    }
                    "availability" -> {
                        val rows = store.readPage(manifest, 0).map { it.copy(profileStatus = ProfileAvailability.UNAVAILABLE) }
                        store.save(manifest.copy(pages = listOf(store.writePage(0, rows)) + manifest.pages.drop(1)))
                    }
                    "duplicate" -> {
                        val duplicate = store.readPage(manifest, 0).take(1)
                        store.save(manifest.copy(pages = manifest.pages.take(1) + store.writePage(1, duplicate)))
                    }
                    "record-count" -> store.save(manifest.copy(pages = manifest.pages.mapIndexed { i, page ->
                        if (i == 0) page.copy(records = 0) else page
                    }))
                    else -> error("Unknown test damage")
                }
                val inspected = runOfflineCli(work, listOf("inspect", "--directory", directory.toString(), "--txid", WireFixture.ids.last().toString()))
                inspected.exit shouldBe 10
                inspected.events.single()["type"]!!.jsonPrimitive.content shouldBe "error"
                cli.run(server, args + "--resume").exit shouldBe 10
            }
        }
    }

    test("legacy capture stays v1 and inspect rejects unsupported format explicitly") {
        val work = tempdir().toPath()
        val directory = work.resolve("legacy")
        WireFixture().use { server ->
            ContractCli(work).run(server, listOf("capture", "--directory", directory.toString()) + evidenceWindow).exit shouldBe 0
        }
        json.decodeFromString<CaptureManifestDto>(Files.readString(directory.resolve("manifest.json"))).toModel().schemaVersion shouldBe "scouterctl.capture/v1"
        val result = runOfflineCli(work, listOf("inspect", "--directory", directory.toString(), "--txid", WireFixture.ids.first().toString()))
        result.exit shouldBe 2
        result.events.single()["error"]!!.jsonObject["code"]!!.jsonPrimitive.content shouldBe "INVALID_ARGUMENT"
    }

    listOf("text-without-profiles", "profiles-on-doctor", "oversized-page").forEach { combination ->
        test("unsupported evidence option combination $combination is rejected with otherwise valid input") {
            val work = tempdir().toPath()
            val captureArgs = listOf("capture", "--directory", work.resolve("capture").toString()) + evidenceWindow
            val args = when (combination) {
                "text-without-profiles" -> captureArgs + "--include-text"
                "profiles-on-doctor" -> listOf("doctor", "--include-profiles")
                "oversized-page" -> (captureArgs + "--include-profiles").toMutableList().apply { set(indexOf("--page-size") + 1, "101") }
                else -> error("Unknown option combination")
            }
            WireFixture().use { server ->
                val result = ContractCli(work).run(server, args)
                result.exit shouldBe 2
                result.events.single()["error"]!!.jsonObject["code"]!!.jsonPrimitive.content shouldBe "INVALID_ARGUMENT"
            }
        }
    }
})
