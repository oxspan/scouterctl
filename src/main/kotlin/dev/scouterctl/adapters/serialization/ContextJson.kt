package dev.scouterctl.adapters.serialization

import dev.scouterctl.core.*
import kotlinx.serialization.Serializable

@Serializable
data class SavedConnectionDto(val host: String, val port: Int, val user: String)

@Serializable
data class ContextConfigDto(
    val schemaVersion: String = "scouterctl.contexts/v1",
    val current: String? = null,
    val contexts: Map<String, SavedConnectionDto> = emptyMap(),
)

fun SavedConnection.toDto(): SavedConnectionDto = SavedConnectionDto(host, port, user)

fun SavedConnectionDto.toModel(): SavedConnection = SavedConnection(host, port, user)

fun ContextConfig.toDto(): ContextConfigDto = ContextConfigDto(schemaVersion, current, contexts.mapValues { (_, value) -> value.toDto() })

fun ContextConfigDto.toModel(): ContextConfig = ContextConfig(schemaVersion, current, contexts.mapValues { (_, value) -> value.toModel() })
