package dev.scouterctl

import dev.scouterctl.adapters.files.EvidenceStore
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import java.nio.file.Path

class CaptureCompatibilityTest : FunSpec({
    test("new profile capture declares v3 and refusing legacy resume leaves every file unchanged") {
        val work = tempdir().toPath()
        val directory = work.resolve("capture")
        WireFixture().use { server ->
            val cli = ContractCli(work)
            val args = listOf("capture", "--include-profiles", "--directory", directory.toString(), "--max-pages", "1") + evidenceWindow
            cli.run(server, args).exit shouldBe 8
            val store = EvidenceStore(directory)
            val manifest = store.load()
            manifest.schemaVersion shouldBe "scouterctl.capture/v3"
            store.save(manifest.copy(schemaVersion = "scouterctl.capture/v2"))
            val before = captureBytes(directory)
            val result = cli.run(server, args + "--resume")
            result.exit shouldBe 2
            result.output shouldContain "Resume requires a v3 profile capture"
            captureBytes(directory) shouldBe before
        }
    }

    test("offline inspection rejects missing malformed and future schema before reading records") {
        val work = tempdir().toPath()
        val directory = Files.createDirectory(work.resolve("capture"))
        for (content in listOf("{}", "[]", """{"schemaVersion":3}""", """{"schemaVersion":"scouterctl.capture/v999"}""")) {
            Files.writeString(directory.resolve("manifest.json"), content)
            val result = runOfflineCli(work, listOf("inspect", "--directory", directory.toString(), "--txid", "1"))
            result.exit shouldBe 2
            result.output shouldContain "Unsupported capture schema"
            Files.readString(directory.resolve("manifest.json")) shouldBe content
        }
    }

    test("transaction-only resume rejects an unsupported schema before writing a checkpoint") {
        val work = tempdir().toPath()
        val directory = Files.createDirectory(work.resolve("capture"))
        val content = """{"schemaVersion":"scouterctl.capture/v999"}"""
        Files.writeString(directory.resolve("manifest.json"), content)
        WireFixture().use { server ->
            val result = ContractCli(work).run(server, listOf("capture", "--resume", "--directory", directory.toString()) + evidenceWindow)
            result.exit shouldBe 2
            result.output shouldContain "Unsupported capture schema"
            captureBytes(directory) shouldBe mapOf("manifest.json" to content)
        }
    }
})

private fun captureBytes(directory: Path): Map<String, String> = Files.list(directory).use { files ->
    files.toList().associate { it.fileName.toString() to Files.readString(it) }
}
