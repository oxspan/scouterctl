package dev.scouterctl

import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.*
import java.nio.file.Files

class ProfileTimingTest : FunSpec({
    val txid = WireFixture.ids.first().toString()
    val profile = listOf("profile", "get", "--date", "20261101", "--txid", txid)

    test("live profiles expose recorded timing and network outcomes without targets") {
        val cli = ContractCli(tempdir().toPath())
        WireFixture(Fault.PROFILE_NETWORK).use { server ->
            val result = cli.run(server, profile)
            result.exit shouldBe 0
            val steps = result.data()["steps"]!!.jsonArray.map { it.jsonObject }.associateBy { it["type"]!!.jsonPrimitive.content }
            steps.getValue("HASHED_MESSAGE")["startTimeMs"] shouldBe JsonPrimitive(0)
            steps.getValue("SQL3")["startTimeMs"] shouldBe JsonPrimitive(350)
            steps.getValue("SQL3")["sql"]!!.jsonObject["elapsedMs"] shouldBe JsonPrimitive(7)
            for ((type, timing) in mapOf("SOCKET" to listOf(400, 25, -510), "APICALL" to listOf(430, 180, -519),
                "APICALL2" to listOf(650, 50, 0))) {
                val step = steps.getValue(type)
                step["parent"] shouldBe JsonPrimitive(1)
                listOf(step["startTimeMs"], step["elapsedMs"], step["errorHash"]) shouldBe timing.map(::JsonPrimitive)
                step.containsKey("target") shouldBe false
            }
            val control = steps.getValue("CONTROL")
            control.containsKey("startTimeMs") shouldBe false
            control.containsKey("parent") shouldBe false
            result.output shouldNotContain "192.0.2.17"
            result.output shouldNotContain "internal-target.example"
            result.output shouldNotContain "private-control-message"
        }
    }

    test("only the live text opt-in exposes recorded socket and API targets") {
        val cli = ContractCli(tempdir().toPath())
        WireFixture(Fault.PROFILE_NETWORK).use { server ->
            val result = cli.run(server, profile + "--include-text")
            result.exit shouldBe 0
            val steps = result.data()["steps"]!!.jsonArray.map { it.jsonObject }.associateBy { it["type"]!!.jsonPrimitive.content }
            steps.getValue("SOCKET")["target"] shouldBe Json.parseToJsonElement("""{"address":"192.0.2.17","port":443}""")
            steps.getValue("APICALL")["target"] shouldBe Json.parseToJsonElement("""{"address":"internal-target.example:9443"}""")
            steps.getValue("APICALL2").containsKey("target") shouldBe false
            result.output shouldNotContain "private-control-message"
        }
    }

    test("capturing a profile with network steps preserves the v3 step layout and excludes targets") {
        val work = tempdir().toPath()
        val directory = work.resolve("capture")
        WireFixture(Fault.PROFILE_NETWORK).use { server ->
            val args = listOf("capture", "--include-profiles", "--include-text", "--directory", directory.toString()) + evidenceWindow
            val capture = ContractCli(work).run(server, args)
            capture.exit shouldBe 0
            capture.data()["schemaVersion"] shouldBe JsonPrimitive("scouterctl.capture/v3")
            val page = capture.data()["pages"]!!.jsonArray.first().jsonObject["name"]!!.jsonPrimitive.content
            val record = Json.parseToJsonElement(Files.readAllLines(directory.resolve(page)).first()).jsonObject
            val steps = record["profile"]!!.jsonObject["steps"]!!.jsonArray
            steps.map { it.jsonObject.keys }.distinct() shouldBe listOf(setOf("type", "order", "sql"))
            val result = runOfflineCli(work, listOf("inspect", "--directory", directory.toString(), "--txid", txid, "--include-text"))
            result.exit shouldBe 0
            result.output shouldNotContain "internal-target.example"
            result.output shouldNotContain "192.0.2.17"
        }
    }
})
