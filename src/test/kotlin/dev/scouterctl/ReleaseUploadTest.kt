package dev.scouterctl

import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ReleaseUploadTest : FunSpec({
    if (!System.getProperty("os.name").startsWith("Windows")) {
        test("publisher uploads exact command assets and repeated publication preserves existing bytes") {
            val fixture = UploadFixture(tempdir().toPath())
            fixture.run() shouldBe 0
            fixture.remoteFiles() shouldBe fixture.packageFiles()
            val before = fixture.remoteFiles()
            fixture.run() shouldBe 0
            fixture.remoteFiles() shouldBe before
        }
        test("publisher rejects candidate version and damaged checksums before remote access") {
            for (candidate in listOf(false, true)) {
                val fixture = UploadFixture(tempdir().toPath())
                if (candidate) Files.writeString(fixture.work.resolve("version.txt"), "0.3.0\n")
                else Files.writeString(fixture.packages.resolve("SHA256SUMS"), "corrupt\n")
                (fixture.run() != 0) shouldBe true
                Files.exists(fixture.work.resolve("calls")) shouldBe false
                fixture.remoteFiles() shouldBe emptyMap()
            }
        }
        test("publisher rejects a tag pointing to another commit without uploading assets") {
            val fixture = UploadFixture(tempdir().toPath())
            (fixture.run("1".repeat(40)) != 0) shouldBe true
            fixture.remoteFiles() shouldBe emptyMap()
        }
        test("publisher validates every existing asset before uploading missing files") {
            val fixture = UploadFixture(tempdir().toPath())
            Files.writeString(fixture.remote.resolve("SHA256SUMS"), "different remote content")
            val before = fixture.remoteFiles()
            (fixture.run() != 0) shouldBe true
            fixture.remoteFiles() shouldBe before
        }
    }
})

private class UploadFixture(val work: Path) {
    val packages: Path = Files.createDirectories(work.resolve("build/release"))
    val remote: Path = Files.createDirectory(work.resolve("remote"))
    private val bin = Files.createDirectory(work.resolve("bin"))
    init {
        val scripts = Files.createDirectories(work.resolve(".github/scripts"))
        for (name in listOf("upload-release.sh", "verify-release-files.sh")) Files.copy(Path.of(".github/scripts", name), scripts.resolve(name))
        Files.writeString(work.resolve("version.txt"), "1.0.0\n")
        Files.writeString(work.resolve(".release-please-manifest.json"), "{\".\":\"1.0.0\"}")
        for (name in listOf("scouterctl-1.0.0-linux-x64.zip", "scouterctl-1.0.0-windows-x64.zip")) {
            ZipOutputStream(Files.newOutputStream(packages.resolve(name))).use { zip ->
                zip.putNextEntry(ZipEntry("scouterctl-1.0.0/version.txt")); zip.write("1.0.0\n".toByteArray()); zip.closeEntry()
            }
        }
        val names = listOf("scouterctl-1.0.0-linux-x64.zip", "scouterctl-1.0.0-windows-x64.zip")
        Files.writeString(packages.resolve("SHA256SUMS"), names.joinToString("") { "${sha256(Files.readAllBytes(packages.resolve(it)))}  $it\n" })
        Files.writeString(bin.resolve("gh"), """
            #!/usr/bin/env bash
            set -euo pipefail
            printf '%s\n' "call" >> "${'$'}FAKE_ROOT/calls"
            if [[ ${'$'}1 == api ]]; then printf '%040d\n' 0; exit; fi
            shift; operation=${'$'}1; shift
            case ${'$'}operation in
              view)
                if [[ "${'$'}*" == *isDraft* ]]; then printf 'false\n'; else
                  for file in "${'$'}FAKE_ROOT/remote/"*; do [[ ! -f ${'$'}file ]] || basename "${'$'}file"; done
                fi ;;
              download)
                shift
                while (( ${'$'}# )); do
                  case ${'$'}1 in --pattern) asset=${'$'}2; shift 2;; --dir) destination=${'$'}2; shift 2;; *) exit 2;; esac
                done
                cp "${'$'}FAKE_ROOT/remote/${'$'}asset" "${'$'}destination/${'$'}asset" ;;
              upload) shift; cp "${'$'}1" "${'$'}FAKE_ROOT/remote/${'$'}1" ;;
              *) exit 2 ;;
            esac
        """.trimIndent() + "\n")
        bin.resolve("gh").toFile().setExecutable(true)
    }
    fun packageFiles() = bytes(packages)
    fun remoteFiles() = bytes(remote)
    private fun bytes(directory: Path) = Files.list(directory).use { paths -> paths.toList().associate { it.fileName.toString() to Files.readAllBytes(it).toList() } }
    fun run(sha: String = "0".repeat(40)): Int {
        val process = ProcessBuilder("bash", ".github/scripts/upload-release.sh").directory(work.toFile()).apply {
            environment()["PATH"] = bin.toString() + ":" + environment()["PATH"]
            environment()["FAKE_ROOT"] = work.toString()
            environment()["RELEASE_TAG"] = "v1.0.0"; environment()["PACKAGE_VERSION"] = "1.0.0"
            environment()["PACKAGE_SHA"] = sha; environment()["GH_REPO"] = "fixture/project"
        }.redirectOutput(work.resolve("stdout").toFile()).redirectError(work.resolve("stderr").toFile()).start()
        try { check(process.waitFor(15, TimeUnit.SECONDS)); return process.exitValue() }
        finally { process.destroyForcibly() }
    }
}
