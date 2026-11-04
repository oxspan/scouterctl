package dev.scouterctl

import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.*
import java.nio.file.Files

class TerminalInputTest : FunSpec({
    test("native terminal saves and selects a connection then authenticates and queries without echoing the password") {
        val work = tempdir().toPath()
        val password = "terminal-secret-9Q!"
        WireFixture(expectedPassword = password).use { server ->
            listOf(
                listOf("context", "add", "--name", "terminal", "--host", "127.0.0.1", "--port", server.port.toString(), "--user", "guest"),
                listOf("context", "use", "--name", "terminal"),
            ).forEach { args ->
                TerminalCli(work, args).use { it.finish().exit shouldBe 0 }
            }
            listOf(listOf("auth", "login"), listOf("object", "list", "--interactive")).forEach { args ->
                TerminalCli(work, args).use { terminal ->
                    terminal.awaitPassword()
                    terminal.send("$password\r")
                    val result = terminal.finish()
                    result.exit shouldBe 0
                    result.output shouldNotContain password
                    result.event()["ok"]!!.jsonPrimitive.boolean shouldBe true
                    if (args.first() == "object") result.event()["data"]!!.jsonArray.single()
                        .jsonObject["objName"]!!.jsonPrimitive.content shouldBe "/fixture"
                }
            }
        }
        val config = Files.readString(work.resolve("config.json"))
        config shouldNotContain password
        config shouldNotContain "session"
        json.parseToJsonElement(config).jsonObject["current"]!!.jsonPrimitive.content shouldBe "terminal"
    }

    test("native terminal rejects a wrong hidden password") {
        WireFixture(expectedPassword = "correct-terminal-password").use { server ->
            TerminalCli(tempdir().toPath(), listOf("auth", "login", "--user", "guest", "--port", server.port.toString())).use { terminal ->
                terminal.awaitPassword()
                terminal.send("wrong-terminal-password\r")
                val result = terminal.finish()
                result.exit shouldBe 3
                result.output shouldNotContain "wrong-terminal-password"
                result.event()["ok"]!!.jsonPrimitive.boolean shouldBe false
            }
        }
    }

    test("empty terminal password returns a structured input error") {
        TerminalCli(tempdir().toPath(), listOf("auth", "login", "--user", "guest")).use { terminal ->
            terminal.awaitPassword()
            terminal.send("\r")
            val result = terminal.finish()
            result.exit shouldBe 2
            result.event()["error"]!!.jsonObject["message"]!!.jsonPrimitive.content shouldBe "Password input is empty"
        }
    }

    test("no-input suppresses a prompt even with a real terminal and interactive flag") {
        TerminalCli(tempdir().toPath(), listOf("auth", "login", "--interactive", "--no-input", "--user", "guest")).use { terminal ->
            val result = terminal.finish()
            result.exit shouldBe 2
            result.output shouldNotContain "Collector password (hidden;"
            result.event()["error"]!!.jsonObject["code"]!!.jsonPrimitive.content shouldBe "INVALID_ARGUMENT"
        }
    }

    test("environment password takes precedence over terminal prompting") {
        val password = "environment-terminal-secret"
        WireFixture(expectedPassword = password).use { server ->
            TerminalCli(tempdir().toPath(), listOf("auth", "login", "--user", "guest", "--port", server.port.toString()), password).use { terminal ->
                val result = terminal.finish()
                result.exit shouldBe 0
                result.output shouldNotContain "Collector password (hidden;"
                result.output shouldNotContain password
            }
        }
    }

    test("terminal EOF cancels password entry with a structured input error") {
        TerminalCli(tempdir().toPath(), listOf("auth", "login", "--user", "guest")).use { terminal ->
            terminal.awaitPassword()
            val windows = System.getProperty("os.name").startsWith("Windows")
            terminal.text() shouldContain if (windows) "Ctrl+Z" else "Ctrl+D"
            terminal.send(if (windows) "\u001a\r" else "\u0004")
            val result = terminal.finish()
            result.exit shouldBe 2
            result.event()["error"]!!.jsonObject["message"]!!.jsonPrimitive.content shouldBe "Password input cancelled"
        }
    }

    test("Ctrl-C cancels terminal password entry without authenticating") {
        TerminalCli(tempdir().toPath(), listOf("auth", "login", "--user", "guest")).use { terminal ->
            terminal.awaitPassword()
            terminal.send("\u0003")
            val result = terminal.finish()
            (result.exit != 0) shouldBe true
            result.output shouldNotContain "\"ok\":true"
        }
    }
})
