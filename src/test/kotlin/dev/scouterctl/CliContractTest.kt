package dev.scouterctl

import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.*

class CliContractTest {
    @TempDir lateinit var temp: Path

    @Test fun `profile preserves SQL outcomes while discovery and profile exclude credentials and free text`() {
        WireFixture().use { server ->
            val doctor = run(server.port, listOf("doctor"))
            val objects = run(server.port, listOf("object", "list"))
            val profile = run(server.port, listOf("profile", "get", "--date", "20261101", "--txid", WireFixture.ids[0].toString()))
            assertEquals("guest", doctor.data()["group"]?.jsonPrimitive?.content)
            assertEquals("/fixture", objects.events.single()["data"]!!.jsonArray.single().jsonObject["objName"]!!.jsonPrimitive.content)
            val decoded = json.decodeFromJsonElement<ProfileRecord>(profile.data())
            assertEquals(listOf(ProfileStep("MESSAGE", 1),
                ProfileStep("SQL3", 2, SqlProfile(123, 7, 0, 1)),
                ProfileStep("SQL3", 3, SqlProfile(456, 11, -789, -3))), decoded.steps)
            listOf(doctor, objects, profile).forEach {
                assertEquals(0, it.exit)
                assertFalse(it.output.contains("secret-token"))
                assertFalse(it.output.contains(scouter.util.CipherUtil.sha256("fixture-password")))
                assertFalse(it.output.contains("private-address"))
                assertFalse(it.output.contains("\"session\""))
                assertEquals("", it.stderr)
            }
        }
    }

    @Test fun `paged JSONL preserves every 64 bit identifier and terminates once`() {
        WireFixture().use { server ->
            val result = run(server.port, listOf("xlog", "search") + window)
            assertEquals(0, result.exit)
            assertEquals(listOf("start", "record", "record", "record", "complete"), result.events.map { it["type"]!!.jsonPrimitive.content })
            val rows = result.events.filter { it["type"]?.jsonPrimitive?.content == "record" }.map { it["data"]!!.jsonObject }
            assertEquals(WireFixture.ids.map(Long::toString), rows.map { it["txid"]!!.jsonPrimitive.content })
            assertTrue(rows.all { it["txid"]!!.jsonPrimitive.isString && it["caller"]!!.jsonPrimitive.content == "9007199254740993" })
            assertFalse(result.output.contains("secret-token"))
        }
    }

    @Test fun `capture resumes without duplicates and refuses changed query or damaged data`() {
        WireFixture().use { server ->
            val directory = temp.resolve("capture")
            val args = listOf("capture", "--directory", directory.toString(), "--max-pages", "1") + window
            assertEquals(8, run(server.port, args).exit)
            val changedQuery = args.toMutableList().apply { set(indexOf("--object-hash") + 1, "43") }
            val changed = run(server.port, changedQuery + "--resume")
            assertEquals(2, changed.exit)
            assertEquals("Resume source or query differs from manifest", changed.events.single()["error"]!!.jsonObject["message"]!!.jsonPrimitive.content)
            assertEquals(0, run(server.port, args + "--resume").exit)
            val manifest = json.decodeFromString<CaptureManifest>(Files.readString(directory.resolve("manifest.json")))
            val rows = manifest.files.flatMap { Files.readAllLines(directory.resolve(it.name)) }
                .map { json.decodeFromString<XLogRecord>(it).txid }
            assertEquals(WireFixture.ids.map(Long::toString), rows)
            assertTrue(manifest.executionComplete)
            assertEquals("unknown", manifest.sampling)
            Files.writeString(directory.resolve(manifest.files.first().name), "corrupted")
            assertEquals(10, run(server.port, args + "--resume").exit)
        }
    }

    @Test fun `wire failures never emit success completion`() {
        val cases = mapOf(Fault.AUTH to 3, Fault.VERSION to 9, Fault.SESSION to 3,
            Fault.MISSING_META to 6, Fault.INVALID_META to 6, Fault.STALLED_CURSOR to 6, Fault.DISCONNECT to 10)
        cases.forEach { (fault, expectedExit) ->
            WireFixture(fault).use { server ->
                val result = run(server.port, listOf("xlog", "search") + window)
                assertEquals(expectedExit, result.exit, fault.name)
                assertEquals("error", result.events.last()["type"]!!.jsonPrimitive.content)
                assertFalse(result.events.any { it["type"]?.jsonPrimitive?.content == "complete" })
            }
        }
    }

    @Test fun `interrupted capture retains checkpoint and resumes on new connection`() {
        val directory = temp.resolve("interrupted")
        WireFixture(Fault.DISCONNECT).use { server ->
            val args = listOf("capture", "--directory", directory.toString()) + window
            assertEquals(10, run(server.port, args).exit)
            val manifest = json.decodeFromString<CaptureManifest>(Files.readString(directory.resolve("manifest.json")))
            assertFalse(manifest.executionComplete)
            assertEquals(2, manifest.files.sumOf { it.records })
            assertEquals(0, run(server.port, args + "--resume").exit)
            val resumed = json.decodeFromString<CaptureManifest>(Files.readString(directory.resolve("manifest.json")))
            assertEquals(3, resumed.files.sumOf { it.records })
            assertTrue(resumed.executionComplete)
        }
    }

    @Test fun `page budget and invalid input have structured nonzero outcomes`() {
        WireFixture().use { server ->
            assertEquals(8, run(server.port, listOf("xlog", "search", "--max-pages", "1") + window).exit)
            assertEquals(2, run(server.port, listOf("xlog", "search") + window + listOf("--page-size", "0")).exit)
            assertEquals(2, run(server.port, listOf("raw", "call")).exit)
        }
    }

    private fun run(port: Int, args: List<String>): Outcome {
        val stderr = Files.createTempFile(temp, "stderr", ".txt")
        val stdout = Files.createTempFile(temp, "stdout", ".jsonl")
        val process = ProcessBuilder(listOf(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-jar", System.getProperty("cli.jar")) + args + listOf("--port", port.toString(), "--user", "guest"))
            .redirectOutput(stdout.toFile()).redirectError(stderr.toFile()).apply {
                environment()["SCOUTERCTL_PASSWORD"] = "fixture-password"
            }.start()
        try {
            check(process.waitFor(15, TimeUnit.SECONDS)) { "CLI did not terminate" }
            return Outcome(process.exitValue(), Files.readString(stdout), Files.readString(stderr))
        } finally { process.destroyForcibly() }
    }
    private data class Outcome(val exit: Int, val output: String, val stderr: String) {
        val events = output.lineSequence().filter { it.isNotBlank() }.map { json.parseToJsonElement(it).jsonObject }.toList()
        fun data() = events.single()["data"]!!.jsonObject
    }
    companion object {
        val window = listOf("--date", "20261101", "--from", "2026-11-01T00:00:00Z", "--to", "2026-11-01T00:01:00Z",
            "--timezone", "UTC", "--object-hash", "42", "--page-size", "2")
    }
}
