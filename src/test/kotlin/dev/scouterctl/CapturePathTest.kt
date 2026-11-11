package dev.scouterctl

import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files

class CapturePathTest : FunSpec({
    test("inspect distinguishes missing and invalid paths from a damaged bundle") {
        val work = tempdir().toPath()
        Files.writeString(work.resolve("not-a-directory"), "ordinary file")
        Files.createDirectory(work.resolve("empty-capture"))
        val cli = ContractCli(work)
        val inspect = listOf("inspect", "--txid", "1", "--directory")

        cli.run(inspect + "missing-capture", password = null).shouldFailWith(
            2, "INVALID_ARGUMENT", "Capture directory does not exist. Specify the path to an existing capture."
        )
        for (path in listOf("not-a-directory", "not-a-directory/child", "not-a-directory/child/grandchild")) {
            cli.run(inspect + path, password = null).shouldFailWith(
                2, "INVALID_ARGUMENT", "Capture path is not a directory. Specify the path to an existing capture."
            )
        }
        cli.run(inspect + "empty-capture", password = null).shouldFailWith(
            10, "IO_ERROR", "Capture integrity or record validation failed"
        )
    }

    for (includeProfiles in listOf(false, true)) {
        test("new capture reports occupied destinations and invalid parents with include-profiles=$includeProfiles") {
            val work = tempdir().toPath()
            val existing = Files.createDirectory(work.resolve("existing"))
            val marker = Files.writeString(existing.resolve("marker"), "existing capture data")
            val occupiedFile = Files.writeString(work.resolve("occupied-file"), "existing file")
            val cli = ContractCli(work)
            val args = listOf("capture") + evidenceWindow + if (includeProfiles) listOf("--include-profiles") else emptyList()
            val occupied = "Capture destination already exists. Choose an unused path, or use --resume for a compatible capture."

            WireFixture().use { server ->
                cli.run(server, args + listOf("--directory", "existing")).shouldFailWith(2, "INVALID_ARGUMENT", occupied)
                cli.run(server, args + listOf("--directory", "occupied-file")).shouldFailWith(2, "INVALID_ARGUMENT", occupied)
                cli.run(server, args + listOf("--directory", "missing-parent/capture")).shouldFailWith(
                    2, "INVALID_ARGUMENT", "Capture parent directory does not exist. Create it before starting a capture."
                )
                for (parent in listOf("occupied-file", "occupied-file/child", "occupied-file/child/grandchild")) {
                    cli.run(server, args + listOf("--directory", "$parent/capture")).shouldFailWith(
                        2, "INVALID_ARGUMENT", "Capture parent path is not a directory. Choose an existing parent directory."
                    )
                    cli.run(server, args + listOf("--resume", "--directory", parent)).shouldFailWith(
                        2, "INVALID_ARGUMENT", "Capture path is not a directory. Specify the path to an existing capture."
                    )
                }
                cli.run(server, args + listOf("--resume", "--directory", "missing-capture")).shouldFailWith(
                    2, "INVALID_ARGUMENT", "Capture directory does not exist. Specify the path to an existing capture."
                )
            }

            Files.list(existing).use { it.toList() } shouldBe listOf(marker)
            Files.readString(marker) shouldBe "existing capture data"
            Files.readString(occupiedFile) shouldBe "existing file"
            Files.notExists(work.resolve("missing-parent")) shouldBe true
            Files.notExists(work.resolve("missing-capture")) shouldBe true
        }
    }
})

private fun Outcome.shouldFailWith(expectedExit: Int, code: String, message: String) {
    exit shouldBe expectedExit
    val error = events.single().getValue("error").jsonObject
    error.getValue("code").jsonPrimitive.content shouldBe code
    error.getValue("message").jsonPrimitive.content shouldBe message
}
