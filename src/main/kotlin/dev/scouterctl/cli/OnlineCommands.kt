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
                OnlineRequest.Objects -> output.result(json.encodeToJsonElement(observation.objects().map { it.toDto() }))
                is OnlineRequest.Profile -> profile(observation, request)
                is OnlineRequest.Search -> {
                    streamXLogs(observation, request.query, request.maxPages, output::event)
                    0
                }
                is OnlineRequest.Capture -> capture(observation, endpoint, request)
            }
        }
    }
    private fun profile(source: ObservationSource, request: OnlineRequest.Profile): Int {
        val profile = source.profile(request.date, request.txid, request.includeText)
        return output.result(json.encodeToJsonElement(profile.toDto()))
    }

    private fun capture(source: ObservationSource, endpoint: Endpoint, request: OnlineRequest.Capture): Int {
        val options = CaptureOptions(request.resume, request.maxPages, "${endpoint.host}:${endpoint.port}", request.includeText)
        return if (request.includeProfiles) {
            val manifest = captureEvidence(source, request.query, options, evidence(request.directory))
            output.result(json.encodeToJsonElement(manifest.toDto()), manifest.complete)
        } else {
            val manifest = capture(source, request.query, options, captures(request.directory))
            output.result(json.encodeToJsonElement(manifest.toDto()), manifest.executionComplete)
        }
    }
}
