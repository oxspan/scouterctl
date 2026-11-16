package dev.scouterctl

import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.*

class AgentDiagnosticsTest : FunSpec({
    test("active request CLI preserves IDs and reveals only opted-in names") {
        val cli = ContractCli(tempdir().toPath())
        WireFixture().use { server ->
            for (includeText in listOf(false, true)) {
                val result = cli.run(server, listOf("agent", "active-requests", "--object-hash", "42") +
                    if (includeText) listOf("--include-text") else emptyList())
                result.exit shouldBe 0
                val request = result.data()["requests"]!!.jsonArray.single().jsonObject
                request["txid"] shouldBe JsonPrimitive(Long.MIN_VALUE.toString())
                request["threadId"] shouldBe JsonPrimitive("9007199254740993")
                request["state"] shouldBe JsonPrimitive("WAITING")
                request["elapsedMs"] shouldBe JsonPrimitive(1250)
                request["threadCpuTimeMs"] shouldBe JsonPrimitive("37")
                request["service"] shouldBe if (includeText) JsonPrimitive("/diagnostic/service") else JsonNull
                request["threadName"] shouldBe if (includeText) JsonPrimitive("diagnostic-worker") else JsonNull
                result.output shouldNotContain "private-"
            }
        }
    }
    test("empty active snapshot succeeds while incomplete snapshots exit partial") {
        val cli = ContractCli(tempdir().toPath())
        for ((fault, size, exit) in listOf(Triple(Fault.ACTIVE_EMPTY, 0, 0), Triple(Fault.ACTIVE_PARTIAL, 1, 8))) {
            WireFixture(fault).use { server ->
                val result = cli.run(server, listOf("agent", "active-requests", "--object-hash", "42"))
                result.exit shouldBe exit
                result.data()["requests"]!!.jsonArray.size shouldBe size
                result.events.single()["executionComplete"] shouldBe JsonPrimitive(exit == 0)
            }
        }
    }
    test("thread dump CLI emits Agent lines without asserting upstream completeness") {
        WireFixture().use { server ->
            val result = ContractCli(tempdir().toPath()).run(server, listOf("agent", "thread-dump", "--object-hash", "42"))
            result.exit shouldBe 0
            result.data()["lines"]!!.jsonArray.map { it.jsonPrimitive.content } shouldBe listOf(
                "\"diagnostic-worker\" #42 WAITING", "    at java.lang.Object.wait(Native Method)",
                "    at example.Worker.await(Worker.java:12)",
            )
            result.data()["completeness"] shouldBe JsonPrimitive("unknown")
        }
    }
    test("live diagnostics reject unavailable Agents and do not disclose attachment errors") {
        val cli = ContractCli(tempdir().toPath())
        for ((fault, command) in listOf(
            Fault.AGENT_OFFLINE to "active-requests", Fault.AGENT_OFFLINE to "thread-dump",
            Fault.AGENT_NO_RESPONSE to "active-requests", Fault.AGENT_NO_RESPONSE to "thread-dump",
            Fault.DIAGNOSTIC_ERROR to "thread-dump", Fault.EMPTY_OBJECTS to "active-requests",
        )) {
            WireFixture(fault).use { server ->
                val result = cli.run(server, listOf("agent", command, "--object-hash", "42"))
                result.exit shouldBe 7
                result.events.single().containsKey("data") shouldBe false
                result.output shouldNotContain "private-"
            }
        }
    }
    test("diagnostic commands require one nonzero target and reject unrelated options") {
        val cli = ContractCli(tempdir().toPath())
        for (args in listOf(
            listOf("agent", "active-requests"), listOf("agent", "thread-dump"),
            listOf("agent", "active-requests", "--object-hash", "0"),
            listOf("agent", "thread-dump", "--object-hash", "0"),
            listOf("agent", "active-requests", "--object-hash", "42", "--key", "secret"),
            listOf("agent", "thread-dump", "--object-hash", "42", "--include-text"),
        )) WireFixture().use { server -> cli.run(server, args).exit shouldBe 2 }
    }
})
