package dev.scouterctl.application

import dev.scouterctl.core.*

private val visibleCollectorSettings = setOf(
    "net_tcp_listen_port", "net_udp_listen_port", "net_http_server_enabled", "net_http_api_enabled",
    "mgr_purge_enabled", "mgr_purge_keep_days", "mgr_purge_profile_keep_days",
    "mgr_purge_xlog_keep_days", "mgr_purge_counter_keep_days",
)

fun collectorSettingValueIsVisible(key: String): Boolean = key in visibleCollectorSettings

fun collectorSettings(source: ObservationSource, key: String?): List<RuntimeSetting> {
    val settings = source.settings().sortedBy { it.key }
    if (key == null) return settings
    return listOf(settings.singleOrNull { it.key == key }
        ?: throw OperationFailure(ErrorCode.NOT_FOUND, "Collector setting was not found"))
}
