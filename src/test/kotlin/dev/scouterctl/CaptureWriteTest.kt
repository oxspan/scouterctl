package dev.scouterctl

import com.sun.nio.file.ExtendedOpenOption.NOSHARE_DELETE
import dev.scouterctl.adapters.files.CaptureStore
import dev.scouterctl.adapters.files.EvidenceStore
import dev.scouterctl.core.ErrorCode
import dev.scouterctl.core.OperationFailure
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption.READ
import java.nio.file.attribute.PosixFilePermissions

class CaptureWriteTest : FunSpec({
    for (includeProfiles in listOf(false, true)) {
        test("denied checkpoint replacement preserves committed bytes and identifies local I/O with include-profiles=$includeProfiles") {
            val work = tempdir().toPath()
            val directory = work.resolve("capture")
            val cli = ContractCli(work)
            WireFixture().use { server ->
                val args = listOf("capture", "--directory", directory.toString()) + evidenceWindow +
                    if (includeProfiles) listOf("--include-profiles") else emptyList()
                cli.run(server, args).exit shouldBe 0
            }
            val manifest = directory.resolve("manifest.json")
            val partial = directory.resolve("manifest.json.partial")
            val before = Files.readString(manifest)
            Files.writeString(partial, "prior partial")
            val save: () -> Unit = if (includeProfiles) {
                val store = EvidenceStore(directory)
                val next = store.load().copy(executionComplete = false)
                val write = { store.save(next) }
                write
            } else {
                val store = CaptureStore(directory)
                val next = store.load().copy(executionComplete = false)
                val write = { store.save(next) }
                write
            }
            val checkFailure = {
                val failure = shouldThrow<OperationFailure> { save() }
                failure.code shouldBe ErrorCode.IO_ERROR
                failure.message shouldBe "Local file I/O failed; check the path, permissions and available disk space"
                Files.readString(manifest) shouldBe before
                Files.exists(partial) shouldBe true
            }
            if (System.getProperty("os.name").startsWith("Windows")) {
                FileChannel.open(manifest, READ, NOSHARE_DELETE).use { checkFailure() }
            } else {
                val permissions = Files.getPosixFilePermissions(directory)
                Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("r-x------"))
                try { checkFailure() } finally { Files.setPosixFilePermissions(directory, permissions) }
            }
            save()
            Files.exists(partial) shouldBe false
            Files.readString(manifest).contains("\"executionComplete\":false") shouldBe true
        }
    }
})
