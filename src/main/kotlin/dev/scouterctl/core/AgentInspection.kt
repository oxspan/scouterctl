package dev.scouterctl.core

data class AgentIdentity(val objHash: Int, val objName: String, val objType: String, val alive: Boolean, val version: String)

data class AgentJvm(
    val javaVersion: String, val runtimeVersion: String?, val vmName: String?, val vmVersion: String?,
    val vendor: String?, val osName: String?, val osArch: String?,
)

enum class AgentJvmAvailability { AVAILABLE, OFFLINE, UNAVAILABLE }

data class AgentStatus(val agent: AgentIdentity, val jvmAvailability: AgentJvmAvailability, val jvm: AgentJvm?)

data class AgentConfiguration(val agent: AgentIdentity, val settings: List<RuntimeSetting>)
