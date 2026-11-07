package dev.scouterctl

import dev.scouterctl.adapters.serialization.json
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

class ContextPrecedenceTest : FunSpec({
    listOf(
        "SCOUTERCTL_CONFIG" to ("" to "SCOUTERCTL_CONFIG must name a file"),
        "XDG_CONFIG_HOME" to ("relative" to "XDG_CONFIG_HOME must be absolute"),
    ).forEach { (variable, invalid) ->
        listOf(listOf("context", "add"), listOf("context", "add", "--name", "local"), listOf("context", "use")).forEach { args ->
            test("invalid $variable precedes missing context input for ${args.joinToString(" ")}") {
                val directory = tempdir().toPath()
                val stdout = directory.resolve("stdout.json")
                val stderr = directory.resolve("stderr.txt")
                val process = ProcessBuilder(listOf(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-jar", System.getProperty("cli.jar")) + args).apply {
                    environment().remove("SCOUTERCTL_CONFIG")
                    environment().remove("XDG_CONFIG_HOME")
                    environment().remove("SCOUTERCTL_PASSWORD")
                    environment()[variable] = invalid.first
                }.redirectOutput(stdout.toFile()).redirectError(stderr.toFile()).start()
                try {
                    process.waitFor(15, TimeUnit.SECONDS) shouldBe true
                    process.exitValue() shouldBe 2
                    val error = json.parseToJsonElement(Files.readString(stdout)).jsonObject["error"]!!.jsonObject
                    error["code"]!!.jsonPrimitive.content shouldBe "INVALID_ARGUMENT"
                    error["message"]!!.jsonPrimitive.content shouldBe invalid.second
                    Files.readString(stderr) shouldBe ""
                } finally { process.destroyForcibly() }
            }
        }
    }
})
