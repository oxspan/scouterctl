package dev.scouterctl

import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonObject

class CollectorVersionsTest : FunSpec({
    test("supported Collector releases retain the server version in CLI results") {
        val cli = ContractCli(tempdir().toPath())
        for (version in listOf("2.0.0", "2.0.1", "2.5.0", "2.5.1", "2.6.0", "2.6.1", "2.6.2", "2.7.0", "2.7.1",
            "2.8.1", "2.10.0", "2.10.2", "2.15.0", "2.17.1", "2.20.0", "2.21.1", "2.21.2", "2.21.3")) {
            WireFixture(collectorVersion = "$version build info").use { server ->
                val result = cli.run(server, listOf("doctor"))
                result.exit shouldBe 0
                result.data()["version"]!!.jsonPrimitive.content shouldBe "$version build info"
            }
        }
    }
    test("unverified releases and malformed versions remain rejected") {
        val cli = ContractCli(tempdir().toPath())
        for (version in listOf("2.8.0", "2.8.10", "2.21.4", "2.8.1-SNAPSHOT", "v2.8.1", "unknown", "")) {
            WireFixture(collectorVersion = version).use { server ->
                val result = cli.run(server, listOf("doctor"))
                result.exit shouldBe 9
                result.events.single()["error"]!!.jsonObject["code"]!!.jsonPrimitive.content shouldBe "VERSION_MISMATCH"
            }
        }
    }
})
