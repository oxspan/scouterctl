package dev.scouterctl.cli

import dev.scouterctl.core.*
import dev.scouterctl.application.*

import dev.scouterctl.adapters.serialization.*
import kotlinx.serialization.json.encodeToJsonElement
import java.nio.file.Path

class OnlineCommands(
    private val contexts: () -> ContextRepository,
    private val source: (Endpoint, String) -> ObservationSource,
    private val captures: (Path) -> CaptureRepository,
    private val evidence: (Path) -> EvidenceRepository,
    private val counterCaptures: (Path) -> CounterCaptureRepository,
    private val password: PasswordInput,
    private val output: JsonOutput,
) {
    fun execute(request: OnlineRequest, connection: ConnectionOptions): Int {
        val endpoint = resolveEndpoint({ Contexts(contexts()) }, connection.host, connection.port,
            connection.user, connection.context, connection.timeoutMs)
        val secret = password.read(connection.interactive, connection.noInput, request is OnlineRequest.Doctor && request.login)
        return source(endpoint, secret).use { observation ->
            val timezoneCheck = when (request) {
                is OnlineRequest.Search -> validateCollectorTimezone(request.query.timezone, request.query.from, request.query.to, observation.timezone())
                is OnlineRequest.Capture -> validateCollectorTimezone(request.query.timezone, request.query.from, request.query.to, observation.timezone())
                is OnlineRequest.CounterHistory -> validateCollectorTimezone(request.query.timezone, request.query.from, request.query.to, observation.timezone())
                is OnlineRequest.CounterExport -> validateCollectorTimezone(request.query.timezone, request.query.from, request.query.to, observation.timezone())
                else -> null
            }
            when (request) {
                is OnlineRequest.Doctor -> output.result(observation.info.toLiveJson(observation.timezone()))
                OnlineRequest.CollectorStatus -> output.result(observation.status().toLiveJson(observation.info, observation.timezone()))
                is OnlineRequest.CollectorConfig -> output.result(json.encodeToJsonElement(collectorSettings(observation, request.key).toSettingsDto(observation.info)))
                is OnlineRequest.AgentStatus -> output.result(json.encodeToJsonElement(observation.agentStatus(request.objHash).toDto(observation.info)))
                is OnlineRequest.AgentConfig -> output.result(json.encodeToJsonElement(agentSettings(observation, request.objHash, request.key).toDto(observation.info)))
                is OnlineRequest.AgentActiveRequests -> {
                    val snapshot = observation.activeRequests(request.objHash, request.includeText)
                    output.result(json.encodeToJsonElement(snapshot.toDto(observation.info)), snapshot.complete)
                }
                is OnlineRequest.AgentThreadDump -> output.result(json.encodeToJsonElement(observation.threadDump(request.objHash).toDto(observation.info)))
                is OnlineRequest.CounterExport -> counterCaptures(request.directory).use { store ->
                    val manifest = exportCounters(observation, request.query,
                        CounterExportOptions(request.resume, request.maxChunks, "${endpoint.host}:${endpoint.port}"), store)
                    output.result(json.encodeToJsonElement(manifest.toDto()), manifest.executionComplete, timezoneCheck = timezoneCheck)
                }
                is OnlineRequest.CounterHistory -> {
                    streamCounterHistory(observation, request.query) { output.counterHistoryEvent(it, timezoneCheck) }
                    0
                }
                is OnlineRequest.Counters -> output.result(json.encodeToJsonElement(realtimeCounters(observation, request.objHash, request.name).toDto(observation.info)))
                is OnlineRequest.Objects -> output.result(json.encodeToJsonElement(observation.objects(request.date).map { it.toDto() }))
                is OnlineRequest.Profile -> profile(observation, request)
                is OnlineRequest.Search -> {
                    streamXLogs(observation, request.query, request.maxPages, request.includeText) { output.event(it, timezoneCheck) }
                    0
                }
                is OnlineRequest.Capture -> capture(observation, endpoint, request, timezoneCheck)
            }
        }
    }
    private fun profile(source: ObservationSource, request: OnlineRequest.Profile): Int {
        val profile = source.profile(request.date, request.txid, request.includeText, includeStepDetails = true)
        val data = if (request.groupSql) json.encodeToJsonElement(summarizeSql(profile, request.includeText).toDto()) else profile.toLiveJson()
        return output.result(data, profile.completeness != ProfileCompleteness.TRUNCATED)
    }

    private fun capture(source: ObservationSource, endpoint: Endpoint, request: OnlineRequest.Capture,
        timezoneCheck: QueryTimezoneCheck?): Int {
        return if (request.includeProfiles) {
            val manifest = CaptureCancellation().use { cancellation ->
                val options = CaptureOptions(request.resume, request.maxPages, "${endpoint.host}:${endpoint.port}",
                    request.includeText, request.maxTotalPages, cancellation::cancelled)
                captureEvidence(source, request.query, options, evidence(request.directory))
            }
            output.result(json.encodeToJsonElement(manifest.toDto()), manifest.complete, xlogCapture = true, timezoneCheck = timezoneCheck)
        } else {
            val options = CaptureOptions(request.resume, request.maxPages, "${endpoint.host}:${endpoint.port}", request.includeText)
            val manifest = capture(source, request.query, options, captures(request.directory))
            output.result(json.encodeToJsonElement(manifest.toDto()), manifest.executionComplete, xlogCapture = true, timezoneCheck = timezoneCheck)
        }
    }
}
