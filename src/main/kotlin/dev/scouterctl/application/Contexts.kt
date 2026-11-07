package dev.scouterctl.application

import dev.scouterctl.core.*

class Contexts(private val repository: ContextRepository) {
    fun read(): ContextConfig = repository.read()
    fun selected(name: String?): SavedConnection? {
        val config = repository.read()
        val selected = name ?: config.current ?: return null
        validateContextName(selected)
        return config.contexts[selected]
            ?: throw OperationFailure(ErrorCode.INVALID_ARGUMENT, "Unknown context; use context list or context add")
    }

    fun add(name: String, connection: SavedConnection): ContextConfig {
        validateContextName(name)
        connection.endpoint(5000)
        return repository.update { config ->
            if (name in config.contexts) throw OperationFailure(ErrorCode.INVALID_ARGUMENT, "Context already exists; choose another name")
            config.copy(contexts = config.contexts + (name to connection))
        }
    }

    fun use(name: String): ContextConfig {
        validateContextName(name)
        return repository.update { config ->
            if (name !in config.contexts) throw OperationFailure(ErrorCode.INVALID_ARGUMENT, "Unknown context; use context list or context add")
            config.copy(current = name)
        }
    }

}

fun resolveEndpoint(contexts: () -> Contexts, host: String?, port: Int?, user: String?, context: String?, timeoutMs: Int): Endpoint {
    val saved = if (context == null && host != null && port != null && user != null) null else contexts().selected(context)
    return Endpoint(host ?: saved?.host ?: "127.0.0.1", port ?: saved?.port ?: 6100,
        user ?: saved?.user ?: throw OperationFailure(ErrorCode.INVALID_ARGUMENT,
            "Missing --user; select a context or provide a Collector account"), timeoutMs)
}
