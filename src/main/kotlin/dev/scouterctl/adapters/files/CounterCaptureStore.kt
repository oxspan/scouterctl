package dev.scouterctl.adapters.files

import dev.scouterctl.core.*
import dev.scouterctl.application.CounterCaptureRepository
import dev.scouterctl.adapters.serialization.*
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.*
import java.security.MessageDigest
import java.util.Locale

class CounterCaptureStore(private val directory: Path) : CounterCaptureRepository {
    private var channel: FileChannel? = null

    override fun create(): Unit = fileOperation {
        Files.createDirectory(directory)
        lock()
    }

    override fun load(): CounterCaptureManifest = fileOperation {
        lock()
        val content = read("manifest.json", 16_777_216)
        requireCaptureSchema(content, "scouterctl.counter-capture/v1")
        decode<CounterCaptureManifestDto>(content).toModel()
    }

    override fun save(manifest: CounterCaptureManifest) = fileOperation {
        val content = json.encodeToString(manifest.toDto())
        if (content.toByteArray(Charsets.UTF_8).size > 16_777_216) {
            throw OperationFailure(ErrorCode.PARTIAL_RESULT, "Counter checkpoint exceeds 16 MiB")
        }
        write("manifest.json", content)
    }

    override fun writeChunk(index: Int, points: List<CounterPoint>): CounterChunkFile = fileOperation {
        val content = points.joinToString("") { json.encodeToString(it.toDto()) + "\n" }
        val bytes = content.toByteArray(Charsets.UTF_8)
        if (points.size > 100000 || bytes.size > 16_777_216) {
            throw OperationFailure(ErrorCode.PARTIAL_RESULT, "Counter chunk is too large; restart with smaller chunk-minutes")
        }
        val name = "chunk-%06d.jsonl".format(Locale.ROOT, index + 1)
        write(name, content)
        CounterChunkFile(name, digest(bytes), points.size)
    }

    override fun validate(manifest: CounterCaptureManifest): Unit = fileOperation {
        manifest.validate()
        manifest.chunks.forEachIndexed { index, chunk ->
            val file = chunk.file
            if (file.name != "chunk-%06d.jsonl".format(Locale.ROOT, index + 1) || !file.sha256.matches(Regex("[0-9a-f]{64}"))) damaged()
            val content = read(file.name, 16_777_216)
            if (digest(content.toByteArray(Charsets.UTF_8)) != file.sha256) damaged()
            val points = content.lineSequence().filter { it.isNotEmpty() }.map { decode<CounterPointDto>(it) }.toList()
            if (points.size != file.records) damaged()
            points.forEach { point ->
                if (point.time < chunk.query.from || point.time >= chunk.query.to) damaged()
                when (point.availability) {
                    "available" -> if (point.value == null || point.value.length > 256 || point.value.toBigDecimalOrNull() == null) damaged()
                    "unsupported" -> if (point.value != null) damaged()
                    else -> damaged()
                }
            }
        }
    }

    override fun close() {
        channel?.close()
        channel = null
    }

    private fun lock() {
        if (channel != null) return
        if (!Files.isDirectory(directory, NOFOLLOW_LINKS)) damaged()
        val opened = FileChannel.open(directory.resolve(".lock"), CREATE, WRITE, NOFOLLOW_LINKS)
        try {
            if (opened.tryLock() == null) throw OperationFailure(ErrorCode.IO_ERROR, "Counter capture is in use")
            channel = opened
        } catch (_: OverlappingFileLockException) {
            opened.close()
            throw OperationFailure(ErrorCode.IO_ERROR, "Counter capture is in use")
        } catch (error: Exception) {
            opened.close()
            throw error
        }
    }

    private inline fun <reified T> decode(content: String): T = try { json.decodeFromString<T>(content) }
        catch (_: SerializationException) { damaged() }

    private fun read(name: String, limit: Int): String {
        val file = directory.resolve(name)
        if (!Files.isRegularFile(file, NOFOLLOW_LINKS)) damaged()
        return Files.newInputStream(file, NOFOLLOW_LINKS).use {
            val bytes = it.readNBytes(limit + 1)
            if (bytes.size > limit) damaged()
            bytes.toString(Charsets.UTF_8)
        }
    }

    private fun write(name: String, content: String) {
        val partial = directory.resolve("$name.partial")
        Files.newOutputStream(partial, CREATE, TRUNCATE_EXISTING, WRITE, NOFOLLOW_LINKS).use {
            it.write(content.toByteArray(Charsets.UTF_8))
        }
        replaceAtomically(partial, directory.resolve(name))
    }

    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(Locale.ROOT, it) }
    private fun damaged(): Nothing = throw OperationFailure(ErrorCode.IO_ERROR, "Counter capture integrity check failed")
}
