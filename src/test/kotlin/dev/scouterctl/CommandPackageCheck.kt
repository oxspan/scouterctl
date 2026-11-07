package dev.scouterctl

import dev.scouterctl.adapters.serialization.json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

/** Opt-in checks for the actual command archives; never modifies a user's installation or PATH. */
fun main(args: Array<String>) {
    val mode = args[0]
    val version = args[1]
    val directory = Path.of(args[2]).toAbsolutePath()
    val libraries = System.getProperty("release.libraries").split('|').toSet()
    val prefix = "scouterctl-$version"
    when (mode) {
        "inputs" -> {
            verifySums(directory, setOf("$prefix.zip", "$prefix-linux-x64.zip", "$prefix-all.jar"))
            val base = readCommandArchive(directory.resolve("$prefix.zip"), version, libraries)
            val linux = readCommandArchive(directory.resolve("$prefix-linux-x64.zip"), version, libraries)
            check(linux.files.keys.none { it.endsWith(".bat") || it.endsWith(".exe") })
            check(common(base) == common(linux)) { "Linux payload differs from canonical distribution" }
            if (!System.getProperty("os.name").startsWith("Windows")) withInstalled(linux) { install, work ->
                verifyInstalled(install.resolve("bin/scouterctl"), work, version)
            }
        }
        "bundle" -> {
            verifySums(directory, setOf("$prefix-linux-x64.zip", "$prefix-windows-x64.zip"))
            val linux = readCommandArchive(directory.resolve("$prefix-linux-x64.zip"), version, libraries)
            val windows = readCommandArchive(directory.resolve("$prefix-windows-x64.zip"), version, libraries)
            check(linux.files.keys.none { it.endsWith(".bat") || it.endsWith(".exe") })
            check(windows.files.keys.none { it == "bin/scouterctl" || it.endsWith(".bat") })
            for (notice in listOf("Zig-LICENSE.txt", "libcxx-LICENSE.txt", "libcxxabi-LICENSE.txt", "libunwind-LICENSE.txt", "MinGW-COPYING.txt")) {
                check(windows.files["vendor/windows-launcher/$notice"]?.isNotEmpty() == true) { "Missing native runtime notice" }
            }
            check(common(linux) == common(windows)) { "Linux and Windows payloads differ" }
            val selected = if (System.getProperty("os.name").startsWith("Windows")) windows else linux
            withInstalled(selected) { install, work ->
                verifyInstalled(install.resolve(if (selected === windows) "bin/scouterctl.exe" else "bin/scouterctl"), work, version)
            }
        }
        "lifecycle" -> verifyInstallLifecycle(directory, Path.of(args[3]).toAbsolutePath(), version, libraries)
        else -> error("Unknown command-package check")
    }
    println("PASS: command package $mode / $version on ${System.getProperty("os.name")}")
}

internal data class CommandArchive(val source: Path, val version: String, val files: Map<String, ByteArray>)
internal fun readCommandArchive(file: Path, version: String, libraries: Set<String>): CommandArchive = ZipFile(file.toFile()).use { zip ->
    val prefix = "scouterctl-$version/"
    val files = linkedMapOf<String, ByteArray>()
    for (entry in zip.entries().asSequence()) {
        check(entry.name.startsWith(prefix) && '\\' !in entry.name && entry.name.split('/').none { it == ".." || it == "." }) { "Unsafe package entry" }
        if (entry.isDirectory) continue
        val name = entry.name.removePrefix(prefix)
        check(name.isNotEmpty() && files.put(name, zip.getInputStream(entry).use { it.readBytes() }) == null) { "Duplicate package entry" }
    }
    check(files["version.txt"]?.toString(Charsets.UTF_8)?.trim() == version) { "Packaged version differs" }
    for (name in listOf("README.md", "QUICKSTART.md", "LICENSE", "SECURITY.md", "vendor/SCOUTER-LICENSE.txt", "docs/installation.md")) {
        check(files[name]?.isNotEmpty() == true) { "Missing package documentation or license: $name" }
    }
    check(files.keys.filter { it.startsWith("lib/") }.map { it.removePrefix("lib/") }.toSet() == libraries) { "Unexpected runtime libraries" }
    CommandArchive(file, version, files)
}
private fun common(archive: CommandArchive) = archive.files.filterKeys { !it.startsWith("bin/") && !it.startsWith("vendor/windows-launcher/") }
    .mapValues { sha256(it.value) }
private fun verifySums(directory: Path, names: Set<String>) {
    val rows = Files.readAllLines(directory.resolve("SHA256SUMS"))
    check(rows.size == names.size)
    val seen = mutableSetOf<String>()
    for (row in rows) {
        val match = Regex("([0-9a-f]{64})  ([A-Za-z0-9_.-]+)").matchEntire(row) ?: error("Invalid checksum row")
        val (hash, name) = match.destructured
        check(name in names && seen.add(name) && sha256(Files.readAllBytes(directory.resolve(name))) == hash) { "Checksum mismatch" }
    }
    check(seen == names)
}
internal fun <T> withInstalled(archive: CommandArchive, block: (Path, Path) -> T): T {
    val work = Files.createTempDirectory("scouterctl-command-check-")
    try {
        val install = work.resolve("한글 설치 폴더").resolve("scouterctl-${archive.version}")
        if (System.getProperty("os.name").startsWith("Windows")) {
            for ((name, bytes) in archive.files) {
                val target = install.resolve(name)
                Files.createDirectories(target.parent)
                Files.write(target, bytes)
            }
        } else {
            Files.createDirectories(install.parent)
            val process = ProcessBuilder("unzip", "-q", archive.source.toString(), "-d", install.parent.toString())
                .redirectErrorStream(true).redirectOutput(work.resolve("extract.log").toFile()).start()
            try { check(process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0) { "ZIP extraction failed" } }
            finally { process.destroyForcibly() }
            check(Files.isExecutable(install.resolve("bin/scouterctl"))) { "ZIP lost launcher execution permission" }
        }
        return block(install, work)
    } finally { work.toFile().deleteRecursively() }
}
internal fun command(executable: Path, work: Path, arguments: List<String>, config: Path = work.resolve("연결 설정.json")): String {
    val stdout = work.resolve("command.stdout")
    val stderr = work.resolve("command.stderr")
    val process = ProcessBuilder(listOf(executable.toString()) + arguments).directory(work.toFile()).apply {
        environment()["SCOUTERCTL_CONFIG"] = config.toString()
        environment().remove("SCOUTERCTL_PASSWORD")
    }.redirectOutput(stdout.toFile()).redirectError(stderr.toFile()).start()
    process.outputStream.close()
    try {
        check(process.waitFor(30, TimeUnit.SECONDS)) { "Installed command timed out" }
        check(process.exitValue() == 0 && Files.readString(stderr).isEmpty()) { "Installed command failed: ${Files.readString(stderr)}" }
        return Files.readString(stdout)
    } finally { process.destroyForcibly() }
}
private fun verifyInstalled(executable: Path, work: Path, version: String) {
    check(command(executable, work, listOf("--version")).trim() == "scouterctl $version")
    check(command(executable, work, listOf("--help")).contains("counter export"))
    command(executable, work, listOf("context", "add", "--name", "운영_서울", "--user", "관측 사용자🚀"))
    val result = json.parseToJsonElement(command(executable, work, listOf("context", "list"))).jsonObject
    check(result["data"]!!.jsonObject["contexts"]!!.jsonObject["운영_서울"]!!.jsonObject["user"]!!.jsonPrimitive.content == "관측 사용자🚀")
}
internal fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
