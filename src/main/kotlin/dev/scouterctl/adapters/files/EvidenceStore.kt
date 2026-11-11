package dev.scouterctl.adapters.files

import dev.scouterctl.core.*
import dev.scouterctl.application.EvidenceRepository
import dev.scouterctl.adapters.serialization.*

import kotlinx.serialization.encodeToString
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.security.MessageDigest

class EvidenceStore(private val directory: Path) : EvidenceRepository {
    override fun create() = createCaptureDirectory(directory)

    override fun load(): EvidenceManifest = fileOperation {
        requireCaptureDirectory(directory)
        val content = read("manifest.json", 8_388_608)
        requireCaptureSchema(content, "scouterctl.capture/v2", "scouterctl.capture/v3")
        val manifest = json.decodeFromString<EvidenceManifestDto>(content).toModel()
        manifest.query.validate()
        if (manifest.query.pageSize > 100 || manifest.pages.size > 10000) damaged()
        if (manifest.cursor.txid.toLongOrNull() == null || manifest.cursor.bucketTime < 0) damaged()
        manifest
    }

    override fun save(manifest: EvidenceManifest) = fileOperation {
        write("manifest.json", json.encodeToString(manifest.toDto()))
    }

    override fun writePage(index: Int, records: List<EvidenceRecord>): EvidencePage = fileOperation {
        val content = records.joinToString("") { json.encodeToString(it.toDto()) + "\n" }
        val bytes = content.toByteArray(Charsets.UTF_8)
        if (bytes.size > 16_777_216) throw OperationFailure(ErrorCode.PARTIAL_RESULT, "Evidence page exceeds 16 MiB; use a smaller page-size")
        val hash = digest(bytes)
        val name = "page-%06d-%s.jsonl".format(index + 1, hash)
        write(name, content)
        EvidencePage(name, hash, records.size, records.count { it.profileStatus == ProfileAvailability.UNAVAILABLE })
    }

    override fun readPage(manifest: EvidenceManifest, index: Int): List<EvidenceRecord> = fileOperation {
        val page = manifest.pages[index]
        if (!page.sha256.matches(Regex("[0-9a-f]{64}")) ||
            page.name != "page-%06d-%s.jsonl".format(index + 1, page.sha256)) damaged()
        val content = read(page.name, 16_777_216)
        if (digest(content.toByteArray(Charsets.UTF_8)) != page.sha256) damaged()
        val records = content.lineSequence().filter { it.isNotEmpty() }.map { json.decodeFromString<EvidenceRecordDto>(it).toModel() }.toList()
        if (records.size != page.records || records.size > manifest.query.pageSize ||
            records.count { it.profileStatus == ProfileAvailability.UNAVAILABLE } != page.unavailableProfiles) damaged()
        records.forEach { record ->
            val xlog = record.xlog
            if (xlog.txid.toLongOrNull() == null || xlog.objHash != manifest.query.objHash ||
                xlog.endTime !in manifest.query.from until manifest.query.to) damaged()
            when (record.profileStatus) {
                ProfileAvailability.AVAILABLE -> if (record.profile == null || record.profile.txid != xlog.txid) damaged()
                ProfileAvailability.UNAVAILABLE -> if (record.profile != null) damaged()
            }
        }
        records
    }

    override fun validate(manifest: EvidenceManifest) = forEachRecord(manifest) { }

    override fun forEachRecord(manifest: EvidenceManifest, consume: (EvidenceRecord) -> Unit) = fileOperation {
        val ids = mutableSetOf<String>()
        manifest.pages.indices.forEach { index ->
            readPage(manifest, index).forEach {
                if (!ids.add(it.xlog.txid)) damaged()
                consume(it)
            }
        }
    }

    private fun read(name: String, limit: Int): String {
        val file = directory.resolve(name)
        if (!Files.isRegularFile(file, NOFOLLOW_LINKS)) damaged()
        Files.newInputStream(file, NOFOLLOW_LINKS).use {
            val bytes = it.readNBytes(limit + 1)
            if (bytes.size > limit) damaged()
            return bytes.toString(Charsets.UTF_8)
        }
    }

    private fun write(name: String, content: String) {
        val partial = directory.resolve("$name.partial")
        Files.newOutputStream(partial, NOFOLLOW_LINKS, java.nio.file.StandardOpenOption.CREATE,
            java.nio.file.StandardOpenOption.TRUNCATE_EXISTING, java.nio.file.StandardOpenOption.WRITE).use {
            it.write(content.toByteArray(Charsets.UTF_8))
        }
        replaceAtomically(partial, directory.resolve(name))
    }

    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
    private fun damaged(): Nothing = throw OperationFailure(ErrorCode.IO_ERROR, "Capture integrity or record validation failed")
}
