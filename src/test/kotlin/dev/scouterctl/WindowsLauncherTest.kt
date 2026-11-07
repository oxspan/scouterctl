package dev.scouterctl

import dev.scouterctl.adapters.files.EvidenceStore
import dev.scouterctl.adapters.serialization.*
import dev.scouterctl.bootstrap.BuildInfo
import dev.scouterctl.core.*
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

class WindowsLauncherTest : FunSpec({
    test("Windows command preserves direct Unicode arguments from a Korean installation and emoji caller directory") {
        val cli = WindowsCommand(tempdir().toPath())
        cli.run("--version").also {
            it.exit shouldBe 0
            it.output.trim() shouldBe "scouterctl ${BuildInfo.version}"
            it.error shouldBe ""
        }
        val user = "관측 사용자🚀 # % & | < > \\\"따옴표\\\" \\\\"
        cli.run("context", "add", "--name", "운영_서울", "--user", user).also {
            it.exit shouldBe 0
            it.error shouldBe ""
            val data = json.parseToJsonElement(it.output).jsonObject["data"]!!.jsonObject
            data["contexts"]!!.jsonObject["운영_서울"]!!.jsonObject["user"]!!.jsonPrimitive.content shouldBe user
        }
        val stored = json.decodeFromString<ContextConfigDto>(Files.readString(cli.config)).toModel()
        stored.contexts.getValue("운영_서울").user shouldBe user
    }

    test("Windows command resolves relative Unicode captures and exact service filters without argument files") {
        val cli = WindowsCommand(tempdir().toPath())
        val directory = cli.cwd.resolve("캡처 # % & 자료 🚀")
        val store = EvidenceStore(directory)
        store.create()
        val service = "/결제 # % & \\\"서울\\\" 🚀<GET>"
        val xlog = XLogRecord("42", "0", "0", 1793491200001L, 42, 10, 123, 0)
        val profile = ProfileRecord("42", emptyList(), transaction = ProfileTransaction("available", DictionaryText(10, service)))
        val page = store.writePage(0, listOf(EvidenceRecord(xlog, ProfileAvailability.AVAILABLE, profile)))
        store.save(EvidenceManifest(source = ServerInfo("fixture", "2.21.3", "UTC", "guest"), endpoint = "offline",
            query = Query("20261101", 1793491200000L, 1793491260000L, "UTC", 42, 2), textIncluded = true,
            executionComplete = true, pages = listOf(page)))
        val original = Files.readString(directory.resolve(page.name))
        val result = cli.run("inspect", "search", "--directory", directory.fileName.toString(),
            "--service-name", service, "--include-text")
        result.exit shouldBe 0
        result.error shouldBe ""
        val data = json.parseToJsonElement(result.output).jsonObject["data"]!!.jsonObject
        data["summary"]!!.jsonObject["matchedRecords"]!!.jsonPrimitive.int shouldBe 1
        data["records"]!!.jsonArray.single().jsonObject["service"]!!.jsonObject["text"]!!.jsonPrimitive.content shouldBe service
        Files.readString(directory.resolve(page.name)) shouldBe original
    }

    test("Windows native startup fails on stderr when Java is missing or JAVA_HOME is invalid") {
        val cli = WindowsCommand(tempdir().toPath())
        for (home in listOf(null, cli.cwd.resolve("없는 자바").toString())) {
            val result = cli.run("--version", javaHome = home)
            result.exit shouldBe 1
            result.output shouldBe ""
            result.error shouldContain "Java 21"
        }
    }

    test("Windows native command returns the CLI structured argument error and exit code") {
        val cli = WindowsCommand(tempdir().toPath())
        val result = cli.run("doctor", "--port", "0", "--user", "guest", "--no-input")
        result.exit shouldBe 2
        result.error shouldBe ""
        json.parseToJsonElement(result.output).jsonObject["error"]!!.jsonObject["code"]!!.jsonPrimitive.content shouldBe "INVALID_ARGUMENT"
    }

    test("Windows native command can cancel its hidden terminal password prompt") {
        val cli = WindowsCommand(tempdir().toPath())
        TerminalCli(cli.cwd, listOf("doctor", "--user", "guest", "--interactive"), command = listOf(cli.executable.toString())).use {
            it.awaitPassword()
            it.send("\u0003")
            val result = it.finish()
            (result.exit != 0) shouldBe true
            result.output shouldNotContain "\"ok\":true"
        }
    }
})

private data class WindowsResult(val exit: Int, val output: String, val error: String)

private class WindowsCommand(work: Path) {
    val cwd: Path = Files.createDirectory(work.resolve("호출 위치 🚀"))
    val config: Path = cwd.resolve("연결 설정🚀.json")
    val executable: Path

    init {
        check(System.getProperty("os.name").startsWith("Windows")) { "Run WindowsLauncherTest on native Windows" }
        val installation = Files.createDirectory(work.resolve("한글 설치 폴더"))
        ZipFile(checkNotNull(System.getProperty("cli.windows.distribution"))).use { zip ->
            zip.entries().asSequence().forEach { entry ->
                val target = installation.resolve(entry.name).normalize()
                require(target.startsWith(installation))
                if (entry.isDirectory) Files.createDirectories(target)
                else {
                    Files.createDirectories(target.parent)
                    zip.getInputStream(entry).use { Files.copy(it, target) }
                }
            }
        }
        executable = installation.resolve("scouterctl-${BuildInfo.version}/bin/scouterctl.exe")
    }

    fun run(vararg args: String, javaHome: String? = System.getProperty("java.home")): WindowsResult {
        val out = cwd.resolve("stdout.txt")
        val err = cwd.resolve("stderr.txt")
        val process = ProcessBuilder(listOf(executable.toString()) + args).directory(cwd.toFile()).apply {
            environment().remove("SCOUTERCTL_PASSWORD")
            environment().remove("JAVA_OPTS")
            environment().remove("SCOUTERCTL_OPTS")
            environment().remove("JDK_JAVA_OPTIONS")
            environment()["SCOUTERCTL_CONFIG"] = config.fileName.toString()
            if (javaHome == null) {
                environment().remove("JAVA_HOME")
                environment()["PATH"] = executable.parent.toString()
            } else environment()["JAVA_HOME"] = javaHome
        }.redirectOutput(out.toFile()).redirectError(err.toFile()).start()
        process.outputStream.close()
        try {
            check(process.waitFor(20, TimeUnit.SECONDS)) { "Windows command did not terminate" }
            return WindowsResult(process.exitValue(), Files.readString(out), Files.readString(err))
        } finally { process.destroyForcibly() }
    }
}
