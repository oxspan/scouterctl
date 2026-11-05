package dev.scouterctl

import dev.scouterctl.adapters.serialization.*

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

class AgentCli(private val work: Path, private val port: Int, private val password: String) {
    fun run(label: String, arguments: List<String>, expected: Int = 0): List<JsonObject> {
        val java = Path.of(System.getProperty("java.home"), "bin/java").toString()
        val out = work.resolve("$label.jsonl")
        val err = work.resolve("$label.stderr")
        val child = ProcessBuilder(listOf(java, "-jar", System.getProperty("cli.jar")) + arguments +
            listOf("--port", port.toString(), "--user", "poc")).apply {
            environment()["SCOUTERCTL_PASSWORD"] = password
        }.redirectOutput(out.toFile()).redirectError(err.toFile()).start()
        try {
            check(child.waitFor(20, TimeUnit.SECONDS)) { "$label timeout" }
            check(child.exitValue() == expected) { "$label exit ${child.exitValue()}: ${Files.readString(out)}" }
            check(Files.readString(err).isEmpty()) { "$label wrote stderr" }
            return Files.readAllLines(out).map { json.parseToJsonElement(it).jsonObject }
        } finally { child.destroyForcibly() }
    }
}
