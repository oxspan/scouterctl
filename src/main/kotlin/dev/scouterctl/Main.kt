package dev.scouterctl

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
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
    description = ["Read-only Collector 2.21.3 POC. Commands: doctor, object list, xlog search, profile get, capture, inspect, inspect search.",
        "Password: SCOUTERCTL_PASSWORD environment variable. TCP has no native TLS; use a trusted path."])
class Cli : Callable<Int> {
    @Parameters(arity = "1..2", paramLabel = "COMMAND") lateinit var words: List<String>
    @Option(names = ["--host"], defaultValue = "127.0.0.1") lateinit var host: String
    @Option(names = ["--port"], defaultValue = "6100") var port: Int = 6100
    @Option(names = ["--user"], description = ["Collector account; required except for offline inspect"]) var user: String? = null
    @Option(names = ["--timeout-ms"], defaultValue = "5000") var timeoutMs: Int = 5000
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
    @Option(names = ["--limit"], description = ["inspect search: top 1..1000 requests by elapsed time; default 20"]) var limit: Int? = null
    @Option(names = ["--min-elapsed-ms"], description = ["inspect search: inclusive minimum request duration"]) var minElapsedMs: Int? = null
    @Option(names = ["--errors-only"], description = ["inspect search: nonzero XLog error hash"]) var errorsOnly: Boolean = false
    @Option(names = ["--service-hash"], description = ["inspect search: exact service hash"]) var serviceHash: Int? = null
    @Option(names = ["--service-name"], description = ["inspect search: exact stored name; requires --include-text"]) var serviceName: String? = null

    override fun call(): Int {
        try {
            val command = words.joinToString(" ")
            if (command !in setOf("doctor", "object list", "xlog search", "profile get", "capture", "inspect", "inspect search")) invalid("Unknown command")
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
            if (port !in 1..65535 || timeoutMs !in 1..60000 || maxPages !in 1..40) invalid("Invalid port, timeout or max-pages (1..40)")
            val query = if (command in setOf("xlog search", "capture")) query() else null
            if (command == "profile get") {
                if (!required(date, "--date").matches(Regex("[0-9]{8}"))) invalid("Use an 8-digit storage date")
                LocalDate.parse(required(date, "--date"), DateTimeFormatter.BASIC_ISO_DATE)
                required(txid, "--txid")
            }
            if (command == "capture") required(directory, "--directory")
            if (includeProfiles && required(query, "query").pageSize > 100) invalid("Profile capture page-size must be 1..100")
            val password = System.getenv("SCOUTERCTL_PASSWORD") ?: invalid("Set SCOUTERCTL_PASSWORD")
            CollectorSource.connect(Endpoint(host, port, required(user, "--user"), timeoutMs), password).use { source ->
                when (command) {
                    "doctor" -> result(json.encodeToJsonElement(source.info))
                    "object list" -> result(json.encodeToJsonElement(source.objects()))
                    "profile get" -> result(json.encodeToJsonElement(source.profile(required(date, "--date"), required(txid, "--txid"), includeText)))
                    "xlog search" -> return stream(source, required(query, "query"))
                    "capture" -> {
                        if (includeProfiles) {
                            val manifest = captureEvidence(source, required(query, "query"),
                                CaptureRequest(required(directory, "--directory"), resume, maxPages, "$host:$port", includeText))
                            result(json.encodeToJsonElement(manifest), manifest.complete)
                            return if (manifest.complete) 0 else 8
                        }
                        val manifest = capture(source, required(query, "query"),
                            CaptureRequest(required(directory, "--directory"), resume, maxPages, "$host:$port"))
                        result(json.encodeToJsonElement(manifest), manifest.executionComplete)
                        return if (manifest.executionComplete) 0 else 8
                    }
                }
            }
            return 0
        } catch (error: CliFailure) {
            return failure(error)
        } catch (_: SocketTimeoutException) {
            return failure(CliFailure(ErrorCode.CONNECTION_FAILED, "Collector timeout; upstream cancellation is not guaranteed"))
        } catch (_: SocketException) {
            return failure(CliFailure(ErrorCode.CONNECTION_FAILED, "Collector connection failed"))
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
