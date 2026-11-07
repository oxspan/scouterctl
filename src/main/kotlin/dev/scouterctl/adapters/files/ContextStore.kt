package dev.scouterctl.adapters.files

import dev.scouterctl.core.*
import dev.scouterctl.application.ContextRepository
import dev.scouterctl.adapters.serialization.*

import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.PosixFilePermissions

class ContextStore(private val path: Path) : ContextRepository {
    override fun read(): ContextConfig = fileOperation {
        val text = try {
            Files.readString(path)
        } catch (_: NoSuchFileException) {
            return@fileOperation ContextConfig()
        }
        try {
            json.decodeFromString<ContextConfigDto>(text).toModel().also { it.validate() }
        } catch (_: SerializationException) {
            throw OperationFailure(ErrorCode.INVALID_ARGUMENT, "Invalid context config; repair it or select another SCOUTERCTL_CONFIG file")
        }
    }

    override fun update(change: (ContextConfig) -> ContextConfig): ContextConfig = fileOperation {
        val parent = path.toAbsolutePath().parent
        Files.createDirectories(parent)
        FileChannel.open(path.resolveSibling("${path.fileName}.lock"), CREATE, WRITE).use { channel ->
            val lock = channel.tryLock() ?: throw OperationFailure(ErrorCode.IO_ERROR, "Context config is busy; retry the command")
            lock.use {
                val updated = change(read())
                updated.validate()
                val attributes = if (Files.getFileStore(parent).supportsFileAttributeView("posix")) {
                    arrayOf(PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
                } else emptyArray()
                val temporary = Files.createTempFile(parent, ".scouterctl-", ".tmp", *attributes)
                try {
                    Files.writeString(temporary, json.encodeToString(updated.toDto()) + "\n")
                    Files.move(temporary, path, ATOMIC_MOVE, REPLACE_EXISTING)
                } finally { Files.deleteIfExists(temporary) }
                return@fileOperation updated
            }
        }
    }
}
