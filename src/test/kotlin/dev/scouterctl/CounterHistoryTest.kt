package dev.scouterctl

import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.*

private val historyArgs = listOf("counter", "history", "--object-hash", "42", "--counter", "HeapUsed",
    "--date", "20260101", "--from", "2026-01-01T00:00:00Z", "--to", "2026-01-01T00:01:00Z", "--timezone", "UTC")

class CounterHistoryTest : FunSpec({
    test("history streams dated object samples with exact numeric values and explicit gaps") {
        val cli = ContractCli(tempdir().toPath())
        WireFixture(Fault.AGENT_OFFLINE).use { server ->
            val result = cli.run(server, historyArgs)
            result.exit shouldBe 0
            val start = result.events.first()
            start["schemaVersion"] shouldBe JsonPrimitive("scouterctl.counter-stream/v1")
            start["target"]!!.jsonObject["objName"] shouldBe JsonPrimitive("/historical-fixture")
            start["counter"]!!.jsonObject["unit"] shouldBe JsonPrimitive("MB")
            start["intervalMs"] shouldBe JsonNull
            start["missingValues"] shouldBe JsonPrimitive("omitted")
            val points = result.events.filter { it["type"] == JsonPrimitive("record") }.map { it["data"]!!.jsonObject }
            points.map { it["time"]!!.jsonPrimitive.long } shouldBe listOf(1767225600000L, 1767225610000L, 1767225630000L, 1767225659999L)
            points.map { it["value"] } shouldBe listOf(JsonPrimitive("9007199254740993"), JsonPrimitive("0"), JsonNull, JsonPrimitive("2.5"))
            points.map { it["availability"]!!.jsonPrimitive.content } shouldBe listOf("available", "available", "unsupported", "available")
            result.events.last()["records"] shouldBe JsonPrimitive(4)
            result.events.last()["executionComplete"] shouldBe JsonPrimitive(true)
            result.output shouldNotContain "private-history-text"
            result.stderr shouldBe ""
        }
    }
    test("empty history is complete without manufacturing zero values or claiming coverage") {
        val cli = ContractCli(tempdir().toPath())
        WireFixture(Fault.HISTORY_EMPTY).use { server ->
            val result = cli.run(server, historyArgs)
            result.exit shouldBe 0
            result.events.size shouldBe 2
            result.events.last()["records"] shouldBe JsonPrimitive(0)
            result.events.last()["upstreamCoverage"] shouldBe JsonPrimitive("unknown")
        }
        WireFixture().use { server ->
            val result = cli.run(server, historyArgs.map { if (it == "HeapUsed") "missing" else it })
            result.exit shouldBe 0
            result.events.first()["counter"]!!.jsonObject["unit"] shouldBe JsonNull
            result.events.last()["records"] shouldBe JsonPrimitive(0)
        }
    }
    test("history rejects malformed stored samples without emitting a successful terminal event") {
        val cli = ContractCli(tempdir().toPath())
        for (fault in listOf(Fault.HISTORY_MALFORMED, Fault.HISTORY_TIME_TYPE, Fault.HISTORY_OUTSIDE, Fault.HISTORY_NAN, Fault.HISTORY_TARGET_DUPLICATE)) {
            WireFixture(fault).use { server ->
                val result = cli.run(server, historyArgs)
                result.exit shouldBe 6
                result.events.single()["error"]!!.jsonObject["code"] shouldBe JsonPrimitive("PROTOCOL_ERROR")
                result.output shouldNotContain "private-"
            }
        }
        WireFixture().use { server ->
            cli.run(server, historyArgs.map { if (it == "42") "99" else it }).exit shouldBe 7
        }
    }
    test("history requires an exact counter and a single declared Collector day before connecting") {
        val cli = ContractCli(tempdir().toPath())
        for (args in listOf(
            historyArgs.filter { it != "--counter" && it != "HeapUsed" },
            historyArgs.map { if (it == "HeapUsed") "" else it },
            historyArgs.map { if (it == "2026-01-01T00:01:00Z") "2026-01-02T00:00:01Z" else it },
            historyArgs.map { if (it == "20260101") "20260230" else it },
            historyArgs.map { if (it == "UTC") "America/New_York" else it },
            historyArgs + listOf("--page-size", "1"),
        )) cli.run(args).exit shouldBe 2
    }
})
