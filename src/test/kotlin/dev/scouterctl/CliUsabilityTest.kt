package dev.scouterctl

import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

class CliUsabilityTest : FunSpec({
    for (noColor in listOf(false, true)) {
        test("redirected help contains no ANSI even when color is forced and NO_COLOR is $noColor") {
            val result = helpOutput(tempdir().toPath(), listOf("--help"), noColor)
            result.exit shouldBe 0
            result.output shouldContain "scouterctl"
            result.output shouldNotContain "\u001b"
            result.stderr shouldBe ""
        }
    }

    for (noColor in listOf(null, "")) {
        test("terminal help honors NO_COLOR presence even with an empty value: $noColor") {
            TerminalCli(tempdir().toPath(), listOf("capture", "--help"),
                command = cliJavaCommand(jvmOptions = listOf("-Dpicocli.ansi=true")), noColor = noColor).use { terminal ->
                terminal.finish().exit shouldBe 0
                terminal.rawText().contains("\u001b[1m") shouldBe (noColor == null)
            }
        }
    }

    data class HelpCase(val words: List<String>, val required: List<String>, val excluded: String)
    for (case in listOf(
        HelpCase(listOf("capture"), listOf("--directory", "--date", "--timezone", "--object-hash"), "--key"),
        HelpCase(listOf("counter", "history"), listOf("--counter", "--date", "--object-hash"), "--directory"),
        HelpCase(listOf("inspect", "compare"), listOf("--before", "--after"), "--host"),
    )) {
        test("${case.words.joinToString(" ")} help identifies its required options and excludes unrelated options") {
            val result = helpOutput(tempdir().toPath(), case.words + "--help")
            result.exit shouldBe 0
            result.stderr shouldBe ""
            val synopsis = result.output.substringBefore("Options:")
            synopsis shouldContain "scouterctl ${case.words.joinToString(" ")}"
            case.required.forEach { option ->
                synopsis shouldContain "$option="
                synopsis shouldNotContain "[$option="
            }
            result.output shouldNotContain case.excluded
        }
    }

    test("inspect help also exposes its offline subcommands") {
        val result = helpOutput(tempdir().toPath(), listOf("inspect", "--help"))
        result.exit shouldBe 0
        for (subcommand in listOf("search", "summary", "compare")) result.output shouldContain "inspect $subcommand"
        result.output shouldNotContain "--host"
    }

    test("counter family help lists its commands without unrelated command options") {
        val result = helpOutput(tempdir().toPath(), listOf("counter", "--help"))
        result.exit shouldBe 0
        result.output shouldContain "scouterctl counter COMMAND"
        for (subcommand in listOf("list", "get", "history", "export")) result.output shouldContain "counter $subcommand"
        result.output shouldNotContain "--directory"
        result.output shouldNotContain "agent status"
    }

    test("unmatched positional values return a safe JSON error") {
        val result = runOfflineCli(tempdir().toPath(), listOf("doctor", "unexpected", "secret-value"))
        result.exit shouldBe 2
        result.events.single()["error"]!!.jsonObject["code"]!!.jsonPrimitive.content shouldBe "INVALID_ARGUMENT"
        result.output shouldNotContain "secret-value"
        result.stderr shouldBe ""
    }

    for ((args, option) in listOf(
        listOf("doctor", "--unknown=secret-value") to "--unknown",
        listOf("doctor", "-z", "secret-value") to "-z",
        listOf("doctor", "--port", "secret-value") to "--port",
        listOf("doctor", "--port") to "--port",
        listOf("counter", "list", "--directory", "secret-value") to "--directory",
    )) {
        test("invalid ${args.joinToString(" ")} identifies the option without echoing values") {
            val result = runOfflineCli(tempdir().toPath(), args)
            result.exit shouldBe 2
            result.stderr shouldBe ""
            val error = result.events.single()["error"]!!.jsonObject
            error["code"]!!.jsonPrimitive.content shouldBe "INVALID_ARGUMENT"
            error["message"]!!.jsonPrimitive.content shouldContain option
            result.output shouldNotContain "secret-value"
        }
    }
})

private data class HelpOutput(val exit: Int, val output: String, val stderr: String)

private fun helpOutput(work: Path, args: List<String>, noColor: Boolean = false): HelpOutput {
    val stdout = work.resolve("stdout")
    val stderr = work.resolve("stderr")
    val process = ProcessBuilder(cliJavaCommand(jvmOptions = listOf("-Dpicocli.ansi=true")) + args)
        .apply {
            environment()["TERM"] = "xterm"
            if (noColor) environment()["NO_COLOR"] = "1" else environment().remove("NO_COLOR")
        }.redirectOutput(stdout.toFile()).redirectError(stderr.toFile()).start()
    try {
        check(process.waitFor(15, TimeUnit.SECONDS)) { "Help did not terminate" }
        return HelpOutput(process.exitValue(), Files.readString(stdout), Files.readString(stderr))
    } finally { process.destroyForcibly() }
}
