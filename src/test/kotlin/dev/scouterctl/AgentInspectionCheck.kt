package dev.scouterctl

import dev.scouterctl.core.Endpoint
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path

internal fun verifyAgentInspection(work: Path, endpoint: Endpoint, password: String, objHash: Int) {
    val files = listOf("conf/scouter.conf", "conf/account.xml", "agent.conf").associateWith {
        Files.readAllBytes(work.resolve(it))
    }
    val cli = AgentCli(work, endpoint.port, password)
    val target = listOf("--object-hash", objHash.toString())
    val status = cli.run("agent-status", listOf("agent", "status") + target).single()["data"]!!.jsonObject
    check(status["agent"]!!.jsonObject["objHash"]!!.jsonPrimitive.int == objHash)
    check(status["jvmAvailability"] == JsonPrimitive("available"))
    val javaVersion = status["jvm"]!!.jsonObject["javaVersion"]!!.jsonPrimitive.content
    check(javaVersion == Files.readString(work.resolve("http-port.java-version")))
    val all = cli.run("agent-config", listOf("agent", "config") + target).single()["data"]!!.jsonObject
    val settings = all["settings"]!!.jsonArray.map { it.jsonObject }
    check(settings.isNotEmpty())
    val port = cli.run("agent-port", listOf("agent", "config") + target + listOf("--key", "net_collector_tcp_port"))
        .single()["data"]!!.jsonObject["settings"]!!.jsonArray.single().jsonObject
    check(port["value"]!!.jsonPrimitive.int == endpoint.port && port["redacted"] == JsonPrimitive(false))
    val hidden = cli.run("agent-hidden", listOf("agent", "config") + target + listOf("--key", "log_dir"))
        .single()["data"]!!.jsonObject["settings"]!!.jsonArray.single().jsonObject
    check(hidden["value"] == JsonNull && hidden["defaultValue"] == JsonNull && hidden["redacted"] == JsonPrimitive(true))
    cli.run("agent-missing-key", listOf("agent", "config") + target + listOf("--key", "scouterctl_missing_setting"), 7)
    cli.run("agent-missing-target", listOf("agent", "status", "--object-hash", "0"), 7)
    AgentCli(work, endpoint.port, "wrong-$password").run("agent-rejected", listOf("agent", "status") + target, 3)
    for ((name, before) in files) check(Files.readAllBytes(work.resolve(name)).contentEquals(before))
    for (label in listOf("agent-status", "agent-config", "agent-port", "agent-hidden")) {
        val text = Files.readString(work.resolve("$label.jsonl"))
        check(!text.contains(password) && !text.contains(work.toString()))
    }
    Files.writeString(work.resolve("inspection-summary.json"), buildJsonObject {
        put("collectorVersion", status["collector"]!!.jsonObject["version"]!!)
        put("agentVersion", status["agent"]!!.jsonObject["version"]!!)
        put("agentJvm", javaVersion); put("status", true); put("settings", settings.size)
        put("portMatchesRuntime", true); put("hiddenValueAndDefault", true)
        put("missingSettingExit", 7); put("missingTargetExit", 7); put("invalidCredentialsExit", 3)
        put("collectorConfigUnchanged", true); put("accountUnchanged", true); put("agentConfigUnchanged", true)
    }.toString())
    println("PASS: Agent JVM, runtime settings, redaction, missing key/target, authentication and unchanged files")
}
