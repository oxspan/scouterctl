package dev.scouterctl

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import picocli.CommandLine.Mixin
import picocli.CommandLine.Spec
import picocli.CommandLine.Model.CommandSpec
import java.io.IOException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.concurrent.Callable
import kotlin.system.exitProcess

@Command(name = "scouterctl", mixinStandardHelpOptions = true, versionProvider = CliVersion::class,
    description = ["Read-only Collector 2.21.3 POC. Commands: doctor, object list, xlog search, profile get, capture, inspect, inspect search, inspect summary.",
        "Connect: doctor --host HOST --port PORT --user ACCOUNT, then object list with the same options.",
        "Save/select connections: context add/list/use --name NAME. Check credentials: auth login.",
        "Password: SCOUTERCTL_PASSWORD, or --interactive for hidden terminal input. Passwords are never saved.",
        "TCP has no native TLS; use a trusted path."])
class Cli : Callable<Int> {
    @Parameters(arity = "1..2", paramLabel = "COMMAND") lateinit var words: List<String>
    @Mixin val connection = ConnectionOptions()
    @Spec lateinit var spec: CommandSpec
    @Option(names = ["--name"], description = ["Name for context add/use"]) var name: String? = null
    @Option(names = ["--date"]) var date: String? = null
    @Option(names = ["--from"]) var from: String? = null
    @Option(names = ["--to"]) var to: String? = null
    @Option(names = ["--timezone"], description = ["Operator-declared collector IANA timezone"] ) var timezone: String? = null
    @Option(names = ["--object-hash"]) var objHash: Int? = null
    @Option(names = ["--page-size"], description = ["Default 1000; profile capture default 25, maximum 100"]) var pageSize: Int? = null
    @Option(names = ["--max-pages"], defaultValue = "40") var maxPages: Int = 40
    @Option(names = ["--txid"]) var txid: Long? = null
    @Option(names = ["--directory"]) var directory: Path? = null
    @Option(names = ["--resume"]) var resume: Boolean = false
    @Option(names = ["--include-profiles"], description = ["Capture profiles in a v2 offline bundle"]) var includeProfiles: Boolean = false
    @Option(names = ["--include-text"], description = ["Include potentially sensitive dictionary text in profile get, profile capture or inspect"])
    var includeText: Boolean = false
    @Option(names = ["--limit"], description = ["inspect search/summary: top 1..1000 requests/services; default 20"]) var limit: Int? = null
    @Option(names = ["--sort"], description = ["inspect summary: errors, elapsed (maximum), requests; default errors"]) var summarySort: String? = null
    @Option(names = ["--min-elapsed-ms"], description = ["inspect search: inclusive minimum request duration"]) var minElapsedMs: Int? = null
    @Option(names = ["--errors-only"], description = ["inspect search: nonzero XLog error hash"]) var errorsOnly: Boolean = false
    @Option(names = ["--service-hash"], description = ["inspect search: exact service hash"]) var serviceHash: Int? = null
    @Option(names = ["--service-name"], description = ["inspect search: exact stored name; requires --include-text"]) var serviceName: String? = null

