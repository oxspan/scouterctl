package dev.scouterctl.cli

import dev.scouterctl.application.*
import dev.scouterctl.core.*

import dev.scouterctl.adapters.serialization.*
import kotlinx.serialization.json.encodeToJsonElement

class ContextCommands(private val repository: () -> ContextRepository, private val output: JsonOutput) {
    fun execute(request: ContextRequest): Int {
        val contexts = Contexts(repository())
        val config = when (request) {
            ContextRequest.ListContexts -> contexts.read()
            is ContextRequest.Add -> contexts.add(
                required(request.name, "--name"),
                SavedConnection(request.host, request.port, required(request.user, "--user")),
            )
            is ContextRequest.Use -> contexts.use(required(request.name, "--name"))
        }
        return output.result(json.encodeToJsonElement(config.toDto()))
    }
}

private fun <T> required(value: T?, option: String): T = value
    ?: throw OperationFailure(ErrorCode.INVALID_ARGUMENT, "Missing $option")
