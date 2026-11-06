package dev.scouterctl

import dev.scouterctl.adapters.serialization.json
import dev.scouterctl.core.Endpoint
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

internal fun verifyHostNative(work: Path, endpoint: Endpoint, password: String, hostHash: Int, javaHash: Int) {
    val windowsJava = System.getenv("SCOUTER_WINDOWS_JAVA") ?: return
    val windowsJar = checkNotNull(System.getenv("SCOUTER_WINDOWS_JAR"))
    val windowsConfig = checkNotNull(System.getenv("SCOUTER_WINDOWS_CONFIG"))
    val requireIo = System.getenv("SCOUTER_HOST_EXPECT_IO") != "false"
    val results = mutableListOf<JsonObject>()
    for (platform in listOf("linux", "windows")) {
        for ((name, hash, unit) in listOf(Triple("Cpu", hostHash, "%"), Triple("MemU", hostHash, "MB"),
            Triple("DiskWriteBytes", hostHash, "bytes/sec"), Triple("NetRxBytes", hostHash, "bytes/sec"),
            Triple("ProcCpu", javaHash, "%"), Triple("scouterctl_missing", hostHash, ""))) {
            val label = "host-$platform-$name"
            val expected = if (name == "scouterctl_missing") 7 else 0
            val args = listOf("counter", "get", "--object-hash", hash.toString(), "--counter", name)
            val result = if (platform == "linux") AgentCli(work, endpoint.port, password).run(label, args, expected).single()
            else {
                val out = work.resolve("$label.jsonl"); val err = work.resolve("$label.stderr")
                val child = ProcessBuilder(listOf(windowsJava, "-jar", windowsJar) + args +
                    listOf("--host", "127.0.0.1", "--port", endpoint.port.toString(), "--user", "poc", "--no-input")).apply {
                    environment()["SCOUTERCTL_PASSWORD"] = password
                    environment()["SCOUTERCTL_CONFIG"] = windowsConfig
                    val inherited = environment()["WSLENV"].orEmpty().split(':')
                        .filter { it.isNotEmpty() && it.substringBefore('/') !in setOf("SCOUTERCTL_PASSWORD", "SCOUTERCTL_CONFIG") }
                    environment()["WSLENV"] = (inherited + listOf("SCOUTERCTL_PASSWORD/w", "SCOUTERCTL_CONFIG/w")).joinToString(":")
                }.redirectOutput(out.toFile()).redirectError(err.toFile()).start()
                try {
                    check(child.waitFor(20, TimeUnit.SECONDS)) { "$label timed out" }
                    check(child.exitValue() == expected && Files.readString(err).isEmpty()) { "$label failed" }
                    val output = Files.readString(out)
                    check(!output.contains(password) && !output.contains(work.toString()))
                    json.parseToJsonElement(output).jsonObject
                } finally { child.destroyForcibly() }
            }
            if (expected == 0) {
                val row = result["data"]!!.jsonObject["counters"]!!.jsonArray.single().jsonObject
                check(row["name"] == JsonPrimitive(name) && row["unit"] == JsonPrimitive(unit))
                if (!requireIo && name in setOf("DiskWriteBytes", "NetRxBytes")) {
                    check(row["availability"] == JsonPrimitive("unavailable") && row["value"] == JsonNull)
                } else check(row["availability"] == JsonPrimitive("available") && row["value"]!!.jsonPrimitive.double >= 0)
            } else check(result["error"]!!.jsonObject["code"] == JsonPrimitive("NOT_FOUND"))
            results.add(buildJsonObject { put("platform", platform); put("counter", name); put("exit", expected) })
        }
    }
    Files.writeString(work.resolve("host-native-summary.json"), JsonArray(results).toString())
}
