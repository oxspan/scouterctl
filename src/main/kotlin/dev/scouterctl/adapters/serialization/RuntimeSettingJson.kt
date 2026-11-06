package dev.scouterctl.adapters.serialization

import dev.scouterctl.core.RuntimeSetting
import kotlinx.serialization.Serializable

@Serializable
data class RuntimeSettingDto(val key: String, val value: String?, val defaultValue: String?, val redacted: Boolean)

fun RuntimeSetting.toDto(): RuntimeSettingDto = RuntimeSettingDto(key, value, defaultValue, redacted)
