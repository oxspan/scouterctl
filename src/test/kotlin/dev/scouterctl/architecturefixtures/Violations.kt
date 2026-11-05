package dev.scouterctl.architecturefixtures.core

class FullyQualifiedIo {
    fun exists(path: String): Boolean = java.nio.file.Files.exists(java.nio.file.Path.of(path))
}
class AdapterProperty(val store: dev.scouterctl.adapters.files.EvidenceStore)
@kotlinx.serialization.Serializable
class WireAnnotation(val value: String)
class InnerOnly(val endpoint: dev.scouterctl.core.Endpoint)

class ProcessAndReflection {
    fun process(): Long = java.lang.ProcessHandle.current().pid()
    fun method(): java.lang.reflect.Method = String::class.java.getMethod("length")
    fun services(): java.util.ServiceLoader<Runnable> = java.util.ServiceLoader.load(Runnable::class.java)
}
