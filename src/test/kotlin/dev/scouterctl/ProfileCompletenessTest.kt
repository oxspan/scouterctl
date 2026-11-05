package dev.scouterctl

import dev.scouterctl.adapters.files.EvidenceStore
import dev.scouterctl.adapters.serialization.*
import dev.scouterctl.core.ProfileCompleteness
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.*
import java.nio.file.Files

class ProfileCompletenessTest : FunSpec({
    listOf(Fault.PROFILE_TRUNCATED to "truncated", Fault.PROFILE_OTHER_CONTROL to "unknown",
        Fault.PROFILE_MANY_STEPS to "unknown", Fault.NONE to "unknown").forEach { (fault, status) ->
        test("online profile reports $status for $fault without counting steps as blocks") {
            val work = tempdir().toPath()
            WireFixture(fault).use { server ->
                val result = ContractCli(work).run(server, listOf("profile", "get", "--date", "20261101", "--txid", WireFixture.ids.first().toString()))
                result.exit shouldBe if (status == "truncated") 8 else 0
                result.data()["completeness"]!!.jsonPrimitive.content shouldBe status
                result.events.single()["executionComplete"]!!.jsonPrimitive.boolean shouldBe (status != "truncated")
                result.output shouldNotContain "Profile Truncated"
                if (fault == Fault.PROFILE_MANY_STEPS) result.data()["steps"]!!.jsonArray.size shouldBe 1001
            }
        }
    }
    test("capture persists truncation and offline inspect retains it with or without text") {
        val work = tempdir().toPath()
        val directory = work.resolve("capture")
        WireFixture(Fault.PROFILE_TRUNCATED).use { server ->
            ContractCli(work).run(server, listOf("capture", "--include-profiles", "--include-text",
                "--directory", directory.toString()) + evidenceWindow).exit shouldBe 0
        }
        val store = EvidenceStore(directory)
        val manifest = store.load()
        store.readPage(manifest, 0).first().profile!!.completeness shouldBe ProfileCompleteness.TRUNCATED
        for (text in listOf(false, true)) {
            val args = listOf("inspect", "--directory", directory.toString(), "--txid", WireFixture.ids.first().toString()) +
                if (text) listOf("--include-text") else emptyList()
            val result = runOfflineCli(work, args)
            result.exit shouldBe 8
            result.data()["record"]!!.jsonObject["profile"]!!.jsonObject["completeness"]!!.jsonPrimitive.content shouldBe "truncated"
            result.events.single()["executionComplete"]!!.jsonPrimitive.boolean shouldBe false
        }
    }
    test("legacy capture profiles without completeness decode as unknown and are not rewritten by inspect") {
        val work = tempdir().toPath()
        val directory = work.resolve("capture")
        WireFixture().use { server ->
            ContractCli(work).run(server, listOf("capture", "--include-profiles", "--directory", directory.toString()) + evidenceWindow).exit shouldBe 0
        }
        val store = EvidenceStore(directory)
        val manifest = store.load()
        val pages = manifest.pages.mapIndexed { index, page ->
            val content = Files.readString(directory.resolve(page.name)).replace(",\"completeness\":\"unknown\"", "")
            val hash = java.security.MessageDigest.getInstance("SHA-256").digest(content.toByteArray()).joinToString("") { "%02x".format(it) }
            val name = "page-%06d-%s.jsonl".format(index + 1, hash)
            Files.writeString(directory.resolve(name), content)
            page.copy(name = name, sha256 = hash)
        }
        store.save(manifest.copy(pages = pages))
        val before = pages.associate { it.name to Files.readString(directory.resolve(it.name)) }
        val result = runOfflineCli(work, listOf("inspect", "--directory", directory.toString(), "--txid", WireFixture.ids.first().toString()))
        result.exit shouldBe 0
        result.data()["record"]!!.jsonObject["profile"]!!.jsonObject["completeness"]!!.jsonPrimitive.content shouldBe "unknown"
        pages.associate { it.name to Files.readString(directory.resolve(it.name)) } shouldBe before
    }
})
