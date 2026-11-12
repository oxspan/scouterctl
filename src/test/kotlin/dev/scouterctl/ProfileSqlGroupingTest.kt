package dev.scouterctl

import dev.scouterctl.adapters.files.EvidenceStore
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.*
import java.nio.file.Files

class ProfileSqlGroupingTest : FunSpec({
    for (text in listOf(false, true)) {
        test("profile get groups 3000 repeated SQL steps with text policy $text") {
            WireFixture(Fault.PROFILE_REPEATED_SQL).use { server ->
                val args = listOf("profile", "get", "--date", "20261101", "--txid", WireFixture.ids.first().toString(), "--group-sql") +
                    if (text) listOf("--include-text") else emptyList()
                val result = ContractCli(tempdir().toPath()).run(server, args)
                result.exit shouldBe 0
                result.stderr shouldBe ""
                val summary = result.data()
                summary["schemaVersion"] shouldBe JsonPrimitive("scouterctl.profile-sql-summary/v1")
                summary["txid"] shouldBe JsonPrimitive(WireFixture.ids.first().toString())
                summary["totalSteps"] shouldBe JsonPrimitive(3004)
                summary["sqlSteps"] shouldBe JsonPrimitive(3000)
                summary["nonSqlSteps"] shouldBe JsonPrimitive(4)
                summary["completeness"] shouldBe JsonPrimitive("unknown")
                summary.containsKey("steps") shouldBe false
                val group = summary["groups"]!!.jsonArray.single().jsonObject
                group["hash"] shouldBe JsonPrimitive(123)
                group["count"] shouldBe JsonPrimitive(3000)
                group["elapsedTotalMs"] shouldBe JsonPrimitive(6000)
                group["maxElapsedMs"] shouldBe JsonPrimitive(2)
                group["errors"] shouldBe JsonPrimitive(30)
                if (text) group["statement"]!!.jsonObject["text"] shouldBe JsonPrimitive("select marker from fixture where id = ?")
                else group["statement"] shouldBe JsonNull
                result.output shouldNotContain "secret-token"
                result.output shouldNotContain "fixture-password"
            }
        }
    }

    test("grouped offline inspection redacts by request and never rewrites stored steps") {
        val work = tempdir().toPath()
        val directory = work.resolve("capture")
        WireFixture().use { server ->
            ContractCli(work).run(server, listOf("capture", "--include-profiles", "--include-text", "--directory", directory.toString()) + evidenceWindow).exit shouldBe 0
        }
        val before = Files.list(directory).use { files -> files.toList().associate { it.fileName.toString() to Files.readString(it) } }
        val args = listOf("inspect", "--directory", directory.toString(), "--txid", WireFixture.ids.first().toString(), "--group-sql")
        for (text in listOf(false, true)) {
            val result = runOfflineCli(work, args + if (text) listOf("--include-text") else emptyList())
            result.exit shouldBe 0
            val inspection = result.data()
            inspection["textIncluded"] shouldBe JsonPrimitive(text)
            val summary = inspection["record"]!!.jsonObject["profile"]!!.jsonObject
            summary["schemaVersion"] shouldBe JsonPrimitive("scouterctl.profile-sql-summary/v1")
            summary["groups"]!!.jsonArray.map { it.jsonObject["hash"]!!.jsonPrimitive.int } shouldBe listOf(456, 123)
            if (!text) result.output shouldNotContain "select marker"
            else summary["groups"]!!.jsonArray.last().jsonObject["statement"]!!.jsonObject["text"] shouldBe
                JsonPrimitive("select marker from fixture where id = ?")
        }
        Files.list(directory).use { files -> files.toList().associate { it.fileName.toString() to Files.readString(it) } } shouldBe before
        val raw = runOfflineCli(work, args.dropLast(1))
        raw.data()["record"]!!.jsonObject["profile"]!!.jsonObject["steps"]!!.jsonArray.size shouldBe 3

        val manifest = EvidenceStore(directory).load()
        Files.writeString(directory.resolve(manifest.pages.last().name), "damaged later page")
        runOfflineCli(work, args).exit shouldBe 10
    }

    for (offline in listOf(false, true)) {
        test("grouped ${if (offline) "inspect" else "profile"} preserves explicit profile truncation") {
            val work = tempdir().toPath()
            WireFixture(Fault.PROFILE_TRUNCATED).use { server ->
                val result = if (offline) {
                    val directory = work.resolve("capture")
                    ContractCli(work).run(server, listOf("capture", "--include-profiles", "--directory", directory.toString()) + evidenceWindow).exit shouldBe 0
                    runOfflineCli(work, listOf("inspect", "--directory", directory.toString(), "--txid", WireFixture.ids.first().toString(), "--group-sql"))
                } else ContractCli(work).run(server, listOf("profile", "get", "--date", "20261101", "--txid", WireFixture.ids.first().toString(), "--group-sql"))
                result.exit shouldBe 8
                result.events.single()["executionComplete"] shouldBe JsonPrimitive(false)
                val profile = if (offline) result.data()["record"]!!.jsonObject["profile"]!!.jsonObject else result.data()
                profile["completeness"] shouldBe JsonPrimitive("truncated")
                profile["groups"]!!.jsonArray.single().jsonObject["count"] shouldBe JsonPrimitive(1)
            }
        }
    }

    test("grouped inspect retains unavailable profiles and refuses text that was not stored") {
        val work = tempdir().toPath()
        val directory = work.resolve("capture")
        WireFixture(Fault.PROFILE_MISSING).use { server ->
            ContractCli(work).run(server, listOf("capture", "--include-profiles", "--directory", directory.toString()) + evidenceWindow).exit shouldBe 8
        }
        val args = listOf("inspect", "--directory", directory.toString(), "--txid", WireFixture.ids[1].toString(), "--group-sql")
        val result = runOfflineCli(work, args)
        result.exit shouldBe 8
        result.data()["record"]!!.jsonObject["profile"] shouldBe JsonNull
        runOfflineCli(work, args + "--include-text").exit shouldBe 2
    }

    for (command in listOf(listOf("doctor"), listOf("capture"), listOf("inspect", "search"), listOf("context", "list"))) {
        test("${command.joinToString(" ")} rejects the SQL grouping option before execution") {
            val result = runOfflineCli(tempdir().toPath(), command + "--group-sql")
            result.exit shouldBe 2
            result.events.single()["error"]!!.jsonObject["message"]!!.jsonPrimitive.content.contains("--group-sql") shouldBe true
        }
    }
})