    override fun call(): Int {
        try {
            val command = words.joinToString(" ")
            if (command in setOf("context add", "context list", "context use")) {
                contextCommand(command)
                return 0
            }
            if (name != null) invalid("--name requires context add/use")
            if (command == "inspect summary") {
                allowedOptions(setOf("--directory", "--limit", "--sort", "--include-text", "--no-input"))
                val summary = summarizeCapture(required(directory, "--directory"),
                    ServiceSummaryOptions(limit ?: 20, ServiceSort.parse(summarySort ?: "errors"), includeText))
                result(json.encodeToJsonElement(summary), summary.complete)
                return if (summary.complete) 0 else 8
            }
            if (summarySort != null) invalid("--sort requires inspect summary")
            if (command !in setOf("doctor", "auth login", "object list", "xlog search", "profile get", "capture", "inspect", "inspect search")) invalid("Unknown command")
            if (command == "auth login") allowedOptions(setOf("--host", "--port", "--user", "--context", "--timeout-ms", "--interactive", "--no-input"))
            if (command != "inspect search" && (limit != null || minElapsedMs != null || errorsOnly || serviceHash != null || serviceName != null)) {
                invalid("Search filters require inspect search")
            }
            if (includeProfiles && command != "capture") invalid("--include-profiles requires capture")
            if (includeText && command !in setOf("profile get", "inspect", "inspect search") && !(command == "capture" && includeProfiles)) {
                invalid("--include-text requires profile get, inspect or capture --include-profiles")
            }
            if (command == "inspect search") {
                if (resume || txid != null) invalid("inspect search does not accept --resume or --txid")
                val search = searchCapture(required(directory, "--directory"),
                    EvidenceFilter(limit ?: 20, minElapsedMs ?: 0, errorsOnly, serviceHash, serviceName), includeText)
                val complete = search.captureExecutionComplete && search.summary.unavailableProfiles == 0 && search.lastFailure == null
                result(json.encodeToJsonElement(search), complete)
                return if (complete) 0 else 8
            }
            if (command == "inspect") {
                if (resume) invalid("--resume requires capture")
                val inspection = inspectCapture(required(directory, "--directory"), required(txid, "--txid"), includeText)
                val complete = inspection.captureExecutionComplete && inspection.unavailableProfiles == 0
                result(json.encodeToJsonElement(inspection), complete)
                return if (complete) 0 else 8
            }
            if (maxPages !in 1..40) invalid("Use --max-pages 1..40")
            val query = if (command in setOf("xlog search", "capture")) query() else null
            if (command == "profile get") {
                if (!required(date, "--date").matches(Regex("[0-9]{8}"))) invalid("Use an 8-digit storage date")
                LocalDate.parse(required(date, "--date"), DateTimeFormatter.BASIC_ISO_DATE)
                required(txid, "--txid")
            }
            if (command == "capture") required(directory, "--directory")
            if (includeProfiles && required(query, "query").pageSize > 100) invalid("Profile capture page-size must be 1..100")
            val endpoint = connection.endpoint()
            val password = connection.password(command == "auth login")
            CollectorSource.connect(endpoint, password).use { source ->
                when (command) {
                    "doctor", "auth login" -> result(json.encodeToJsonElement(source.info))
                    "object list" -> result(json.encodeToJsonElement(source.objects()))
                    "profile get" -> result(json.encodeToJsonElement(source.profile(required(date, "--date"), required(txid, "--txid"), includeText)))
                    "xlog search" -> return stream(source, required(query, "query"))
                    "capture" -> {
                        if (includeProfiles) {
                            val manifest = captureEvidence(source, required(query, "query"),
                                CaptureRequest(required(directory, "--directory"), resume, maxPages, "${endpoint.host}:${endpoint.port}", includeText))
                            result(json.encodeToJsonElement(manifest), manifest.complete)
                            return if (manifest.complete) 0 else 8
                        }
                        val manifest = capture(source, required(query, "query"),
                            CaptureRequest(required(directory, "--directory"), resume, maxPages, "${endpoint.host}:${endpoint.port}"))
                        result(json.encodeToJsonElement(manifest), manifest.executionComplete)
                        return if (manifest.executionComplete) 0 else 8
                    }
                }
            }
            return 0
        } catch (error: CliFailure) {
            return failure(error)
        } catch (_: SocketTimeoutException) {
            return failure(CliFailure(ErrorCode.CONNECTION_FAILED,
                "Collector timeout; check TCP access and --timeout-ms; upstream cancellation is not guaranteed"))
        } catch (_: SocketException) {
            return failure(CliFailure(ErrorCode.CONNECTION_FAILED,
                "Collector connection failed; check --host, --port and TCP access"))
        } catch (_: IOException) {
            return failure(CliFailure(ErrorCode.IO_ERROR, "Transport or artifact I/O failed"))
        } catch (_: IllegalArgumentException) {
            return failure(CliFailure(ErrorCode.INVALID_ARGUMENT, "Invalid argument or manifest"))
        } catch (_: java.time.DateTimeException) {
            return failure(CliFailure(ErrorCode.INVALID_ARGUMENT, "Invalid date or timestamp"))
        } catch (_: Exception) {
            return failure(CliFailure(ErrorCode.PROTOCOL_ERROR, "Response could not be decoded"))
        }
    }

