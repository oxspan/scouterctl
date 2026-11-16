package dev.scouterctl

import dev.scouterctl.adapters.serialization.json
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PackageManagerReleaseTest : FunSpec({
    if (!System.getProperty("os.name").startsWith("Windows")) {
        test("index updater rejects corrupted release bytes before contacting GitHub") {
            val fixture = IndexFixture(tempdir().toPath())
            Files.writeString(fixture.work.resolve("build/release/SHA256SUMS"), "invalid\n")
            (fixture.run() != 0) shouldBe true
            fixture.posts() shouldBe emptyList()
            Files.exists(fixture.work.resolve("calls")) shouldBe false
        }
        test("repairing an older release cannot downgrade package manager definitions") {
            val fixture = IndexFixture(tempdir().toPath())
            fixture.remoteVersion("2.0.0")
            fixture.run() shouldBe 0
            fixture.posts() shouldBe emptyList()
            Files.exists(fixture.work.resolve("pr-created")) shouldBe false
        }
        test("index update creates a branch with both verified definitions and opens one pull request") {
            val fixture = IndexFixture(tempdir().toPath())
            fixture.run() shouldBe 0
            fixture.posts() shouldBe listOf("trees", "commits", "refs")
            val request = json.parseToJsonElement(Files.readString(fixture.work.resolve("tree-request"))).jsonObject
            request["base_tree"]!!.jsonPrimitive.content shouldBe "base-tree"
            val scoop = request["tree"]!!.jsonArray.single { it.jsonObject["path"]!!.jsonPrimitive.content == "bucket/scouterctl.json" }.jsonObject
            json.parseToJsonElement(scoop["content"]!!.jsonPrimitive.content).jsonObject["version"]!!.jsonPrimitive.content shouldBe "1.0.0"
            Files.exists(fixture.work.resolve("pr-created")) shouldBe true
            fixture.run() shouldBe 0
            fixture.posts() shouldBe listOf("trees", "commits", "refs")
        }
        test("an interrupted update resumes an identical branch without replacing its commits") {
            val fixture = IndexFixture(tempdir().toPath())
            Files.writeString(fixture.work.resolve("branch-exists"), "yes")
            fixture.run() shouldBe 0
            fixture.posts() shouldBe emptyList()
            Files.exists(fixture.work.resolve("pr-created")) shouldBe true
        }
    }
})

private class IndexFixture(val work: Path) {
    init {
        val scripts = Files.createDirectories(work.resolve(".github/scripts"))
        for (name in listOf("generate-package-manifests.sh", "update-package-index.sh")) Files.copy(Path.of(".github/scripts", name), scripts.resolve(name))
        val templates = Files.createDirectory(work.resolve("packaging"))
        for (name in listOf("scoop.json.in", "homebrew.rb.in")) Files.copy(Path.of("packaging", name), templates.resolve(name))
        val packages = Files.createDirectories(work.resolve("build/release"))
        val names = listOf("scouterctl-1.0.0-linux-x64.zip", "scouterctl-1.0.0-windows-x64.zip")
        for (name in names) ZipOutputStream(Files.newOutputStream(packages.resolve(name))).use { zip ->
            zip.putNextEntry(ZipEntry("scouterctl-1.0.0/version.txt"))
            zip.write("1.0.0\n".toByteArray())
            zip.closeEntry()
        }
        Files.writeString(packages.resolve("SHA256SUMS"), names.joinToString("") { "${sha256(Files.readAllBytes(packages.resolve(it)))}  $it\n" })
        remoteVersion("0.9.0")
        val bin = Files.createDirectory(work.resolve("bin"))
        Files.writeString(bin.resolve("gh"), """
            #!/usr/bin/env bash
            set -euo pipefail
            printf '%s\n' call >> "${'$'}FAKE_ROOT/calls"
            if [[ ${'$'}1 == pr ]]; then
              if [[ ${'$'}2 == list ]]; then [[ ! -f ${'$'}FAKE_ROOT/pr-created ]] || printf '1\n'; exit 0; fi
              [[ ${'$'}2 == create ]]
              touch "${'$'}FAKE_ROOT/pr-created"
              exit 0
            fi
            [[ ${'$'}1 == api ]]; shift
            if [[ ${'$'}1 == --method ]]; then
              [[ ${'$'}2 == POST ]]; endpoint=${'$'}{3##*/}; shift 3
              printf '%s\n' "${'$'}endpoint" >> "${'$'}FAKE_ROOT/posts"
              case ${'$'}endpoint in
                trees)
                  [[ ${'$'}1 == --input ]]; cp "${'$'}2" "${'$'}FAKE_ROOT/tree-request"
                  jq -e '.base_tree == "base-tree"' "${'$'}2" >/dev/null
                  printf 'new-tree\n' ;;
                commits)
                  [[ ${'$'}1 == --input ]]
                  jq -e '.tree == "new-tree" and .parents == ["base-commit"]' "${'$'}2" >/dev/null
                  printf 'new-commit\n' ;;
                refs) touch "${'$'}FAKE_ROOT/branch-exists" ;;
                *) exit 2 ;;
              esac
              exit 0
            fi
            case ${'$'}1 in
              repos/oxspan/scouterctl) printf 'master\n' ;;
              */git/ref/heads/master) printf 'base-commit\n' ;;
              */git/commits/base-commit) printf 'base-tree\n' ;;
              */git/ref/heads/automation/*) [[ -f ${'$'}FAKE_ROOT/branch-exists ]] ;;
              */contents/bucket/scouterctl.json?ref=base-commit) base64 "${'$'}FAKE_ROOT/remote.json" ;;
              */contents/Formula/scouterctl.rb?ref=base-commit) printf 'previous formula' | base64 ;;
              */contents/bucket/scouterctl.json?ref=automation/*) base64 "${'$'}FAKE_ROOT/build/package-index/bucket/scouterctl.json" ;;
              */contents/Formula/scouterctl.rb?ref=automation/*) base64 "${'$'}FAKE_ROOT/build/package-index/Formula/scouterctl.rb" ;;
              *) exit 2 ;;
            esac
        """.trimIndent() + "\n")
        check(bin.resolve("gh").toFile().setExecutable(true))
    }
    fun remoteVersion(version: String) = Files.writeString(work.resolve("remote.json"), "{\"version\":\"$version\"}")
    fun posts(): List<String> = work.resolve("posts").let { if (Files.exists(it)) Files.readAllLines(it) else emptyList() }
    fun run(): Int {
        val process = ProcessBuilder("bash", ".github/scripts/update-package-index.sh").directory(work.toFile()).apply {
            environment()["PATH"] = work.resolve("bin").toString() + ":" + environment()["PATH"]
            environment()["FAKE_ROOT"] = work.toString()
            environment()["GH_REPO"] = "oxspan/scouterctl"
            environment()["RELEASE_TAG"] = "v1.0.0"
            environment()["PACKAGE_VERSION"] = "1.0.0"
            environment()["TMPDIR"] = work.toString()
        }.redirectOutput(work.resolve("stdout").toFile()).redirectError(work.resolve("stderr").toFile()).start()
        try {
            check(process.waitFor(20, TimeUnit.SECONDS))
            return process.exitValue()
        } finally { process.destroyForcibly() }
    }
}
