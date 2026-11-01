package dev.scouterctl

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest

val json = Json { encodeDefaults = true }

@Serializable
data class PageFile(val name: String, val sha256: String, val records: Int)
@Serializable
data class CaptureManifest(
    val schemaVersion: String = "scouterctl.capture/v1",
    val source: ServerInfo,
    val endpoint: String,
    val query: Query,
    val cursor: Cursor = Cursor(),
    val executionComplete: Boolean = false,
    val upstreamCoverage: String = "unknown",
    val sampling: String = "unknown",
    val timezoneVerification: String = "operator-declared",
    val files: List<PageFile> = emptyList(),
)

data class CaptureRequest(val directory: Path, val resume: Boolean, val maxPages: Int, val endpoint: String)

fun capture(source: ObservationSource, query: Query, request: CaptureRequest): CaptureManifest {
    query.validate()
    val directory = request.directory
    val manifestPath = directory.resolve("manifest.json")
    var manifest = if (request.resume) {
        val loaded = json.decodeFromString<CaptureManifest>(Files.readString(manifestPath))
        if (loaded.schemaVersion != "scouterctl.capture/v1" || loaded.query != query ||
            loaded.source != source.info || loaded.endpoint != request.endpoint) {
            throw CliFailure(ErrorCode.INVALID_ARGUMENT, "Resume source or query differs from manifest")
        }
        loaded.files.forEachIndexed { index, entry ->
            if (entry.name != "page-%06d.jsonl".format(index + 1) ||
                digest(Files.readAllBytes(directory.resolve(entry.name))) != entry.sha256) {
                throw CliFailure(ErrorCode.IO_ERROR, "Capture file integrity check failed")
            }
        }
        loaded
    } else {
        Files.createDirectory(directory)
        CaptureManifest(source = source.info, endpoint = request.endpoint, query = query).also {
            atomicWrite(manifestPath, json.encodeToString(it))
        }
    }
    if (manifest.executionComplete) return manifest
    repeat(request.maxPages) {
        val page = source.page(query, manifest.cursor)
        val name = "page-%06d.jsonl".format(manifest.files.size + 1)
        val content = page.records.joinToString("") { json.encodeToString(it) + "\n" }
        atomicWrite(directory.resolve(name), content)
        manifest = manifest.copy(cursor = page.cursor, executionComplete = !page.hasMore,
            files = manifest.files + PageFile(name, digest(content.toByteArray(Charsets.UTF_8)), page.records.size))
        atomicWrite(manifestPath, json.encodeToString(manifest))
        if (manifest.executionComplete) return manifest
    }
    return manifest
}

private fun atomicWrite(path: Path, content: String) {
    val partial = path.resolveSibling(path.fileName.toString() + ".partial")
    Files.writeString(partial, content)
    Files.move(partial, path, ATOMIC_MOVE, REPLACE_EXISTING)
}

private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes).joinToString("") { "%02x".format(it) }
