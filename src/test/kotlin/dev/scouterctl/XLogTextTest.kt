package dev.scouterctl

import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.*

class XLogTextTest : FunSpec({
    val search = listOf("xlog", "search", "--date", "20261101",
        "--from", "2026-11-01T00:00:00Z", "--to", "2026-11-01T00:01:00Z",
        "--timezone", "UTC", "--object-hash", "42", "--page-size", "2")

    test("XLog search omits dictionary fields and avoids dictionary requests by default") {
        val cli = ContractCli(tempdir().toPath())
        WireFixture(Fault.STRUCTURAL_ONLY, xlogErrorHash = -789).use { source ->
            val result = cli.run(source, search)
            result.exit shouldBe 0
            val row = result.events.first { it["type"] == JsonPrimitive("record") }["data"]!!.jsonObject
            row["serviceHash"] shouldBe JsonPrimitive(10)
            row["errorHash"] shouldBe JsonPrimitive(-789)
            row.containsKey("service") shouldBe false
            row.containsKey("error") shouldBe false
            result.output shouldNotContain "secret-token"
        }
    }

    for (fault in listOf(Fault.NONE, Fault.EMPTY_TEXT)) {
        test("XLog text opt-in preserves record order and reports dictionary availability with $fault") {
            val cli = ContractCli(tempdir().toPath())
            WireFixture(fault, xlogErrorHash = -789).use { source ->
                val result = cli.run(source, search + "--include-text")
                result.exit shouldBe 0
                result.stderr shouldBe ""
                result.events.last()["type"] shouldBe JsonPrimitive("complete")
                val rows = result.events.filter { it["type"] == JsonPrimitive("record") }.map { it["data"]!!.jsonObject }
                rows.map { it["txid"] } shouldBe WireFixture.ids.map { JsonPrimitive(it.toString()) }
                val service = if (fault == Fault.NONE)
                    """{"hash":10,"text":"/fixture<GET>","status":"resolved"}"""
                    else """{"hash":10,"text":null,"status":"unresolved"}"""
                rows.map { it["service"] } shouldBe List(rows.size) { Json.parseToJsonElement(service) }
                val error = if (fault == Fault.NONE)
                    """{"hash":-789,"text":"fixture duplicate key error","status":"resolved"}"""
                    else """{"hash":-789,"text":null,"status":"unresolved"}"""
                rows.map { it["error"] } shouldBe listOf(Json.parseToJsonElement(error), JsonNull, JsonNull)
                result.output shouldNotContain "secret-token"
            }
        }
    }

    test("A malformed XLog dictionary reply terminates the stream with a protocol error") {
        val cli = ContractCli(tempdir().toPath())
        WireFixture(Fault.TEXT_WRONG_HASH, xlogErrorHash = -789).use { source ->
            val result = cli.run(source, search + "--include-text")
            result.exit shouldBe 6
            result.events.map { it["type"] } shouldBe listOf(JsonPrimitive("start"), JsonPrimitive("error"))
            result.events.last()["error"]!!.jsonObject["code"] shouldBe JsonPrimitive("PROTOCOL_ERROR")
        }
    }
})
