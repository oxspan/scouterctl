package dev.scouterctl

import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

class CliContractTest : FunSpec({
    val window = listOf("--date", "20261101", "--from", "2026-11-01T00:00:00Z", "--to", "2026-11-01T00:01:00Z",
        "--timezone", "UTC", "--object-hash", "42", "--page-size", "2")

    test("profile preserves SQL outcomes while discovery and profile exclude credentials and free text") {
        val cli = ContractCli(tempdir().toPath())
        WireFixture(Fault.STRUCTURAL_ONLY).use { server ->
            val doctor = cli.run(server, listOf("doctor"))
            val objects = cli.run(server, listOf("object", "list"))
            val profile = cli.run(server, listOf("profile", "get", "--date", "20261101", "--txid", WireFixture.ids[0].toString()))
            listOf(doctor, objects, profile).forEach { it.exit shouldBe 0 }
            doctor.data()["group"]?.jsonPrimitive?.content shouldBe "guest"
            objects.events.single()["data"]!!.jsonArray.single().jsonObject["objName"]!!.jsonPrimitive.content shouldBe "/fixture"
            val decoded = json.decodeFromJsonElement<ProfileRecord>(profile.data())
            decoded.steps shouldContainExactly listOf(ProfileStep("MESSAGE", 1),
                ProfileStep("SQL3", 2, SqlProfile(123, 7, 0, 1)),
                ProfileStep("SQL3", 3, SqlProfile(456, 11, -789, -3)))
            listOf(doctor, objects, profile).forEach {
                it.output shouldNotContain "secret-token"
                it.output shouldNotContain scouter.util.CipherUtil.sha256("fixture-password")
                it.output shouldNotContain "private-address"
                it.output shouldNotContain "\"session\""
                it.stderr shouldBe ""
            }
        }
    }

    test("paged JSONL preserves every 64 bit identifier and terminates once") {
        val cli = ContractCli(tempdir().toPath())
        WireFixture().use { server ->
            val result = cli.run(server, listOf("xlog", "search") + window)
            result.exit shouldBe 0
            result.events.map { it["type"]!!.jsonPrimitive.content } shouldContainExactly
                listOf("start", "record", "record", "record", "complete")
            val rows = result.events.filter { it["type"]?.jsonPrimitive?.content == "record" }.map { it["data"]!!.jsonObject }
            rows.map { it["txid"]!!.jsonPrimitive.content } shouldContainExactly WireFixture.ids.map(Long::toString)
            rows.forEach {
                it["txid"]!!.jsonPrimitive.isString.shouldBeTrue()
                it["caller"]!!.jsonPrimitive.content shouldBe "9007199254740993"
            }
            result.output shouldNotContain "secret-token"
        }
    }

    test("capture resumes without duplicates and refuses changed query or damaged data") {
        val temp = tempdir().toPath()
        val cli = ContractCli(temp)
        WireFixture().use { server ->
            val directory = temp.resolve("capture")
            val args = listOf("capture", "--directory", directory.toString(), "--max-pages", "1") + window
            cli.run(server, args).exit shouldBe 8
            val changedQuery = args.toMutableList().apply { set(indexOf("--object-hash") + 1, "43") }
            val changed = cli.run(server, changedQuery + "--resume")
            changed.exit shouldBe 2
            changed.events.single()["error"]!!.jsonObject["message"]!!.jsonPrimitive.content shouldBe
                "Resume source or query differs from manifest"
            cli.run(server, args + "--resume").exit shouldBe 0
            val manifest = json.decodeFromString<CaptureManifest>(Files.readString(directory.resolve("manifest.json")))
            val rows = manifest.files.flatMap { Files.readAllLines(directory.resolve(it.name)) }
                .map { json.decodeFromString<XLogRecord>(it).txid }
            rows shouldContainExactly WireFixture.ids.map(Long::toString)
            manifest.executionComplete.shouldBeTrue()
            manifest.sampling shouldBe "unknown"
            Files.writeString(directory.resolve(manifest.files.first().name), "corrupted")
            cli.run(server, args + "--resume").exit shouldBe 10
        }
    }

    mapOf(Fault.AUTH to 3, Fault.VERSION to 9, Fault.SESSION to 3,
        Fault.MISSING_META to 6, Fault.INVALID_META to 6, Fault.STALLED_CURSOR to 6, Fault.DISCONNECT to 10)
        .forEach { (fault, expectedExit) ->
            test("wire failure $fault returns exit $expectedExit without success completion") {
                val cli = ContractCli(tempdir().toPath())
                WireFixture(fault).use { server ->
                    val result = cli.run(server, listOf("xlog", "search") + window)
                    result.exit shouldBe expectedExit
                    result.events.last()["type"]!!.jsonPrimitive.content shouldBe "error"
                    result.events.any { it["type"]?.jsonPrimitive?.content == "complete" }.shouldBeFalse()
                }
            }
        }

    test("interrupted capture retains checkpoint and resumes on new connection") {
        val temp = tempdir().toPath()
        val cli = ContractCli(temp)
        val directory = temp.resolve("interrupted")
        WireFixture(Fault.DISCONNECT).use { server ->
            val args = listOf("capture", "--directory", directory.toString()) + window
            cli.run(server, args).exit shouldBe 10
            val manifest = json.decodeFromString<CaptureManifest>(Files.readString(directory.resolve("manifest.json")))
            manifest.executionComplete.shouldBeFalse()
            manifest.files.sumOf { it.records } shouldBe 2
            cli.run(server, args + "--resume").exit shouldBe 0
            val resumed = json.decodeFromString<CaptureManifest>(Files.readString(directory.resolve("manifest.json")))
            resumed.files.sumOf { it.records } shouldBe 3
            resumed.executionComplete.shouldBeTrue()
        }
    }

    test("page budget and invalid input have structured nonzero outcomes") {
        val cli = ContractCli(tempdir().toPath())
        WireFixture().use { server ->
            cli.run(server, listOf("xlog", "search", "--max-pages", "1") + window).exit shouldBe 8
            cli.run(server, listOf("xlog", "search") + window + listOf("--page-size", "0")).exit shouldBe 2
            cli.run(server, listOf("raw", "call")).exit shouldBe 2
        }
    }
})

