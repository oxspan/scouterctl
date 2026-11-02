package dev.scouterctl

import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.*
import java.net.InetAddress
import java.net.ServerSocket

class ConnectionInputTest : FunSpec({
    test("unresolvable Collector host is a connection failure rather than artifact I/O") {
        val result = ContractCli(tempdir().toPath()).run(listOf("doctor", "--user", "guest",
            "--host", "scouterctl.invalid", "--timeout-ms", "100"))
        result.exit shouldBe 5
        result.events.single()["error"]!!.jsonObject["code"]!!.jsonPrimitive.content shouldBe "CONNECTION_FAILED"
        result.output shouldNotContain "fixture-password"
        result.stderr shouldBe ""
    }

    listOf("", "   ", "https://127.0.0.1:6100", "collector host").forEachIndexed { index, host ->
        test("invalid host form $index is rejected before credentials or connection") {
            val result = ContractCli(tempdir().toPath()).run(listOf("doctor", "--user", "guest", "--host", host), null)
            result.exit shouldBe 2
            result.events.single()["error"]!!.jsonObject["code"]!!.jsonPrimitive.content shouldBe "INVALID_ARGUMENT"
            result.events.single()["error"]!!.jsonObject["message"]!!.jsonPrimitive.content shouldContain "--host"
            result.stderr shouldBe ""
        }
    }

    test("blank Collector account is rejected before missing password") {
        val result = ContractCli(tempdir().toPath()).run(listOf("doctor", "--user", " "), null)
        result.exit shouldBe 2
        result.events.single()["error"]!!.jsonObject["message"]!!.jsonPrimitive.content shouldContain "--user"
    }

    test("noninteractive missing password returns one structured input error") {
        val result = ContractCli(tempdir().toPath()).run(listOf("doctor", "--user", "guest"), null)
        result.exit shouldBe 2
        result.events.single()["error"]!!.jsonObject["code"]!!.jsonPrimitive.content shouldBe "INVALID_ARGUMENT"
        result.events.single()["error"]!!.jsonObject["message"]!!.jsonPrimitive.content shouldContain "SCOUTERCTL_PASSWORD"
        result.stderr shouldBe ""
    }

    test("Collector response timeout retains connection failure exit") {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { listening ->
            val result = ContractCli(tempdir().toPath()).run(listening.localPort, listOf("doctor", "--timeout-ms", "100"))
            result.exit shouldBe 5
            result.events.single()["error"]!!.jsonObject["code"]!!.jsonPrimitive.content shouldBe "CONNECTION_FAILED"
            result.stderr shouldBe ""
        }
    }

    test("authenticated Collector without objects returns successful empty discovery") {
        WireFixture(Fault.EMPTY_OBJECTS).use { server ->
            val result = ContractCli(tempdir().toPath()).run(server.port, listOf("object", "list"))
            result.exit shouldBe 0
            result.events.single()["data"]!!.jsonArray.size shouldBe 0
            result.events.single()["executionComplete"]!!.jsonPrimitive.boolean shouldBe true
            result.stderr shouldBe ""
        }
    }
})
