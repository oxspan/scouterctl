package dev.scouterctl.cli

import dev.scouterctl.application.*
import dev.scouterctl.core.ProfileCompleteness

import dev.scouterctl.adapters.serialization.*
import kotlinx.serialization.json.encodeToJsonElement
import java.nio.file.Path

class OfflineCommands(private val repository: (Path) -> EvidenceRepository, private val output: JsonOutput) {
    fun execute(request: OfflineRequest): Int = when (request) {
        is OfflineRequest.Inspect -> inspect(request)
        is OfflineRequest.Search -> search(request)
        is OfflineRequest.Summary -> summary(request)
        is OfflineRequest.Compare -> compare(request)
    }
    private fun inspect(request: OfflineRequest.Inspect): Int {
        val result = inspectCapture(repository(request.directory), request.txid, request.includeText)
        val data = if (request.groupSql) result.toSqlSummaryJson(result.record.profile?.let { summarizeSql(it, request.includeText) })
            else json.encodeToJsonElement(result.toDto())
        return output.result(data, result.captureExecutionComplete && result.unavailableProfiles == 0 &&
            result.record.profile?.completeness != ProfileCompleteness.TRUNCATED)
    }
    private fun search(request: OfflineRequest.Search): Int {
        val result = searchCapture(repository(request.directory), request.filter, request.includeText)
        val complete = result.captureExecutionComplete && result.summary.unavailableProfiles == 0 && result.lastFailure == null
        return output.result(json.encodeToJsonElement(result.toDto()), complete)
    }
    private fun summary(request: OfflineRequest.Summary): Int {
        val result = summarizeCapture(repository(request.directory), request.options)
        return output.result(json.encodeToJsonElement(result.toDto()), result.complete)
    }
    private fun compare(request: OfflineRequest.Compare): Int {
        val result = compareCaptures(repository(request.before), repository(request.after), request.options)
        return output.result(json.encodeToJsonElement(result.toDto()), result.complete)
    }
}
