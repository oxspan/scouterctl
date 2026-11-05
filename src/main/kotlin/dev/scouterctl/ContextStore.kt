package dev.scouterctl

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.PosixFilePermissions

@Serializable
data class SavedConnection(val host: String, val port: Int, val user: String) {
    fun endpoint(timeoutMs: Int) = Endpoint(host, port, user, timeoutMs)
}

@Serializable
data class ContextConfig(
    val schemaVersion: String = "scouterctl.contexts/v1",
    val current: String? = null,
    val contexts: Map<String, SavedConnection> = emptyMap(),
) {
    fun validate() {
        if (schemaVersion != "scouterctl.contexts/v1" || (current != null && current !in contexts)) {
            throw CliFailure(ErrorCode.INVALID_ARGUMENT, "Unsupported or inconsistent context config")
        }
        contexts.forEach { (name, connection) -> validateContextName(name); connection.endpoint(5000) }
    }
}

internal fun validateContextName(name: String) {
    if (name.codePointCount(0, name.length) !in 1..64 ||
        !name.matches(Regex("[\\p{L}\\p{Nd}][\\p{L}\\p{Nd}\\p{M}._-]*"))) {
        throw CliFailure(ErrorCode.INVALID_ARGUMENT, "Context name must be 1..64 Unicode code points: letters, digits, combining marks, dots, underscores or hyphens; start with a letter or digit")
    }
}

class ContextStore(private val path: Path = configPath()) {
    private val codec = Json { encodeDefaults = true }

    fun read(): ContextConfig {
        val text = try { Files.readString(path) } catch (_: NoSuchFileException) { return ContextConfig() }
        return try { codec.decodeFromString<ContextConfig>(text).also { it.validate() } }
        catch (_: SerializationException) {
            throw CliFailure(ErrorCode.INVALID_ARGUMENT, "Invalid context config; repair it or select another SCOUTERCTL_CONFIG file")
        }
    }

    fun selected(name: String?): SavedConnection? {
        val config = read()
        val selected = name ?: config.current ?: return null
        validateContextName(selected)
        return config.contexts[selected]
            ?: throw CliFailure(ErrorCode.INVALID_ARGUMENT, "Unknown context; use context list or context add")
    }

    fun add(name: String, connection: SavedConnection): ContextConfig {
        validateContextName(name)
        connection.endpoint(5000)
        return update { config ->
            if (name in config.contexts) throw CliFailure(ErrorCode.INVALID_ARGUMENT, "Context already exists; choose another name")
            config.copy(contexts = config.contexts + (name to connection))
        }
    }

    fun use(name: String): ContextConfig {
        validateContextName(name)
        return update { config ->
            if (name !in config.contexts) throw CliFailure(ErrorCode.INVALID_ARGUMENT, "Unknown context; use context list or context add")
            config.copy(current = name)
        }
    }

    private fun update(change: (ContextConfig) -> ContextConfig): ContextConfig {
        val parent = path.toAbsolutePath().parent
        Files.createDirectories(parent)
        FileChannel.open(path.resolveSibling("${path.fileName}.lock"), CREATE, WRITE).use { channel ->
            val lock = channel.tryLock() ?: throw CliFailure(ErrorCode.IO_ERROR, "Context config is busy; retry the command")
            lock.use {
                val updated = change(read())
                updated.validate()
                val attributes = if (Files.getFileStore(parent).supportsFileAttributeView("posix")) {
                    arrayOf(PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
                } else emptyArray()
                val temporary = Files.createTempFile(parent, ".scouterctl-", ".tmp", *attributes)
                try {
                    Files.writeString(temporary, codec.encodeToString(updated) + "\n")
                    Files.move(temporary, path, ATOMIC_MOVE, REPLACE_EXISTING)
                } finally { Files.deleteIfExists(temporary) }
                return updated
            }
        }
    }
}

private fun configPath(): Path {
    val explicit = System.getenv("SCOUTERCTL_CONFIG")
    if (explicit != null) {
        if (explicit.isBlank()) throw CliFailure(ErrorCode.INVALID_ARGUMENT, "SCOUTERCTL_CONFIG must name a file")
        return Path.of(explicit).toAbsolutePath()
    }
    val xdg = System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }
    val base = if (xdg != null) Path.of(xdg) else Path.of(System.getProperty("user.home"), ".config")
    if (!base.isAbsolute) throw CliFailure(ErrorCode.INVALID_ARGUMENT, "XDG_CONFIG_HOME must be absolute")
    return base.resolve("scouterctl/config.json")
}
