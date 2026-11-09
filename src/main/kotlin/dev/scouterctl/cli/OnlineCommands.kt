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
            when (request) {
                is OnlineRequest.Doctor -> output.result(json.encodeToJsonElement(observation.info.toDto()))
                OnlineRequest.CollectorStatus -> output.result(json.encodeToJsonElement(observation.status().toDto(observation.info)))
                is OnlineRequest.CollectorConfig -> output.result(json.encodeToJsonElement(collectorSettings(observation, request.key).toSettingsDto(observation.info)))
                is OnlineRequest.AgentStatus -> output.result(json.encodeToJsonElement(observation.agentStatus(request.objHash).toDto(observation.info)))
                is OnlineRequest.AgentConfig -> output.result(json.encodeToJsonElement(agentSettings(observation, request.objHash, request.key).toDto(observation.info)))
                is OnlineRequest.CounterExport -> counterCaptures(request.directory).use { store ->
                    val manifest = exportCounters(observation, request.query,
                        CounterExportOptions(request.resume, request.maxChunks, "${endpoint.host}:${endpoint.port}"), store)
                    output.result(json.encodeToJsonElement(manifest.toDto()), manifest.executionComplete)
                }
                is OnlineRequest.CounterHistory -> {
                    streamCounterHistory(observation, request.query, output::counterHistoryEvent)
                    0
                }
                is OnlineRequest.Counters -> output.result(json.encodeToJsonElement(realtimeCounters(observation, request.objHash, request.name).toDto(observation.info)))
                is OnlineRequest.Objects -> output.result(json.encodeToJsonElement(observation.objects(request.date).map { it.toDto() }))
                is OnlineRequest.Profile -> profile(observation, request)
                is OnlineRequest.Search -> {
                    streamXLogs(observation, request.query, request.maxPages, request.includeText, output::event)
                    0
                }
                is OnlineRequest.Capture -> capture(observation, endpoint, request)
            }
        }
    }
    private fun profile(source: ObservationSource, request: OnlineRequest.Profile): Int {
        val profile = source.profile(request.date, request.txid, request.includeText, includeStepDetails = true)
        return output.result(profile.toLiveJson(), profile.completeness != ProfileCompleteness.TRUNCATED)
    }

    private fun capture(source: ObservationSource, endpoint: Endpoint, request: OnlineRequest.Capture): Int {
        val options = CaptureOptions(request.resume, request.maxPages, "${endpoint.host}:${endpoint.port}", request.includeText)
        return if (request.includeProfiles) {
            val manifest = captureEvidence(source, request.query, options, evidence(request.directory))
            output.result(json.encodeToJsonElement(manifest.toDto()), manifest.complete, xlogCapture = true)
        } else {
            val manifest = capture(source, request.query, options, captures(request.directory))
            output.result(json.encodeToJsonElement(manifest.toDto()), manifest.executionComplete, xlogCapture = true)
        }
    }
}
