package dev.scouterctl

import dev.scouterctl.adapters.serialization.json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

fun main(args: Array<String>) {
    val version = args[0]
    val current = Path.of(args[1]).toAbsolutePath()
    val previous = Path.of(args[2]).toAbsolutePath()
    require(current != previous && Files.isRegularFile(current) && Files.isRegularFile(previous))
    val work = Files.createTempDirectory("scouterctl-npm-")
    try {
        for (global in listOf(false, true)) NpmInstallation(work.resolve(if (global) "global" else "project"), global)
            .verify(current, previous, version)
        println("PASS: npm global/project install -> upgrade -> downgrade -> remove; Unicode, exit codes, config retained")
    } finally { work.toFile().deleteRecursively() }
}

private class NpmInstallation(private val root: Path, private val global: Boolean) {
    private val windows = System.getProperty("os.name").startsWith("Windows")
    private val prefix = Files.createDirectories(root.resolve("installation"))
    private val caller = Files.createDirectories(root.resolve("호출 위치"))
    private val config = caller.resolve("연결 설정.json")
    private val cache = Files.createDirectories(root.resolve("cache"))
    private val packageHome = if (global) prefix.resolve(if (windows) "node_modules/scouterctl" else "lib/node_modules/scouterctl")
        else prefix.resolve("node_modules/scouterctl")
    private val bin = if (global) { if (windows) prefix else prefix.resolve("bin") } else prefix.resolve("node_modules/.bin")

    fun verify(current: Path, previous: Path, version: String) {
        Files.writeString(prefix.resolve("package.json"), "{\"private\":true}")
        install(previous)
        val previousVersion = cli("--version").success().trim()
        check(previousVersion != "scouterctl $version") { "Use two differently versioned application archives" }
        cli("context", "add", "--name", "운영_서울", "--user", "관측 사용자🚀").success()
        cli("context", "use", "--name", "운영_서울").success()
        if (windows) execute(listOf("node", packageHome.resolve("scouterctl.cjs").toString(),
            "context", "add", "--name", "직접_인자", "--user", "관측 사용자🚀"), caller).success()
        val saved = Files.readAllBytes(config).toList()
        install(current)
        check(cli("--version").success().trim() == "scouterctl $version")
        val contexts = json.parseToJsonElement(cli("context", "list").success()).jsonObject["data"]!!.jsonObject
        check(contexts["current"]!!.jsonPrimitive.content == "운영_서울")
        check(contexts["contexts"]!!.jsonObject["운영_서울"]!!.jsonObject["user"]!!.jsonPrimitive.content == "관측 사용자🚀")
        if (windows) check(contexts["contexts"]!!.jsonObject["직접_인자"]!!.jsonObject["user"]!!.jsonPrimitive.content == "관측 사용자🚀")
        WireFixture(expectedPassword = "설치 검증🚀").use { server ->
            val connected = execute(listOf("node", packageHome.resolve("scouterctl.cjs").toString(),
                "doctor", "--no-input", "--host", "127.0.0.1", "--port", server.port.toString(), "--user", "guest"), caller,
                mapOf("SCOUTERCTL_PASSWORD" to "설치 검증🚀"))
            check(json.parseToJsonElement(connected.success()).jsonObject["ok"]!!.jsonPrimitive.content == "true")
        }
        val invalid = cli("doctor", "--port", "0", "--user", "guest")
        check(invalid.exit == 2 && invalid.error.isEmpty())
        check(json.parseToJsonElement(invalid.output).jsonObject["error"]!!.jsonObject["code"]!!.jsonPrimitive.content == "INVALID_ARGUMENT")
        val missing = execute(listOf("node", packageHome.resolve("scouterctl.cjs").toString(), "--version"), caller,
            mapOf("JAVA_HOME" to root.resolve("missing-java").toString()))
        check(missing.exit == 1 && missing.output.isEmpty() && "Java 21" in missing.error)
        TerminalCli(caller, listOf("doctor", "--user", "guest", "--interactive"),
            command = listOf("node", packageHome.resolve("scouterctl.cjs").toString())).use { terminal ->
            terminal.awaitPassword()
            terminal.send("\u0003")
            check(terminal.finish().exit == 130)
        }
        check(Files.readAllBytes(config).toList() == saved)
        install(previous)
        check(cli("--version").success().trim() == previousVersion)
        npm("uninstall", "scouterctl")
        check(!Files.exists(packageHome) && !Files.exists(bin.resolve(if (windows) "scouterctl.cmd" else "scouterctl")))
        check(Files.readAllBytes(config).toList() == saved)
    }

    private fun install(archive: Path) = npm("install", archive.toString())

    private fun npm(vararg arguments: String) {
        val command = listOf("npm", "--prefix", prefix.toString(), "--cache", cache.toString(),
            "--offline", "--ignore-scripts", "--no-audit", "--no-fund") +
            (if (global) listOf("--global") else listOf("--save-exact")) + arguments
        val shell = if (windows) listOf("cmd.exe", "/d", "/c") + command else command
        execute(shell, prefix).success()
    }

    private fun cli(vararg arguments: String): NpmResult {
        val command = if (windows) {
            Files.writeString(caller.resolve("command.args"), arguments.joinToString("\n") { argument ->
                "\"" + argument.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
            } + "\n")
            listOf("cmd.exe", "/d", "/c", "scouterctl.cmd @command.args")
        } else listOf("/bin/sh", "-c", "exec scouterctl \"$@\"", "scouterctl") + arguments
        val result = execute(command, caller, mapOf("PATH" to "$bin${java.io.File.pathSeparator}${System.getenv("PATH")}"))
        return result
    }

    private fun execute(command: List<String>, cwd: Path, overrides: Map<String, String> = emptyMap()): NpmResult {
        val stdout = root.resolve("stdout")
        val stderr = root.resolve("stderr")
        val process = ProcessBuilder(command).directory(cwd.toFile()).apply {
            environment()["SCOUTERCTL_CONFIG"] = config.toString()
            environment()["npm_config_userconfig"] = root.resolve("empty.npmrc").toString()
            environment()["npm_config_update_notifier"] = "false"
            listOf("SCOUTERCTL_PASSWORD", "JAVA_OPTS", "SCOUTERCTL_OPTS", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS").forEach(environment()::remove)
            environment().putAll(overrides)
        }.redirectOutput(stdout.toFile()).redirectError(stderr.toFile()).start()
        process.outputStream.close()
        try {
            check(process.waitFor(60, TimeUnit.SECONDS)) { "npm command timed out: $command" }
            return NpmResult(process.exitValue(), Files.readString(stdout), Files.readString(stderr))
        } finally { process.destroyForcibly() }
    }
}

private data class NpmResult(val exit: Int, val output: String, val error: String) {
    fun success(): String {
        check(exit == 0) { "npm command failed ($exit): $output\n$error" }
        return output
    }
}
