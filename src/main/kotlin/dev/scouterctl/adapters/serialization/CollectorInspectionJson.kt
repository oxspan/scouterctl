package dev.scouterctl.adapters.serialization

import dev.scouterctl.core.*
import kotlinx.serialization.Serializable

@Serializable
data class CollectorStatusDto(
    val collector: ServerInfoDto, val usedHeapBytes: Long, val committedHeapBytes: Long, val serverTimeEpochMs: Long,
)

@Serializable
data class CollectorSettingsDto(val collector: ServerInfoDto, val settings: List<RuntimeSettingDto>)

fun CollectorStatus.toDto(source: ServerInfo): CollectorStatusDto =
    CollectorStatusDto(source.toDto(), usedHeapBytes, committedHeapBytes, serverTimeEpochMs)

fun List<RuntimeSetting>.toSettingsDto(source: ServerInfo): CollectorSettingsDto =
    CollectorSettingsDto(source.toDto(), map { it.toDto() })
