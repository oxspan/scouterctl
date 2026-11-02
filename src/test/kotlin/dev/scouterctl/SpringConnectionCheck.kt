package dev.scouterctl

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

fun verifyInitialConnection(work: Path, endpoint: Endpoint, password: String) {
    val cli = ContractCli(work)
    val checks = mutableListOf<JsonObject>()
    val flags = listOf("--host", endpoint.host, "--port", endpoint.port.toString(), "--user", endpoint.user)
    fun record(label: String, outcome: Outcome, expected: Int) {
        check(outcome.exit == expected) { "$label: expected $expected, got ${outcome.exit}" }
        check(outcome.stderr.isEmpty()) { "$label wrote stderr" }
        check(!outcome.output.contains(password)) { "$label exposed the password" }
        checks += buildJsonObject {
            put("case", label); put("exit", outcome.exit); put("response", outcome.events.single())
        }
    }
    record("missing-password", cli.run(listOf("doctor") + flags, null), 2)
    record("wrong-password", cli.run(listOf("doctor") + flags, "incorrect-$password"), 3)
    record("wrong-account", cli.run(listOf("doctor", "--port", endpoint.port.toString(), "--user", "missing-account"), password), 3)
    record("url-instead-of-host", cli.run(listOf("doctor", "--host", "http://127.0.0.1", "--user", endpoint.user), password), 2)
    record("unresolved-host", cli.run(listOf("doctor", "--host", "scouterctl.invalid", "--user", endpoint.user), password), 5)
    val closedPort = ServerSocket(0).use { it.localPort }
    record("refused-port", cli.run(listOf("doctor", "--port", closedPort.toString(), "--user", endpoint.user), password), 5)
    record("authenticated", cli.run(listOf("doctor") + flags, password), 0)
    val empty = cli.run(listOf("object", "list") + flags, password)
    check(empty.events.single()["data"]!!.jsonArray.isEmpty()) { "Fresh Collector should have no Agent yet" }
    record("no-targets", empty, 0)
    Files.writeString(work.resolve("connection-checks.json"), json.encodeToString(checks))

    val stdout = work.resolve("quickstart-shell.jsonl")
    val stderr = work.resolve("quickstart-shell.stderr")
    val script = """
        set -e
        SCOUTERCTL_JAR="${'$'}POC_JAR"
        SCOUTERCTL=(java -jar "${'$'}SCOUTERCTL_JAR" --host 127.0.0.1 --port "${'$'}POC_PORT" --user poc)
        read -r -s -p 'Collector password: ' SCOUTERCTL_PASSWORD
        printf '\n'
        export SCOUTERCTL_PASSWORD
        "${'$'}{SCOUTERCTL[@]}" doctor
        "${'$'}{SCOUTERCTL[@]}" object list
        unset SCOUTERCTL_PASSWORD
    """.trimIndent()
    val process = ProcessBuilder("bash", "-c", script).directory(work.toFile())
        .redirectOutput(stdout.toFile()).redirectError(stderr.toFile()).apply {
            environment().remove("SCOUTERCTL_PASSWORD")
            environment()["POC_JAR"] = System.getProperty("cli.jar")
            environment()["POC_PORT"] = endpoint.port.toString()
            environment()["PATH"] = "${Path.of(System.getProperty("java.home"), "bin")}:${environment()["PATH"]}"
        }.start()
    try {
        process.outputStream.bufferedWriter().use { it.write("$password\n") }
        check(process.waitFor(20, TimeUnit.SECONDS) && process.exitValue() == 0) { "Quick-start Bash flow failed" }
        val text = Files.readString(stdout)
        check(!text.contains(password) && Files.readString(stderr).isEmpty())
        val events = text.lineSequence().filter(String::isNotBlank).map { json.parseToJsonElement(it).jsonObject }.toList()
        check(events.size == 2 && events.all { it["ok"]!!.jsonPrimitive.boolean })
        check(events.first()["data"]!!.jsonObject["serverId"]!!.jsonPrimitive.content == "scouterctl-spring-smoke")
        check(events.last()["data"]!!.jsonArray.isEmpty())
    } finally { process.destroyForcibly() }
    println("PASS: 8 initial connection cases and Bash quick start before Agent discovery")
}
