package dev.scouterctl.adapters.serialization

import dev.scouterctl.core.*
import kotlinx.serialization.Serializable

@Serializable
data class CollectorStatusDto(
    val collector: ServerInfoDto, val usedHeapBytes: Long, val committedHeapBytes: Long, val serverTimeEpochMs: Long,
)

@Serializable
data class CollectorSettingDto(val key: String, val value: String?, val defaultValue: String?, val redacted: Boolean)

@Serializable
data class CollectorSettingsDto(val collector: ServerInfoDto, val settings: List<CollectorSettingDto>)

fun CollectorStatus.toDto(source: ServerInfo): CollectorStatusDto =
    CollectorStatusDto(source.toDto(), usedHeapBytes, committedHeapBytes, serverTimeEpochMs)

fun List<CollectorSetting>.toSettingsDto(source: ServerInfo): CollectorSettingsDto =
    CollectorSettingsDto(source.toDto(), map { CollectorSettingDto(it.key, it.value, it.defaultValue, it.redacted) })
