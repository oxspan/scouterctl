package dev.scouterctl.adapters.files

import kotlinx.serialization.encodeToString
import dev.scouterctl.core.*

import dev.scouterctl.application.CaptureRepository
import dev.scouterctl.adapters.serialization.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest

class CaptureStore(private val directory: Path) : CaptureRepository {
    override fun create() = fileOperation { Files.createDirectory(directory); Unit }
    override fun load(): CaptureManifest = fileOperation {
        val content = Files.readString(directory.resolve("manifest.json"))
        requireCaptureSchema(content, "scouterctl.capture/v1")
        json.decodeFromString<CaptureManifestDto>(content).toModel()
    }
    override fun save(manifest: CaptureManifest) = fileOperation {
        atomicWrite(directory.resolve("manifest.json"), json.encodeToString(manifest.toDto()))
    }
    override fun validate(manifest: CaptureManifest) = fileOperation {
        manifest.files.forEachIndexed { index, entry ->
            if (entry.name != "page-%06d.jsonl".format(index + 1) ||
                digest(Files.readAllBytes(directory.resolve(entry.name))) != entry.sha256) {
                throw OperationFailure(ErrorCode.IO_ERROR, "Capture file integrity check failed")
            }
        }
    }
    override fun writePage(index: Int, records: List<XLogRecord>): PageFile = fileOperation {
        val name = "page-%06d.jsonl".format(index + 1)
        val content = records.joinToString("") { json.encodeToString(it.toDto()) + "\n" }
        atomicWrite(directory.resolve(name), content)
        PageFile(name, digest(content.toByteArray(Charsets.UTF_8)), records.size)
    }
    private fun atomicWrite(path: Path, content: String) {
        val partial = path.resolveSibling(path.fileName.toString() + ".partial")
        Files.writeString(partial, content)
        Files.move(partial, path, ATOMIC_MOVE, REPLACE_EXISTING)
    }
    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}
