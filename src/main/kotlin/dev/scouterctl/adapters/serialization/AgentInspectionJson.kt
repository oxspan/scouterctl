package dev.scouterctl.adapters.serialization

import dev.scouterctl.core.*
import kotlinx.serialization.Serializable

@Serializable
data class AgentIdentityDto(val objHash: Int, val objName: String, val objType: String, val alive: Boolean, val version: String)

@Serializable
data class AgentJvmDto(
    val javaVersion: String, val runtimeVersion: String?, val vmName: String?, val vmVersion: String?,
    val vendor: String?, val osName: String?, val osArch: String?,
)

@Serializable
data class AgentStatusDto(val collector: ServerInfoDto, val agent: AgentIdentityDto, val jvmAvailability: String, val jvm: AgentJvmDto?)

@Serializable
data class AgentConfigurationDto(val collector: ServerInfoDto, val agent: AgentIdentityDto, val settings: List<RuntimeSettingDto>)

fun AgentIdentity.toDto(): AgentIdentityDto = AgentIdentityDto(objHash, objName, objType, alive, version)

fun AgentStatus.toDto(source: ServerInfo): AgentStatusDto = AgentStatusDto(source.toDto(), agent.toDto(),
    jvmAvailability.name.lowercase(), jvm?.let { AgentJvmDto(it.javaVersion, it.runtimeVersion, it.vmName,
        it.vmVersion, it.vendor, it.osName, it.osArch) })

fun AgentConfiguration.toDto(source: ServerInfo): AgentConfigurationDto =
    AgentConfigurationDto(source.toDto(), agent.toDto(), settings.map { it.toDto() })
