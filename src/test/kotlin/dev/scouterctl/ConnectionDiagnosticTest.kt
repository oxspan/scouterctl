package dev.scouterctl

import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.*

class ConnectionDiagnosticTest : FunSpec({
    val profile = listOf("profile", "get", "--date", "20261101", "--txid",
        WireFixture.ids.first().toString(), "--include-text")

    test("a stalled dictionary response records socket timeout and the incomplete server request") {
        WireFixture(Fault.TEXT_TIMEOUT).use { server ->
            val result = ContractCli(tempdir().toPath()).run(server, profile)
            result.exit shouldBe 5
            val diagnostic = requireNotNull(result.diagnostic)
            diagnostic["process"] shouldBe JsonPrimitive("exited")
            diagnostic["exit"] shouldBe JsonPrimitive(5)
            diagnostic["elapsedMs"]!!.jsonPrimitive.long shouldBeGreaterThanOrEqual 5000L
            diagnostic["error"]!!.jsonObject["reason"] shouldBe JsonPrimitive("socket_timeout")
            val fixture = diagnostic["fixture"]!!.jsonObject
            fixture["command"] shouldBe JsonPrimitive("dictionary")
            fixture["phase"] shouldBe JsonPrimitive("holding_response")
            fixture["failure"] shouldBe JsonPrimitive("none")
            fixture["connections"] shouldBe JsonPrimitive(1)
            fixture["requests"]!!.jsonPrimitive.int shouldBe fixture["completedRequests"]!!.jsonPrimitive.int + 1
            fixture["responsePackets"] shouldBe JsonPrimitive(0)
            diagnostic.toString() shouldNotContain "fixture-password"
        }
    }

    test("dictionary disconnect is distinguishable from timeout without logging profile text") {
        WireFixture(Fault.TEXT_DISCONNECT).use { server ->
            val result = ContractCli(tempdir().toPath()).run(server, profile)
            result.exit shouldBe 10
            val diagnostic = requireNotNull(result.diagnostic)
            diagnostic["error"]!!.jsonObject["reason"] shouldBe JsonPrimitive("transport_or_artifact_io")
            diagnostic["fixture"]!!.jsonObject["command"] shouldBe JsonPrimitive("dictionary")
            diagnostic.toString() shouldNotContain "secret-token"
            diagnostic.toString() shouldNotContain "private-address"
        }
    }

    test("diagnostic projection excludes secrets in all untrusted fields and malformed output") {
        val secret = "private-password-SQL-path-credential"
        val output = buildJsonObject {
            put("type", "error")
            put("error", buildJsonObject {
                put("code", "CONNECTION_FAILED")
                put("message", "Collector connection failed; check --host, --port and TCP access")
                put("detail", secret)
            })
            put("data", secret)
        }.toString()
        val diagnostic = connectionDiagnostic(5, 42, "$secret\n$output\n[$secret", secret)
        diagnostic["error"] shouldBe buildJsonObject {
            put("code", "CONNECTION_FAILED"); put("reason", "socket_failure")
        }
        diagnostic["elapsedMs"] shouldBe JsonPrimitive(42)
        diagnostic["stderrBytes"]!!.jsonPrimitive.int shouldBe secret.toByteArray().size
        diagnostic["fixture"] shouldBe JsonNull
        diagnostic.toString() shouldNotContain secret

        for (error in listOf(
            """{"type":"error","error":{"code":"$secret","message":"$secret"}}""",
            """{"type":"error","error":["$secret"]}""",
            """{"type":"error","error":{"code":{},"message":[]}}""",
            "x".repeat(65_537) + secret,
        )) {
            val unknown = connectionDiagnostic(6, 1, error, secret)
            unknown["error"]!!.jsonObject["code"] shouldBe JsonNull
            unknown["error"]!!.jsonObject["reason"] shouldBe JsonPrimitive("unclassified")
            unknown.toString() shouldNotContain secret
        }
    }

    test("process deadline is separate from a CLI exit and successful invocations produce no diagnostic") {
        val deadline = connectionDiagnostic(null, 15_000, "", "")
        deadline["process"] shouldBe JsonPrimitive("deadline_exceeded")
        deadline["exit"] shouldBe JsonNull
        WireFixture(Fault.MANY_SQL).use { server ->
            val result = ContractCli(tempdir().toPath()).run(server, profile)
            result.exit shouldBe 0
            result.diagnostic shouldBe null
            val fixture = server.diagnostics()
            fixture["requests"] shouldBe fixture["completedRequests"]
            fixture["command"] shouldBe JsonPrimitive("dictionary")
            fixture["responsePackets"] shouldBe JsonPrimitive(1)
        }
    }
})
