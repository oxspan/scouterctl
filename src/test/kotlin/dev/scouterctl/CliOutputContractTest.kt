package dev.scouterctl

import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.*

class CliOutputContractTest : FunSpec({
    test("single-result envelopes distinguish available data from complete execution") {
        val cli = ContractCli(tempdir().toPath())
        val empty = cli.run(listOf("context", "list"))
        empty.exit shouldBe 0
        empty.stderr shouldBe ""
        empty.events.single().let { result ->
            result["schemaVersion"] shouldBe JsonPrimitive("scouterctl.result/v1")
            result["ok"] shouldBe JsonPrimitive(true)
            result["executionComplete"] shouldBe JsonPrimitive(true)
            result["data"] shouldBe Json.parseToJsonElement(
                """{"schemaVersion":"scouterctl.contexts/v1","current":null,"contexts":{}}""",
            )
        }
        WireFixture(Fault.PROFILE_TRUNCATED).use { source ->
            val partial = cli.run(source, listOf("profile", "get", "--date", "20261101", "--txid", WireFixture.ids.first().toString()))
            partial.exit shouldBe 8
            partial.stderr shouldBe ""
            partial.events.single().let { result ->
                result["schemaVersion"] shouldBe JsonPrimitive("scouterctl.result/v1")
                result["ok"] shouldBe JsonPrimitive(true)
                result["executionComplete"] shouldBe JsonPrimitive(false)
                result["data"]!!.jsonObject["txid"] shouldBe JsonPrimitive(WireFixture.ids.first().toString())
                result["data"]!!.jsonObject["completeness"] shouldBe JsonPrimitive("truncated")
                result.containsKey("error") shouldBe false
            }
        }
    }

    test("parser and command failures use the same machine-readable error envelope") {
        val cli = ContractCli(tempdir().toPath())
        for (args in listOf(listOf("doctor", "--unknown-option"), listOf("raw", "call"))) {
            val result = cli.run(args)
            result.exit shouldBe 2
            result.stderr shouldBe ""
            result.events.single().let { error ->
                error["schemaVersion"] shouldBe JsonPrimitive("scouterctl.error/v1")
                error["type"] shouldBe JsonPrimitive("error")
                error["ok"] shouldBe JsonPrimitive(false)
                error["error"]!!.jsonObject["code"] shouldBe JsonPrimitive("INVALID_ARGUMENT")
                error["error"]!!.jsonObject["message"]!!.jsonPrimitive.isString shouldBe true
                error.containsKey("data") shouldBe false
            }
        }
    }

    test("stream completion and partial termination have distinct terminal contracts") {
        val cli = ContractCli(tempdir().toPath())
        val query = listOf("xlog", "search", "--date", "20261101", "--from", "2026-11-01T00:00:00Z",
            "--to", "2026-11-01T00:01:00Z", "--timezone", "UTC", "--object-hash", "42", "--page-size", "2")
        WireFixture().use { source ->
            for (limited in listOf(false, true)) {
                val result = cli.run(source, query + if (limited) listOf("--max-pages", "1") else emptyList())
                result.exit shouldBe if (limited) 8 else 0
                result.stderr shouldBe ""
                result.events.first()["schemaVersion"] shouldBe JsonPrimitive("scouterctl.stream/v1")
                result.events.first()["type"] shouldBe JsonPrimitive("start")
                val records = result.events.drop(1).dropLast(1)
                records.size shouldBe if (limited) 2 else 3
                records.forEachIndexed { index, record ->
                    record["type"] shouldBe JsonPrimitive("record")
                    record["data"]!!.jsonObject["txid"] shouldBe JsonPrimitive(WireFixture.ids[index].toString())
                }
                result.events.last().let { terminal ->
                    if (limited) {
                        terminal["schemaVersion"] shouldBe JsonPrimitive("scouterctl.error/v1")
                        terminal["type"] shouldBe JsonPrimitive("error")
                        terminal["ok"] shouldBe JsonPrimitive(false)
                        terminal["error"]!!.jsonObject["code"] shouldBe JsonPrimitive("PARTIAL_RESULT")
                    } else {
                        terminal["type"] shouldBe JsonPrimitive("complete")
                        terminal["executionComplete"] shouldBe JsonPrimitive(true)
                        terminal["upstreamCoverage"] shouldBe JsonPrimitive("unknown")
                        terminal["sampling"] shouldBe JsonPrimitive("unknown")
                    }
                }
            }
        }
    }
})
