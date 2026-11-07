package dev.scouterctl

import dev.scouterctl.adapters.serialization.json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

internal fun verifyInstallLifecycle(candidateFile: Path, previousFile: Path, version: String, libraries: Set<String>) {
    val candidate = readCommandArchive(candidateFile, version, libraries)
    val previousVersion = ZipFile(previousFile.toFile()).use { zip ->
        val entry = zip.entries().asSequence().single { it.name.endsWith("/version.txt") }
        zip.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8).trim() }
    }
    check(previousVersion != version) { "Lifecycle check needs two distinct versions" }
    val previousLibraries = ZipFile(previousFile.toFile()).use { zip ->
        zip.entries().asSequence().filter { !it.isDirectory && "/lib/" in it.name }.map { it.name.substringAfter("/lib/") }.toSet()
    }
    val previous = readCommandArchive(previousFile, previousVersion, previousLibraries)
    val root = Files.createTempDirectory("scouterctl-lifecycle-")
    try {
        val user = Files.createDirectories(root.resolve("사용자 자료 🚀"))
        val config = user.resolve("연결 설정.json")
        val sentinel = user.resolve("saved.jsonl")
        Files.writeString(sentinel, "existing user data\n")
        fun install(archive: CommandArchive): Path {
            val path = root.resolve("한글 설치 폴더").resolve("scouterctl-${archive.version}")
            for ((name, bytes) in archive.files) {
                val target = path.resolve(name)
                Files.createDirectories(target.parent)
                Files.write(target, bytes)
            }
            path.resolve("bin/scouterctl").toFile().takeIf { it.exists() }?.setExecutable(true)
            return path
        }
        val old = install(previous)
        val fresh = install(candidate)
        val windows = System.getProperty("os.name").startsWith("Windows")
        fun executable(path: Path) = path.resolve(if (windows) "bin/scouterctl.exe" else "bin/scouterctl")
        fun selectedVersion(path: Path): String {
            val out = root.resolve("path.stdout")
            val err = root.resolve("path.stderr")
            val shell = if (windows) listOf("cmd.exe", "/d", "/c", "scouterctl.exe --version")
                else listOf("/bin/sh", "-c", "exec scouterctl --version")
            val process = ProcessBuilder(shell).directory(user.toFile()).apply {
                environment()["PATH"] = path.resolve("bin").toString() + java.io.File.pathSeparator + environment()["PATH"]
            }.redirectOutput(out.toFile()).redirectError(err.toFile()).start()
            try {
                check(process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0 && Files.readString(err).isEmpty()) { "PATH command failed" }
                return Files.readString(out).trim()
            } finally { process.destroyForcibly() }
        }
        check(selectedVersion(old) == "scouterctl $previousVersion")
        command(executable(old), user, listOf("context", "add", "--name", "운영_서울", "--user", "관측 사용자🚀"), config)
        command(executable(old), user, listOf("context", "use", "--name", "운영_서울"), config)
        val configBytes = Files.readAllBytes(config).toList()
        val dataBytes = Files.readAllBytes(sentinel).toList()
        check(selectedVersion(fresh) == "scouterctl $version")
        val listed = json.parseToJsonElement(command(executable(fresh), user, listOf("context", "list"), config)).jsonObject
        check(listed["data"]!!.jsonObject["current"]!!.jsonPrimitive.content == "운영_서울")
        check(Files.readAllBytes(config).toList() == configBytes)
        check(selectedVersion(old) == "scouterctl $previousVersion")
        check(fresh.toFile().deleteRecursively())
        check(selectedVersion(old) == "scouterctl $previousVersion")
        check(Files.readAllBytes(config).toList() == configBytes && Files.readAllBytes(sentinel).toList() == dataBytes)
        check(old.toFile().deleteRecursively())
        check(Files.readAllBytes(config).toList() == configBytes && Files.readAllBytes(sentinel).toList() == dataBytes)
        println("PASS: install $previousVersion -> $version -> rollback -> remove, PATH/config/data retained")
    } finally { root.toFile().deleteRecursively() }
}
