package dev.scouterctl

import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

class UnicodeCliTest : FunSpec({
    test("relative JAR launch works from a Korean installation directory") {
        val cli = UnicodeCli(tempdir().toPath())
        val result = cli.run(listOf("--version"))
        result.first shouldBe 0
        result.second.trim() shouldBe "scouterctl ${BuildInfo.version}"
        result.third shouldBe ""
    }

    test("UTF-8 argument file preserves Korean connection names users and config paths") {
        val cli = UnicodeCli(tempdir().toPath())
        val name = "운영_서울-1"
        val user = "관측 사용자🚀"
        cli.json(listOf("context", "add", "--name", name, "--user", user)).exit shouldBe 0
        val selected = cli.json(listOf("context", "use", "--name", name))
        selected.exit shouldBe 0
        selected.data()["current"]!!.jsonPrimitive.content shouldBe name
        val listed = cli.json(listOf("context", "list"))
        listed.exit shouldBe 0
        listed.data()["contexts"]!!.jsonObject[name]!!.jsonObject["user"]!!.jsonPrimitive.content shouldBe user
        val stored = json.decodeFromString<ContextConfig>(Files.readString(cli.config))
        stored.current shouldBe name
        stored.contexts.getValue(name).user shouldBe user
    }

    test("UTF-8 argument file searches Korean capture paths and exact service text without mutation") {
        val cli = UnicodeCli(tempdir().toPath())
        val directory = cli.install.resolve("캡처 자료")
        val store = EvidenceStore(directory)
        store.create()
        val name = "/결제 #1 \\\"주문\\\" 🚀<GET>"
        val xlog = XLogRecord("42", "0", "0", 1793491200001L, 42, 10, 123, 0)
        val profile = ProfileRecord("42", emptyList(), transaction = ProfileTransaction("available", DictionaryText(10, name)))
        val page = store.writePage(0, listOf(EvidenceRecord(xlog, ProfileAvailability.AVAILABLE, profile)))
        store.save(EvidenceManifest(source = ServerInfo("fixture", "2.21.3", "UTC", "guest"), endpoint = "offline",
            query = Query("20261101", 1793491200000L, 1793491260000L, "UTC", 42, 2), textIncluded = true,
            executionComplete = true, pages = listOf(page)))
        val original = Files.readString(directory.resolve(page.name))
        val found = cli.json(listOf("inspect", "search", "--directory", "캡처 자료", "--service-name", name, "--include-text"))
        found.exit shouldBe 0
        val data = found.data()
        data["summary"]!!.jsonObject["matchedRecords"]!!.jsonPrimitive.int shouldBe 1
        val row = data["records"]!!.jsonArray.single().jsonObject
        row["xlog"]!!.jsonObject["txid"]!!.jsonPrimitive.content shouldBe "42"
        row["service"]!!.jsonObject["text"]!!.jsonPrimitive.content shouldBe name
        val detail = cli.json(listOf("inspect", "--directory", "캡처 자료", "--txid", "42", "--include-text"))
        detail.exit shouldBe 0
        detail.output.contains("결제") shouldBe true
        Files.readString(directory.resolve(page.name)) shouldBe original
    }

    test("Unicode context names retain length and traversal restrictions without modifying config") {
        val cli = UnicodeCli(tempdir().toPath())
        val boundary = "한".repeat(64)
        cli.json(listOf("context", "add", "--name", boundary, "--user", "guest")).exit shouldBe 0
        val original = Files.readString(cli.config)
        listOf("한".repeat(65), "../운영", "운영/서울", "운영\\서울", "운영 이름", ".운영", "운영\n서울").forEach { name ->
            cli.json(listOf("context", "add", "--name", name, "--user", "guest")).exit shouldBe 2
            Files.readString(cli.config) shouldBe original
        }
    }
})

private class UnicodeCli(work: Path) {
    val install: Path = Files.createDirectories(work.resolve("한글 설치"))
    val config: Path = install.resolve("연결 설정.json")

    init { Files.copy(Path.of(System.getProperty("cli.jar")), install.resolve("scouterctl.jar")) }

    fun json(args: List<String>): Outcome {
        val (exit, output, stderr) = run(args)
        stderr shouldBe ""
        return Outcome(exit, output, stderr)
    }

    fun run(args: List<String>): Triple<Int, String, String> {
        val file = install.resolve("command.args")
        Files.writeString(file, args.joinToString("\n") { arg ->
            "\"" + arg.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\""
        } + "\n")
        val stdout = install.resolve("stdout.txt")
        val stderr = install.resolve("stderr.txt")
        val process = ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-Dfile.encoding=UTF-8", "-jar", "scouterctl.jar", "@command.args")
            .directory(install.toFile()).redirectOutput(stdout.toFile()).redirectError(stderr.toFile()).apply {
                environment().remove("SCOUTERCTL_PASSWORD")
                environment()["SCOUTERCTL_CONFIG"] = config.toString()
            }.start()
        process.outputStream.close()
        try {
            check(process.waitFor(20, TimeUnit.SECONDS)) { "Unicode CLI did not terminate" }
            return Triple(process.exitValue(), Files.readString(stdout), Files.readString(stderr))
        } finally { process.destroyForcibly() }
    }
}
