package dev.scouterctl

import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE

class ContextCliTest : FunSpec({
    test("busy config refuses writes without losing existing data") {
        val work = tempdir().toPath()
        val cli = ContractCli(work)
        cli.run(listOf("context", "add", "--name", "local", "--user", "guest"), null).exit shouldBe 0
        val before = Files.readString(work.resolve("config.json"))
        FileChannel.open(work.resolve("config.json.lock"), CREATE, WRITE).use { channel ->
            channel.lock().use {
                cli.run(listOf("context", "use", "--name", "local"), null).exit shouldBe 10
                Files.readString(work.resolve("config.json")) shouldBe before
            }
        }
        cli.run(listOf("context", "use", "--name", "local"), null).exit shouldBe 0
    }

    test("saved contexts survive processes and explicit selection and flags override the active endpoint") {
        val work = tempdir().toPath()
        val cli = ContractCli(work)
        WireFixture().use { first ->
            WireFixture(Fault.AUTH).use { second ->
                cli.run(listOf("context", "add", "--name", "local", "--host", "127.0.0.1",
                    "--port", first.port.toString(), "--user", "guest")).exit shouldBe 0
                cli.run(listOf("context", "add", "--name", "other", "--port", second.port.toString(),
                    "--user", "other-user")).exit shouldBe 0
                val listed = cli.run(listOf("context", "list"), null).data()
                listed["current"] shouldBe JsonNull
                listed["contexts"]!!.jsonObject.keys shouldBe setOf("local", "other")
                cli.run(listOf("context", "use", "--name", "other"), null).exit shouldBe 0
                cli.run(listOf("doctor")).exit shouldBe 3
                cli.run(listOf("doctor", "--context", "local")).exit shouldBe 0
                cli.run(listOf("doctor", "--port", first.port.toString(), "--user", "guest")).exit shouldBe 0
                cli.run(listOf("doctor", "--context", "missing")).exit shouldBe 2
                cli.run(listOf("context", "list"), null).data()["current"]!!.jsonPrimitive.content shouldBe "other"
                Files.readString(work.resolve("config.json")) shouldNotContain "fixture-password"
                Files.readString(work.resolve("config.json")) shouldNotContain scouter.util.CipherUtil.sha256("fixture-password")
            }
        }
    }

    test("invalid or duplicate changes preserve the saved selection and complete file") {
        val work = tempdir().toPath()
        val cli = ContractCli(work)
        cli.run(listOf("context", "add", "--name", "local", "--user", "guest"), null).exit shouldBe 0
        cli.run(listOf("context", "use", "--name", "local"), null).exit shouldBe 0
        val before = Files.readString(work.resolve("config.json"))
        listOf(
            listOf("context", "add", "--name", "local", "--user", "replacement"),
            listOf("context", "use", "--name", "missing"),
            listOf("context", "add", "--name", "../escape", "--user", "guest"),
            listOf("context", "add", "--name", "bad", "--host", "https://example.com", "--user", "guest"),
            listOf("context", "add", "--name", "bad"),
        ).forEach { args ->
            cli.run(args, null).exit shouldBe 2
            Files.readString(work.resolve("config.json")) shouldBe before
        }
    }

    test("corrupt unsupported and credential-bearing config fail closed without echoing contents") {
        val work = tempdir().toPath()
        val cli = ContractCli(work)
        listOf(
            "not-json private-sentinel",
            """{"schemaVersion":"future","contexts":{}}""",
            """{"schemaVersion":"scouterctl.contexts/v1","contexts":{},"password":"private-sentinel"}""",
            """{"schemaVersion":"scouterctl.contexts/v1","current":"missing","contexts":{}}""",
        ).forEach { raw ->
            Files.writeString(work.resolve("config.json"), raw)
            val result = cli.run(listOf("context", "list"), null)
            result.exit shouldBe 2
            result.output shouldNotContain "private-sentinel"
            result.stderr shouldBe ""
            Files.readString(work.resolve("config.json")) shouldBe raw
        }
        WireFixture().use { server ->
            cli.run(listOf("doctor", "--host", "127.0.0.1", "--port", server.port.toString(), "--user", "guest")).exit shouldBe 0
        }
        val offline = cli.run(listOf("inspect"), null)
        offline.exit shouldBe 2
        offline.events.single()["error"]!!.jsonObject["message"]!!.jsonPrimitive.content shouldBe "Missing --directory"
    }

    test("login and interactive opt-in never read piped input and no-input wins") {
        val cli = ContractCli(tempdir().toPath())
        listOf(listOf("auth", "login"), listOf("doctor", "--interactive"),
            listOf("auth", "login", "--no-input"), listOf("doctor", "--interactive", "--no-input")).forEach { args ->
            val result = cli.run(args + listOf("--user", "guest"), null)
            result.exit shouldBe 2
            result.events.single()["error"]!!.jsonObject["code"]!!.jsonPrimitive.content shouldBe "INVALID_ARGUMENT"
            result.stderr shouldBe ""
        }
        WireFixture().use { server ->
            cli.run(server, listOf("auth", "login", "--no-input")).exit shouldBe 0
        }
    }

    test("local context operations reject unrelated endpoint and command flags") {
        val cli = ContractCli(tempdir().toPath())
        listOf(
            listOf("context", "list", "--user", "guest"),
            listOf("context", "add", "--name", "local", "--user", "guest", "--context", "other"),
            listOf("context", "use", "--name", "local", "--port", "6101"),
            listOf("context", "list", "--txid", "1"),
            listOf("doctor", "--name", "ignored", "--user", "guest"),
        ).forEach { cli.run(it, null).exit shouldBe 2 }
    }
})