internal class ContractCli(private val temp: Path) {
    fun run(server: WireFixture, args: List<String>): Outcome =
        run(args + listOf("--port", server.port.toString(), "--user", "guest"), fixture = server::diagnostics)

    fun run(port: Int, args: List<String>): Outcome =
        run(args + listOf("--port", port.toString(), "--user", "guest"))

    fun run(args: List<String>, password: String? = "fixture-password", fixture: (() -> JsonObject)? = null): Outcome {
        val started = System.nanoTime()
        val stderr = Files.createTempFile(temp, "stderr", ".txt")
        val stdout = Files.createTempFile(temp, "stdout", ".jsonl")
        val process = ProcessBuilder(listOf(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-jar", System.getProperty("cli.jar")) + args)
            .directory(temp.toFile())
            .redirectOutput(stdout.toFile()).redirectError(stderr.toFile()).apply {
                environment().remove("SCOUTERCTL_PASSWORD")
                environment()["SCOUTERCTL_CONFIG"] = temp.resolve("config.json").toString()
                if (password != null) environment()["SCOUTERCTL_PASSWORD"] = password
            }.start()
        process.outputStream.close()
        try {
            val finished = process.waitFor(15, TimeUnit.SECONDS)
            val exit = if (finished) process.exitValue() else null
            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            val fixtureState = if (exit != 0) fixture?.invoke() else null
            if (!finished) process.destroyForcibly().waitFor(5, TimeUnit.SECONDS)
            val output = Files.readString(stdout)
            val errors = Files.readString(stderr)
            val diagnostic = if (exit != 0) connectionDiagnostic(exit, elapsedMs, output, errors, fixtureState) else null
            diagnostic?.let { println("CLI_DIAGNOSTIC $it") }
            check(finished) { "CLI did not terminate; $diagnostic" }
            return Outcome(checkNotNull(exit), output, errors, diagnostic)
        } finally { process.destroyForcibly() }
    }
}

internal data class Outcome(val exit: Int, val output: String, val stderr: String, val diagnostic: JsonObject? = null) {
    val events = output.lineSequence().filter { it.isNotBlank() }.map { json.parseToJsonElement(it).jsonObject }.toList()
    fun data() = events.single()["data"]!!.jsonObject
}
