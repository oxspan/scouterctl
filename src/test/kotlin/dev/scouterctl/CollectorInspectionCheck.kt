package dev.scouterctl

import dev.scouterctl.core.Endpoint
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path

internal fun verifyCollectorInspection(work: Path, endpoint: Endpoint, password: String) {
    val config = work.resolve("conf/scouter.conf")
    val account = work.resolve("conf/account.xml")
    val configBefore = Files.readAllBytes(config)
    val accountBefore = Files.readAllBytes(account)
    val cli = AgentCli(work, endpoint.port, password)
    val status = cli.run("collector-status", listOf("collector", "status")).single()["data"]!!.jsonObject
    val used = status["usedHeapBytes"]!!.jsonPrimitive.long
    val committed = status["committedHeapBytes"]!!.jsonPrimitive.long
    check(used >= 0 && committed >= used)
    check(status["serverTimeEpochMs"]!!.jsonPrimitive.long > 0)
    val all = cli.run("collector-config", listOf("collector", "config")).single()["data"]!!.jsonObject
    val settings = all["settings"]!!.jsonArray.map { it.jsonObject }
    check(settings.isNotEmpty())
    check(settings.map { it["key"]!!.jsonPrimitive.content }.distinct().size == settings.size)
    val selected = cli.run("collector-port", listOf("collector", "config", "--key", "net_tcp_listen_port"))
        .single()["data"]!!.jsonObject["settings"]!!.jsonArray.single().jsonObject
    check(selected["value"]!!.jsonPrimitive.int == endpoint.port)
    check(selected["redacted"] == JsonPrimitive(false))
    val hidden = cli.run("collector-hidden", listOf("collector", "config", "--key", "db_dir"))
        .single()["data"]!!.jsonObject["settings"]!!.jsonArray.single().jsonObject
    check(hidden["redacted"] == JsonPrimitive(true) && hidden["value"] == JsonNull && hidden["defaultValue"] == JsonNull)
    val missing = cli.run("collector-missing", listOf("collector", "config", "--key", "scouterctl_missing_setting"), 7)
    check(missing.single()["error"]!!.jsonObject["code"] == JsonPrimitive("NOT_FOUND"))
    AgentCli(work, endpoint.port, "wrong-$password").run("collector-rejected", listOf("collector", "status"), 3)
    check(Files.readAllBytes(config).contentEquals(configBefore))
    check(Files.readAllBytes(account).contentEquals(accountBefore))
    for (label in listOf("collector-status", "collector-config", "collector-port", "collector-hidden")) {
        val text = Files.readString(work.resolve("$label.jsonl"))
        check(!text.contains(password) && !text.contains(work.toString()))
    }
    Files.writeString(work.resolve("inspection-summary.json"), buildJsonObject {
        put("collectorVersion", status["collector"]!!.jsonObject["version"]!!)
        put("status", true); put("settings", settings.size); put("portMatchesRuntime", true)
        put("hiddenValueAndDefault", true); put("missingSettingExit", 7); put("invalidCredentialsExit", 3)
        put("configUnchanged", true); put("accountUnchanged", true)
    }.toString())
    println("PASS: Collector status, runtime/default settings, redaction, missing key, authentication and unchanged files")
}
