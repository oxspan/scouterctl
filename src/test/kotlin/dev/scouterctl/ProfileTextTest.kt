package dev.scouterctl

import dev.scouterctl.core.*

import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.*

class ProfileTextTest : FunSpec({
    val arguments = listOf("profile", "get", "--date", "20261101", "--txid", WireFixture.ids.first().toString(), "--include-text")

    listOf(Fault.NONE, Fault.MISSING_TEXT, Fault.XLOG_MISSING).forEach { fault ->
        test("opt-in profile resolves available text and preserves unavailable context with $fault") {
            val cli = ContractCli(tempdir().toPath())
            WireFixture(fault).use { server ->
                val result = cli.run(server, arguments)
                result.exit shouldBe 0
                val profile = decodeLiveProfile(result.data())
                profile.txid shouldBe WireFixture.ids.first().toString()
                profile.steps[0] shouldBe ProfileStep("MESSAGE", 1)
                val sql = profile.steps.mapNotNull { it.sql }
                sql[0] shouldBe SqlProfile(123, 7, 0, 1,
                    DictionaryText(123, "select marker from fixture where id = ?"))
                sql[1] shouldBe SqlProfile(456, 11, -789, -3,
                    DictionaryText(456, if (fault == Fault.MISSING_TEXT) null else "insert into fixture(id) values (?)"),
                    DictionaryText(-789, "fixture duplicate key error"))
                profile.transaction shouldBe if (fault == Fault.XLOG_MISSING) ProfileTransaction("unavailable") else
                    ProfileTransaction("available", DictionaryText(10, "/fixture<GET>"), DictionaryText(-789, "fixture duplicate key error"))
                result.data()["steps"]!!.jsonArray[2].jsonObject["sql"]!!.jsonObject["statement"]!!.jsonObject["status"]!!
                    .jsonPrimitive.content shouldBe if (fault == Fault.MISSING_TEXT) "unresolved" else "resolved"
                result.output shouldNotContain "secret-token"
                result.output shouldNotContain "private-address"
                result.stderr shouldBe ""
            }
        }
    }

    test("dictionary batching preserves all 101 SQL statements including hash zero") {
        val cli = ContractCli(tempdir().toPath())
        WireFixture(Fault.MANY_SQL).use { server ->
            val result = cli.run(server, arguments)
            result.exit shouldBe 0
            val profile = decodeLiveProfile(result.data())
            profile.steps.map { it.sql?.statement } shouldBe (0..100).map { DictionaryText(it, "statement $it") }
        }
    }

    test("empty dictionaries preserve service SQL and error hashes as unresolved") {
        val cli = ContractCli(tempdir().toPath())
        WireFixture(Fault.EMPTY_TEXT).use { server ->
            val result = cli.run(server, arguments)
            result.exit shouldBe 0
            val profile = decodeLiveProfile(result.data())
            profile.transaction shouldBe ProfileTransaction("available", DictionaryText(10), DictionaryText(-789))
            profile.steps.mapNotNull { it.sql } shouldBe listOf(
                SqlProfile(123, 7, 0, 1, DictionaryText(123)),
                SqlProfile(456, 11, -789, -3, DictionaryText(456), DictionaryText(-789)))
            val transaction = result.data()["transaction"]!!.jsonObject
            transaction["service"]!!.jsonObject["status"]!!.jsonPrimitive.content shouldBe "unresolved"
            transaction["error"]!!.jsonObject["status"]!!.jsonPrimitive.content shouldBe "unresolved"
        }
    }

    mapOf(Fault.TEXT_WRONG_TYPE to 6, Fault.TEXT_WRONG_HASH to 6, Fault.TEXT_DUPLICATE to 6,
        Fault.TEXT_PACK to 6, Fault.TEXT_DISCONNECT to 10, Fault.TEXT_SESSION to 3, Fault.XLOG_WRONG_ID to 6)
        .forEach { (fault, exit) ->
            test("text lookup failure $fault fails with exit $exit instead of reporting unresolved") {
                val cli = ContractCli(tempdir().toPath())
                WireFixture(fault).use { server ->
                    val result = cli.run(server, arguments)
                    result.exit shouldBe exit
                    result.events.single()["type"]!!.jsonPrimitive.content shouldBe "error"
                    result.output shouldNotContain "fixture duplicate key error"
                    result.stderr shouldBe ""
                }
            }
        }

    test("text opt-in on a different command is rejected") {
        val cli = ContractCli(tempdir().toPath())
        WireFixture().use { server ->
            val result = cli.run(server, listOf("doctor", "--include-text"))
            result.exit shouldBe 2
            result.events.single()["error"]!!.jsonObject["code"]!!.jsonPrimitive.content shouldBe "INVALID_ARGUMENT"
        }
    }
})
