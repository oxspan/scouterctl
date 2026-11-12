package dev.scouterctl.application

import dev.scouterctl.core.*

private val visibleAgentSettings = setOf(
    "net_collector_tcp_port", "net_collector_udp_port", "xlog_sampling_enabled", "trace_sampling_enabled",
    "trace_interservice_enabled", "trace_auto_service_enabled", "profile_step_max_count", "profile_sql_max_count",
    "trace_sql_parameter_max_length", "profile_http_parameter_enabled", "profile_http_querystring_enabled",
)

private val visibleAgentTextSettings = setOf("net_collector_ip", "obj_name", "obj_type")

fun agentTextSettingValueIsVisible(key: String): Boolean = key in visibleAgentTextSettings

fun agentSettingValueIsVisible(key: String): Boolean = key in visibleAgentSettings || agentTextSettingValueIsVisible(key)

fun agentSettings(source: ObservationSource, objHash: Int, key: String?): AgentConfiguration {
    val configuration = source.agentSettings(objHash)
    val settings = configuration.settings.sortedBy { it.key }
    if (key == null) return configuration.copy(settings = settings)
    val selected = settings.singleOrNull { it.key == key }
        ?: throw OperationFailure(ErrorCode.NOT_FOUND, "Agent setting was not found")
    return configuration.copy(settings = listOf(selected))
}
