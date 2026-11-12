package dev.scouterctl.cli

import dev.scouterctl.core.*

import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter

internal fun Cli.interpret(): CommandRequest {
    val command = words.joinToString(" ")
    if (spec.commandLine().parseResult.hasMatchedOption("--group-sql") && command !in setOf("profile get", "inspect")) {
        invalid("--group-sql requires profile get or inspect")
    }
    if (command in setOf("context add", "context list", "context use")) return contextRequest(command)
    if (name != null) invalid("--name requires context add/use")
    if (command == "inspect compare") {
        allowedOptions()
        return OfflineRequest.Compare(required(before, "--before"), required(after, "--after"), ComparisonOptions(limit ?: 20, includeText))
    }
    if (command == "counter export") {
        allowedOptions()
        val budget = maxChunks ?: 24
        if (budget !in 1..10000) invalid("Use max-chunks 1..10000")
        val query = CounterExportQuery(Instant.parse(required(from, "--from")).toEpochMilli(),
            Instant.parse(required(to, "--to")).toEpochMilli(), required(timezone, "--timezone"),
            required(objHash, "--object-hash"), required(counterName, "--counter"), chunkMinutes ?: 60)
        query.windows()
        return OnlineRequest.CounterExport(query, required(directory, "--directory"), resume, budget)
    }
    if (chunkMinutes != null || maxChunks != null) invalid("--chunk-minutes and --max-chunks require counter export")
    if (command == "counter history") {
        allowedOptions()
        return OnlineRequest.CounterHistory(CounterHistoryQuery(required(date, "--date"),
            Instant.parse(required(from, "--from")).toEpochMilli(), Instant.parse(required(to, "--to")).toEpochMilli(),
            required(timezone, "--timezone"), required(objHash, "--object-hash"), required(counterName, "--counter")).also { it.validate() })
    }
    if (command in setOf("counter list", "counter get")) {
        allowedOptions()
        val counter = if (command == "counter get") required(counterName, "--counter") else null
        if (counter != null && (counter.isBlank() || counter.length > 256 || counter.any(Char::isISOControl))) invalid("Invalid counter name")
        return OnlineRequest.Counters(required(objHash, "--object-hash"), counter)
    }
    if (counterName != null) invalid("--counter requires counter get, counter history or counter export")
    if (command in setOf("collector status", "collector config", "agent status", "agent config")) {
        allowedOptions()
        settingKey?.let { if (!it.matches(Regex("[A-Za-z_][A-Za-z0-9_.-]{0,255}"))) invalid("Use a setting name for --key") }
        return when (command) {
            "collector status" -> OnlineRequest.CollectorStatus
            "collector config" -> OnlineRequest.CollectorConfig(settingKey)
            "agent status" -> OnlineRequest.AgentStatus(required(objHash, "--object-hash"))
            else -> OnlineRequest.AgentConfig(required(objHash, "--object-hash"), settingKey)
        }
    }
    if (settingKey != null) invalid("--key requires collector config or agent config")
    if (before != null || after != null) invalid("--before and --after require inspect compare")
    if (command == "inspect summary") {
        allowedOptions()
        return OfflineRequest.Summary(required(directory, "--directory"),
            ServiceSummaryOptions(limit ?: 20, ServiceSort.parse(summarySort ?: "errors"), includeText))
    }
    if (summarySort != null) invalid("--sort requires inspect summary")
    validateGeneralOptions(command)
    return when (command) {
        "inspect search" -> {
            if (resume || txid != null) invalid("inspect search does not accept --resume or --txid")
            OfflineRequest.Search(required(directory, "--directory"),
                EvidenceFilter(limit ?: 20, minElapsedMs ?: 0, errorsOnly, serviceHash, serviceName), includeText)
        }
        "inspect" -> {
            if (resume) invalid("--resume requires capture")
            OfflineRequest.Inspect(required(directory, "--directory"), required(txid, "--txid"), includeText, groupSql)
        }
        else -> onlineRequest(command)
    }
}

private fun Cli.contextRequest(command: String): ContextRequest {
    allowedOptions()
    return when (command) {
        "context add" -> ContextRequest.Add(name, connection.host ?: "127.0.0.1", connection.port ?: 6100, connection.user)
        "context use" -> ContextRequest.Use(name)
        else -> ContextRequest.ListContexts
    }
}

private fun Cli.validateGeneralOptions(command: String) {
    val supported = setOf("doctor", "auth login", "object list", "xlog search", "profile get", "capture", "inspect", "inspect search")
    if (command !in supported) invalid("Unknown command")
    if (command == "auth login") {
        allowedOptions()
    }
    if (command != "inspect search" && (limit != null || minElapsedMs != null || errorsOnly || serviceHash != null || serviceName != null)) {
        val option = spec.commandLine().parseResult.matchedOptions().first {
            it.longestName() in setOf("--limit", "--min-elapsed-ms", "--errors-only", "--service-hash", "--service-name")
        }.longestName()
        invalid("$option requires inspect search")
    }
    if (includeProfiles && command != "capture") invalid("--include-profiles requires capture")
    if (includeText && command !in setOf("xlog search", "profile get", "inspect", "inspect search") && !(command == "capture" && includeProfiles)) {
        invalid("--include-text requires xlog search, profile get, inspect or capture --include-profiles")
    }
}

private fun Cli.onlineRequest(command: String): OnlineRequest {
    if (maxPages !in 1..40) invalid("Use --max-pages 1..40")
    val query = if (command in setOf("xlog search", "capture")) query() else null
    if (command == "profile get") {
        validateStorageDate(required(date, "--date"))
        required(txid, "--txid")
    }
    if (command == "capture") required(directory, "--directory")
    if (includeProfiles && required(query, "query").pageSize > 100) invalid("Profile capture page-size must be 1..100")
    return when (command) {
        "doctor", "auth login" -> OnlineRequest.Doctor(command == "auth login")
        "object list" -> OnlineRequest.Objects(date?.also(::validateStorageDate))
        "profile get" -> OnlineRequest.Profile(required(date, "--date"), required(txid, "--txid"), includeText, groupSql)
        "xlog search" -> OnlineRequest.Search(required(query, "query"), maxPages, includeText)
        "capture" -> OnlineRequest.Capture(
            required(query, "query"), required(directory, "--directory"), resume, maxPages, includeProfiles, includeText,
        )
        else -> invalid("Unknown command")
    }
}

private fun validateStorageDate(date: String) {
    if (!date.matches(Regex("[0-9]{8}"))) invalid("Use an 8-digit storage date")
    LocalDate.parse(date, DateTimeFormatter.BASIC_ISO_DATE)
}

private fun Cli.query(): Query = Query(required(date, "--date"),
    Instant.parse(required(from, "--from")).toEpochMilli(), Instant.parse(required(to, "--to")).toEpochMilli(),
    required(timezone, "--timezone"), required(objHash, "--object-hash"), pageSize ?: if (includeProfiles) 25 else 1000).also { it.validate() }

private fun Cli.allowedOptions() {
    val allowed = commandGuides.getValue(words.joinToString(" ")).allowed
    spec.commandLine().parseResult.matchedOptions().firstOrNull { it.longestName() !in allowed }?.let {
        invalid("Option ${it.longestName()} is not supported by this command; use --help")
    }
}
private fun invalid(message: String): Nothing = throw OperationFailure(ErrorCode.INVALID_ARGUMENT, message)
private fun <T> required(value: T?, name: String): T = value ?: invalid("Missing $name")
