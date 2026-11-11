package dev.scouterctl

import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.*

private data class AgentFailureCase(val fault: Fault, val command: String, val hash: String, val exit: Int)

class AgentInspectionTest : FunSpec({
    test("Agent status selects JVM fields without exposing system properties") {
        val cli = ContractCli(tempdir().toPath())
        WireFixture().use { server ->
            val result = cli.run(server, listOf("agent", "status", "--object-hash", "42"))
            result.exit shouldBe 0
            result.data()["agent"]!!.jsonObject["version"] shouldBe JsonPrimitive("2.8.1")
            result.data()["jvmAvailability"] shouldBe JsonPrimitive("available")
            result.data()["jvm"]!!.jsonObject["javaVersion"] shouldBe JsonPrimitive("1.8.0_502")
            result.data()["jvm"]!!.jsonObject["vmName"] shouldBe JsonPrimitive("OpenJDK 64-Bit Server VM")
            result.output shouldNotContain "private-"
            result.output shouldNotContain "/private/"
        }
    }
    test("Agent settings retain safe values and hide current and default sensitive values") {
        val cli = ContractCli(tempdir().toPath())
        WireFixture().use { server ->
            val result = cli.run(server, listOf("agent", "config", "--object-hash", "42"))
            result.exit shouldBe 0
            val settings = result.data()["settings"]!!.jsonArray.map { it.jsonObject }
            val visible = mapOf(
                "net_collector_tcp_port" to ("6100" to "6100"),
                "trace_sql_parameter_max_length" to ("0" to "20"),
                "profile_http_parameter_enabled" to ("true" to "false"),
                "profile_http_querystring_enabled" to ("true" to "false"),
                "net_collector_ip" to ("collector.example.test" to "127.0.0.1"),
                "obj_name" to ("fixture" to ""),
                "obj_type" to ("java" to ""),
            )
            settings.map { it["key"]!!.jsonPrimitive.content } shouldBe (visible.keys + listOf("db_dir", "smtp_password")).sorted()
            settings.forEach { setting ->
                val expected = visible[setting["key"]!!.jsonPrimitive.content]
                setting["value"] shouldBe (expected?.first?.let(::JsonPrimitive) ?: JsonNull)
                setting["defaultValue"] shouldBe (expected?.second?.let(::JsonPrimitive) ?: JsonNull)
                setting["redacted"] shouldBe JsonPrimitive(expected == null)
            }
            result.output shouldNotContain "private-"
            result.output shouldNotContain "/private/"
            val selected = cli.run(server, listOf("agent", "config", "--object-hash", "42", "--key", "smtp_password"))
            selected.exit shouldBe 0
            selected.data()["settings"]!!.jsonArray.single().jsonObject["value"] shouldBe JsonNull
            cli.run(server, listOf("agent", "config", "--object-hash", "42", "--key", "missing")).exit shouldBe 7
        }
    }
    for ((fault, availability) in listOf(Fault.AGENT_OFFLINE to "offline", Fault.AGENT_NO_RESPONSE to "unavailable")) {
        test("Agent status reports $availability while settings fail as unavailable") {
            val cli = ContractCli(tempdir().toPath())
            WireFixture(fault).use { server ->
                val result = cli.run(server, listOf("agent", "status", "--object-hash", "42"))
                result.exit shouldBe 0
                result.data()["jvmAvailability"] shouldBe JsonPrimitive(availability)
                result.data()["jvm"] shouldBe JsonNull
                cli.run(server, listOf("agent", "config", "--object-hash", "42")).exit shouldBe 7
            }
        }
    }
    test("Agent reads reject unknown or duplicate identities and malformed replies") {
        val cli = ContractCli(tempdir().toPath())
        for ((fault, command, hash, exit) in listOf(
            AgentFailureCase(Fault.NONE, "status", "99", 7), AgentFailureCase(Fault.NONE, "config", "99", 7),
            AgentFailureCase(Fault.AGENT_DUPLICATE, "status", "42", 6),
            AgentFailureCase(Fault.AGENT_JVM_MALFORMED, "status", "42", 6),
            AgentFailureCase(Fault.CONFIG_MALFORMED, "config", "42", 6),
            AgentFailureCase(Fault.CONFIG_INVALID_VISIBLE, "config", "42", 6),
            AgentFailureCase(Fault.CONFIG_INVALID_AGENT_TEXT, "config", "42", 6))) {
            WireFixture(fault).use { server ->
                val result = cli.run(server, listOf("agent", command, "--object-hash", hash))
                result.exit shouldBe exit
                result.events.single().containsKey("data") shouldBe false
                result.output shouldNotContain "private-"
            }
        }
    }
    test("Agent inspection requires an explicit valid hash and accepts only its options") {
        val cli = ContractCli(tempdir().toPath())
        for (args in listOf(listOf("agent", "status"), listOf("agent", "config"),
            listOf("agent", "status", "--object-hash", "bad"),
            listOf("agent", "status", "--object-hash", "42", "--key", "db_dir"),
            listOf("agent", "config", "--object-hash", "42", "--include-text"),
            listOf("collector", "status", "--object-hash", "42"))) {
            cli.run(args).exit shouldBe 2
        }
    }
})
