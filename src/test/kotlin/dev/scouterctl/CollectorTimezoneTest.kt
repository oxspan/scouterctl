package dev.scouterctl

import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.*
import java.nio.file.Files

class CollectorTimezoneTest : FunSpec({
    for (command in listOf(listOf("doctor"), listOf("collector", "status"))) {
        test("${command.joinToString(" ")} reports JVM zone and client-derived offset with provenance") {
            WireFixture(Fault.TIMEZONE_SEOUL).use { server ->
                val result = ContractCli(tempdir().toPath()).run(server, command)
                result.exit shouldBe 0
                result.stderr shouldBe ""
                val info = if (command.first() == "doctor") result.data() else result.data()["collector"]!!.jsonObject
                val zone = info["timezone"]!!.jsonObject
                zone["id"] shouldBe JsonPrimitive("Asia/Seoul")
                zone["utcOffsetSeconds"] shouldBe JsonPrimitive(32400)
                zone["referenceTimeEpochMs"] shouldBe JsonPrimitive(WireFixture.baseTime)
                zone["idSource"] shouldBe JsonPrimitive("jvm-property")
                zone["offsetSource"] shouldBe JsonPrimitive("client-zone-rules")
                result.output shouldNotContain "private-zone-env-token"
            }
        }
    }

    test("XLog timezone mismatch fails before a stream and equal-offset aliases remain usable") {
        WireFixture(Fault.TIMEZONE_SEOUL).use { server ->
            val cli = ContractCli(tempdir().toPath())
            val wrong = cli.run(server, listOf("xlog", "search") + evidenceWindow)
            wrong.exit shouldBe 2
            val error = wrong.events.single()["error"]!!.jsonObject
            error["code"] shouldBe JsonPrimitive("INVALID_ARGUMENT")
            error["message"]!!.jsonPrimitive.content.contains("--timezone") shouldBe true
            error["message"]!!.jsonPrimitive.content.contains("Asia/Seoul") shouldBe true

            val alias = cli.run(server, listOf("xlog", "search") + evidenceWindow.map { if (it == "UTC") "Asia/Tokyo" else it })
            alias.exit shouldBe 0
            alias.events.count { it["type"] == JsonPrimitive("record") } shouldBe 3
            alias.events.first()["storageDateVerification"] shouldBe JsonPrimitive("unverified")
        }
    }

    for (profiles in listOf(false, true)) {
        test("timezone mismatch cannot create a ${if (profiles) "profile" else "XLog"} capture") {
            val work = tempdir().toPath()
            val destination = work.resolve("capture")
            WireFixture(Fault.TIMEZONE_SEOUL).use { server ->
                val result = ContractCli(work).run(server, listOf("capture", "--directory", destination.toString()) + evidenceWindow +
                    if (profiles) listOf("--include-profiles") else emptyList())
                result.exit shouldBe 2
                result.events.single()["error"]!!.jsonObject["code"] shouldBe JsonPrimitive("INVALID_ARGUMENT")
                Files.exists(destination) shouldBe false
            }
        }
    }

    for (export in listOf(false, true)) {
        test("counter ${if (export) "export" else "history"} rejects the reported timezone mismatch") {
            val work = tempdir().toPath()
            val destination = work.resolve("counter-capture")
            WireFixture(Fault.TIMEZONE_SEOUL).use { server ->
                val window = listOf("--date", "20260101", "--from", "2026-01-01T00:00:00Z", "--to", "2026-01-01T00:01:00Z",
                    "--timezone", "UTC", "--object-hash", "42")
                val args = if (export) listOf("counter", "export", "--counter", "HeapUsed", "--directory", destination.toString()) + window.drop(2)
                    else listOf("counter", "history", "--counter", "HeapUsed") + window
                val result = ContractCli(work).run(server, args)
                result.exit shouldBe 2
                result.events.single()["error"]!!.jsonObject["code"] shouldBe JsonPrimitive("INVALID_ARGUMENT")
                Files.exists(destination) shouldBe false
            }
        }
    }

    for (fault in listOf(Fault.TIMEZONE_MISSING, Fault.TIMEZONE_INVALID)) {
        test("$fault leaves unknown metadata explicit and preserves unverified searches") {
            WireFixture(fault).use { server ->
                val cli = ContractCli(tempdir().toPath())
                val doctor = cli.run(server, listOf("doctor"))
                doctor.exit shouldBe 0
                doctor.data()["timezone"] shouldBe JsonNull
                doctor.output shouldNotContain "private-"
                val search = cli.run(server, listOf("xlog", "search") + evidenceWindow)
                search.exit shouldBe 0
                search.events.count { it["type"] == JsonPrimitive("record") } shouldBe 3
                search.events.first()["storageDateVerification"] shouldBe JsonPrimitive("unverified")
            }
        }
    }

    for (fault in listOf(Fault.TIMEZONE_TYPE, Fault.TIMEZONE_TIME_TYPE)) {
        test("$fault is a protocol error without exposing environment values") {
            WireFixture(fault).use { server ->
                val result = ContractCli(tempdir().toPath()).run(server, listOf("doctor"))
                result.exit shouldBe 6
                result.events.single()["error"]!!.jsonObject["code"] shouldBe JsonPrimitive("PROTOCOL_ERROR")
                result.output shouldNotContain "private-"
            }
        }
    }
})
