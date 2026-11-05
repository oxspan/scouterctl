package dev.scouterctl

import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.*

class CollectorInspectionTest : FunSpec({
    test("Collector status reports heap bytes and server time with source metadata") {
        val cli = ContractCli(tempdir().toPath())
        WireFixture().use { server ->
            val result = cli.run(server, listOf("collector", "status"))
            result.exit shouldBe 0
            result.data()["usedHeapBytes"] shouldBe JsonPrimitive(1024)
            result.data()["committedHeapBytes"] shouldBe JsonPrimitive(4096)
            result.data()["serverTimeEpochMs"] shouldBe JsonPrimitive(WireFixture.baseTime)
            result.data()["collector"]!!.jsonObject["version"] shouldBe JsonPrimitive("2.21.3 test")
        }
    }
    test("settings show selected operational values while hiding unknown values and defaults") {
        val cli = ContractCli(tempdir().toPath())
        WireFixture().use { server ->
            val result = cli.run(server, listOf("collector", "config"))
            result.exit shouldBe 0
            val settings = result.data()["settings"]!!.jsonArray.map { it.jsonObject }
            settings.map { it["key"]!!.jsonPrimitive.content } shouldBe listOf("db_dir", "net_tcp_listen_port", "smtp_password")
            settings.filter { it["key"] != JsonPrimitive("net_tcp_listen_port") }.forEach {
                it["value"] shouldBe JsonNull
                it["defaultValue"] shouldBe JsonNull
                it["redacted"] shouldBe JsonPrimitive(true)
            }
            result.output shouldNotContain "private-"
            result.output shouldNotContain "/private/"
            val single = cli.run(server, listOf("collector", "config", "--key", "net_tcp_listen_port"))
            single.exit shouldBe 0
            single.data()["settings"]!!.jsonArray.single().jsonObject.let {
                it["value"] shouldBe JsonPrimitive("6100")
                it["defaultValue"] shouldBe JsonPrimitive("6100")
                it["redacted"] shouldBe JsonPrimitive(false)
            }
            cli.run(server, listOf("collector", "config", "--key", "missing_setting")).exit shouldBe 7
        }
    }
    test("invalid status and settings responses fail without leaking their values") {
        val cli = ContractCli(tempdir().toPath())
        for ((fault, command) in listOf(Fault.STATUS_MALFORMED to "status", Fault.CONFIG_MALFORMED to "config",
            Fault.CONFIG_DUPLICATE to "config", Fault.CONFIG_INVALID_VISIBLE to "config")) {
            WireFixture(fault).use { server ->
                val result = cli.run(server, listOf("collector", command))
                result.exit shouldBe 6
                result.events.single()["error"]!!.jsonObject["code"] shouldBe JsonPrimitive("PROTOCOL_ERROR")
                result.output shouldNotContain "private-"
                result.events.single().containsKey("data") shouldBe false
            }
        }
    }
    test("inspection rejects unrelated options and malformed keys before connecting") {
        val cli = ContractCli(tempdir().toPath())
        for (args in listOf(listOf("collector", "status", "--key", "db_dir"),
            listOf("collector", "status", "--date", "20261106"),
            listOf("collector", "config", "--include-text"),
            listOf("collector", "config", "--key", "bad key"),
            listOf("doctor", "--key", "db_dir"))) {
            cli.run(args).exit shouldBe 2
        }
    }
})
