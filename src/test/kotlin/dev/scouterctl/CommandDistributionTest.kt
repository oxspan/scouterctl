package dev.scouterctl

import dev.scouterctl.bootstrap.BuildInfo
import dev.scouterctl.adapters.serialization.json
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

class CommandDistributionTest : FunSpec({
    val windows = System.getProperty("os.name").startsWith("Windows")
    run {
        test("extracted command preserves Unicode arguments and caller-relative configuration through PATH") {
            val cli = InstalledCommand(tempdir().toPath())
            cli.run("--version").also {
                it.exit shouldBe 0
                it.output.trim() shouldBe "scouterctl ${BuildInfo.version}"
                it.error shouldBe ""
            }
            val result = cli.run("context", "add", "--name", "운영_서울", "--user", "관측 사용자🚀")
            result.exit shouldBe 0
            result.error shouldBe ""
            val config = json.parseToJsonElement(Files.readString(cli.cwd.resolve(if (windows) "config.json" else "연결 설정.json"))).jsonObject
            config["contexts"]!!.jsonObject["운영_서울"]!!.jsonObject["user"]!!.jsonPrimitive.content shouldBe "관측 사용자🚀"
        }

        test("extracted command preserves structured failures and shell exit codes") {
            val cli = InstalledCommand(tempdir().toPath())
            val result = cli.run("doctor", "--port", "0", "--user", "guest")
            result.exit shouldBe 2
            result.error shouldBe ""
            json.parseToJsonElement(result.output).jsonObject["error"]!!.jsonObject["code"]!!.jsonPrimitive.content shouldBe "INVALID_ARGUMENT"
        }

        test("extracted command stops when Java is absent from PATH") {
            val cli = InstalledCommand(tempdir().toPath())
            val result = cli.run("--version", javaHome = null)
            result.exit shouldBe 1
            result.output shouldBe ""
            result.error shouldContain "no 'java' command could be found"
        }

        test("extracted command reports invalid Java installation on stderr") {
            val cli = InstalledCommand(tempdir().toPath())
            val result = cli.run("--version", javaHome = cli.cwd.resolve("missing-java").toString())
            result.exit shouldBe 1
            result.output shouldBe ""
            result.error shouldContain "JAVA_HOME is set to an invalid directory"
        }

        if (!windows) test("extracted command receives terminal Ctrl-C at the hidden password prompt") {
            val cli = InstalledCommand(tempdir().toPath())
            TerminalCli(cli.cwd, listOf("doctor", "--user", "guest", "--interactive"),
                command = listOf(cli.launcher.toString())).use { terminal ->
                terminal.awaitPassword()
                terminal.send("\u0003")
                terminal.finish().exit shouldBe 130
            }
        }
    }
})

private data class CommandResult(val exit: Int, val output: String, val error: String)

private class InstalledCommand(work: Path) {
    private val windows = System.getProperty("os.name").startsWith("Windows")
    val cwd: Path = Files.createDirectory(work.resolve(if (windows) "caller" else "호출 위치"))
    val launcher: Path

    init {
        val destination = Files.createDirectory(work.resolve(if (windows) "installation" else "한글 설치 폴더"))
        ZipFile(System.getProperty("cli.distribution")).use { zip ->
            zip.entries().asSequence().forEach { entry ->
                val target = destination.resolve(entry.name).normalize()
                require(target.startsWith(destination))
                if (entry.isDirectory) Files.createDirectories(target)
                else {
                    Files.createDirectories(target.parent)
                    zip.getInputStream(entry).use { Files.copy(it, target) }
                }
            }
        }
        launcher = destination.resolve("scouterctl-${BuildInfo.version}/bin/" + if (windows) "scouterctl.bat" else "scouterctl")
        if (!windows) check(launcher.toFile().setExecutable(true))
    }

    fun run(vararg args: String, javaHome: String? = System.getProperty("java.home")): CommandResult {
        val output = cwd.resolve("stdout.txt")
        val error = cwd.resolve("stderr.txt")
        val command = if (windows) {
            Files.writeString(cwd.resolve("command.args"), args.joinToString("\n") { arg ->
                "\"" + arg.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
            } + "\n")
            listOf(Path.of(System.getenv("SystemRoot"), "System32", "cmd.exe").toString(),
                "/d", "/s", "/c", "scouterctl @command.args")
        } else listOf("/bin/sh", "-c", "exec scouterctl \"$@\"", "scouterctl") + args
        val process = ProcessBuilder(command)
            .directory(cwd.toFile()).apply {
                environment().remove("SCOUTERCTL_PASSWORD")
                environment().remove("JAVA_OPTS")
                environment().remove("SCOUTERCTL_OPTS")
                environment().withCliCoverage()
                environment()["PATH"] = if (javaHome == null) launcher.parent.toString()
                    else "${launcher.parent}${java.io.File.pathSeparator}${environment()["PATH"]}"
                if (javaHome == null) environment().remove("JAVA_HOME") else environment()["JAVA_HOME"] = javaHome
                if (windows) environment()["PATHEXT"] = ".COM;.EXE;.BAT;.CMD"
                environment()["SCOUTERCTL_CONFIG"] = if (windows) "config.json" else "연결 설정.json"
            }.redirectOutput(output.toFile()).redirectError(error.toFile()).start()
        process.outputStream.close()
        try {
            check(process.waitFor(20, TimeUnit.SECONDS)) { "Installed command did not terminate" }
            return CommandResult(process.exitValue(), Files.readString(output), Files.readString(error))
        } finally { process.destroyForcibly() }
    }
}
