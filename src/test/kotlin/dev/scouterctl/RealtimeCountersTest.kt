package dev.scouterctl

import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.*

class RealtimeCountersTest : FunSpec({
    test("counter list distinguishes zero, missing, unknown-unit and unsupported values") {
        val cli = ContractCli(tempdir().toPath())
        WireFixture().use { server ->
            val result = cli.run(server, listOf("counter", "list", "--object-hash", "42"))
            result.exit shouldBe 0
            val counters = result.data()["counters"]!!.jsonArray.map { it.jsonObject }.associateBy { it["name"]!!.jsonPrimitive.content }
            counters.keys.toList() shouldBe listOf("GcCount", "HeapUsed", "ProcCpu", "pluginCounter", "pluginText")
            counters.getValue("HeapUsed")["value"] shouldBe JsonPrimitive("9007199254740993")
            counters.getValue("HeapUsed")["unit"] shouldBe JsonPrimitive("MB")
            counters.getValue("ProcCpu")["value"] shouldBe JsonPrimitive("0.0")
            counters.getValue("ProcCpu")["availability"] shouldBe JsonPrimitive("available")
            counters.getValue("GcCount")["value"] shouldBe JsonNull
            counters.getValue("GcCount")["availability"] shouldBe JsonPrimitive("unavailable")
            counters.getValue("pluginCounter")["unit"] shouldBe JsonNull
            counters.getValue("pluginCounter")["value"] shouldBe JsonPrimitive("2.5")
            counters.getValue("pluginText")["value"] shouldBe JsonNull
            counters.getValue("pluginText")["availability"] shouldBe JsonPrimitive("unsupported")
            result.output shouldNotContain "private-counter-value"
        }
    }
    test("counter get selects an exact counter using Collector custom units") {
        val cli = ContractCli(tempdir().toPath())
        WireFixture(Fault.COUNTER_CUSTOM).use { server ->
            val result = cli.run(server, listOf("counter", "get", "--object-hash", "42", "--counter", "HeapUsed"))
            result.exit shouldBe 0
            val counter = result.data()["counters"]!!.jsonArray.single().jsonObject
            counter["name"] shouldBe JsonPrimitive("HeapUsed")
            counter["unit"] shouldBe JsonPrimitive("bytes")
            counter["displayName"] shouldBe JsonPrimitive("Custom heap")
        }
    }
    for (fault in listOf(Fault.AGENT_OFFLINE, Fault.COUNTER_EMPTY)) {
        test("known counters remain unavailable with $fault") {
            val cli = ContractCli(tempdir().toPath())
            WireFixture(fault).use { server ->
                val result = cli.run(server, listOf("counter", "get", "--object-hash", "42", "--counter", "HeapUsed"))
                result.exit shouldBe 0
                val counter = result.data()["counters"]!!.jsonArray.single().jsonObject
                counter["value"] shouldBe JsonNull
                counter["availability"] shouldBe JsonPrimitive("unavailable")
            }
        }
    }
    test("unknown counter and target return not found") {
        val cli = ContractCli(tempdir().toPath())
        WireFixture().use { server ->
            cli.run(server, listOf("counter", "get", "--object-hash", "42", "--counter", "missing")).exit shouldBe 7
            cli.run(server, listOf("counter", "list", "--object-hash", "99")).exit shouldBe 7
        }
    }
    test("counter reads reject malformed lists, duplicates, nonfinite numbers and XML entities") {
        val cli = ContractCli(tempdir().toPath())
        for (fault in listOf(Fault.COUNTER_MALFORMED, Fault.COUNTER_DUPLICATE, Fault.COUNTER_NAN, Fault.COUNTER_XML_DTD)) {
            WireFixture(fault).use { server ->
                val result = cli.run(server, listOf("counter", "list", "--object-hash", "42"))
                result.exit shouldBe 6
                result.events.single()["error"]!!.jsonObject["code"] shouldBe JsonPrimitive("PROTOCOL_ERROR")
                result.output shouldNotContain "private-"
            }
        }
    }
    test("counter commands require an explicit target and exact selection for get") {
        val cli = ContractCli(tempdir().toPath())
        for (args in listOf(listOf("counter", "list"), listOf("counter", "get", "--object-hash", "42"),
            listOf("counter", "list", "--object-hash", "42", "--counter", "HeapUsed"),
            listOf("counter", "get", "--object-hash", "42", "--counter", ""),
            listOf("counter", "list", "--object-hash", "42", "--key", "HeapUsed"),
            listOf("doctor", "--counter", "HeapUsed"))) cli.run(args).exit shouldBe 2
    }
})
