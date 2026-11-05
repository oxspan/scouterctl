package dev.scouterctl.cli

import dev.scouterctl.core.*

import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter

internal fun Cli.interpret(): CommandRequest {
    val command = words.joinToString(" ")
    if (command in setOf("context add", "context list", "context use")) return contextRequest(command)
    if (name != null) invalid("--name requires context add/use")
    if (command == "inspect compare") {
        allowedOptions(setOf("--before", "--after", "--limit", "--include-text", "--no-input"))
        return OfflineRequest.Compare(required(before, "--before"), required(after, "--after"), ComparisonOptions(limit ?: 20, includeText))
    }
    if (before != null || after != null) invalid("--before and --after require inspect compare")
    if (command == "inspect summary") {
        allowedOptions(setOf("--directory", "--limit", "--sort", "--include-text", "--no-input"))
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
            OfflineRequest.Inspect(required(directory, "--directory"), required(txid, "--txid"), includeText)
        }
        else -> onlineRequest(command)
    }
}

private fun Cli.contextRequest(command: String): ContextRequest {
    val allowed = when (command) {
        "context add" -> setOf("--name", "--host", "--port", "--user", "--no-input")
        "context use" -> setOf("--name", "--no-input")
        else -> setOf("--no-input")
    }
    allowedOptions(allowed)
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
        allowedOptions(setOf("--host", "--port", "--user", "--context", "--timeout-ms", "--interactive", "--no-input"))
    }
    if (command != "inspect search" && (limit != null || minElapsedMs != null || errorsOnly || serviceHash != null || serviceName != null)) {
        invalid("Search filters require inspect search")
    }
    if (includeProfiles && command != "capture") invalid("--include-profiles requires capture")
    if (includeText && command !in setOf("profile get", "inspect", "inspect search") && !(command == "capture" && includeProfiles)) {
        invalid("--include-text requires profile get, inspect or capture --include-profiles")
    }
}

private fun Cli.onlineRequest(command: String): OnlineRequest {
    if (maxPages !in 1..40) invalid("Use --max-pages 1..40")
    val query = if (command in setOf("xlog search", "capture")) query() else null
    if (command == "profile get") {
        if (!required(date, "--date").matches(Regex("[0-9]{8}"))) invalid("Use an 8-digit storage date")
        LocalDate.parse(required(date, "--date"), DateTimeFormatter.BASIC_ISO_DATE)
        required(txid, "--txid")
    }
    if (command == "capture") required(directory, "--directory")
    if (includeProfiles && required(query, "query").pageSize > 100) invalid("Profile capture page-size must be 1..100")
    return when (command) {
        "doctor", "auth login" -> OnlineRequest.Doctor(command == "auth login")
        "object list" -> OnlineRequest.Objects
        "profile get" -> OnlineRequest.Profile(required(date, "--date"), required(txid, "--txid"), includeText)
        "xlog search" -> OnlineRequest.Search(required(query, "query"), maxPages)
        "capture" -> OnlineRequest.Capture(
            required(query, "query"), required(directory, "--directory"), resume, maxPages, includeProfiles, includeText,
        )
        else -> invalid("Unknown command")
    }
}

private fun Cli.query(): Query = Query(required(date, "--date"),
    Instant.parse(required(from, "--from")).toEpochMilli(), Instant.parse(required(to, "--to")).toEpochMilli(),
    required(timezone, "--timezone"), required(objHash, "--object-hash"), pageSize ?: if (includeProfiles) 25 else 1000).also { it.validate() }

private fun Cli.allowedOptions(allowed: Set<String>) {
    if (spec.commandLine().parseResult.matchedOptions().any { it.longestName() !in allowed }) {
        invalid("Option is not supported by this command; use --help")
    }
}
private fun invalid(message: String): Nothing = throw OperationFailure(ErrorCode.INVALID_ARGUMENT, message)
private fun <T> required(value: T?, name: String): T = value ?: invalid("Missing $name")