    private fun contextCommand(command: String) {
        val allowed = when (command) {
            "context add" -> setOf("--name", "--host", "--port", "--user", "--no-input")
            "context use" -> setOf("--name", "--no-input")
            else -> setOf("--no-input")
        }
        allowedOptions(allowed)
        val store = ContextStore()
        val config = when (command) {
            "context add" -> store.add(required(name, "--name"), SavedConnection(
                connection.host ?: "127.0.0.1", connection.port ?: 6100, required(connection.user, "--user")))
            "context use" -> store.use(required(name, "--name"))
            else -> store.read()
        }
        result(json.encodeToJsonElement(config))
    }

    private fun allowedOptions(allowed: Set<String>) {
        if (spec.commandLine().parseResult.matchedOptions().any { it.longestName() !in allowed }) {
            invalid("Option is not supported by this command; use --help")
        }
    }

    private fun query(): Query = Query(required(date, "--date"),
        Instant.parse(required(from, "--from")).toEpochMilli(), Instant.parse(required(to, "--to")).toEpochMilli(),
        required(timezone, "--timezone"), required(objHash, "--object-hash"), pageSize ?: if (includeProfiles) 25 else 1000).also { it.validate() }

    private fun stream(source: ObservationSource, query: Query): Int {
        emit(buildJsonObject { put("type", "start"); put("schemaVersion", "scouterctl.stream/v1");
            put("source", json.encodeToJsonElement(source.info)); put("query", json.encodeToJsonElement(query)) })
        var cursor = Cursor()
        repeat(maxPages) {
            val page = source.page(query, cursor)
            page.records.forEach { record ->
                emit(buildJsonObject { put("type", "record"); put("data", json.encodeToJsonElement(record)) })
            }
            cursor = page.cursor
            if (!page.hasMore) {
                emit(buildJsonObject { put("type", "complete"); put("executionComplete", true)
                    put("upstreamCoverage", "unknown"); put("sampling", "unknown") })
                return 0
            }
        }
        throw CliFailure(ErrorCode.PARTIAL_RESULT, "Page budget reached; use capture for resumable extraction")
    }
}

private fun invalid(message: String): Nothing = throw CliFailure(ErrorCode.INVALID_ARGUMENT, message)
private fun <T> required(value: T?, name: String): T = value ?: invalid("Missing $name")
private fun result(data: JsonElement, complete: Boolean = true) {
    emit(buildJsonObject { put("schemaVersion", "scouterctl.result/v1"); put("ok", true)
        put("data", data); put("executionComplete", complete) })
}
private fun emit(value: JsonElement) {
    println(value)
    if (System.out.checkError()) throw CliFailure(ErrorCode.IO_ERROR, "Output stream failed")
}
private fun failure(error: CliFailure): Int {
    println(buildJsonObject { put("schemaVersion", "scouterctl.error/v1"); put("type", "error"); put("ok", false)
        putJsonObject("error") { put("code", error.code.name); put("message", error.message) } })
    return error.code.exit
}
fun main(args: Array<String>) {
    val command = CommandLine(Cli())
    command.setParameterExceptionHandler { _, _ -> failure(CliFailure(ErrorCode.INVALID_ARGUMENT, "Invalid command arguments; use --help")) }
    exitProcess(command.execute(*args))
}
