package dev.scouterctl.cli

import dev.scouterctl.core.*
import dev.scouterctl.application.*

import dev.scouterctl.adapters.serialization.*
import kotlinx.serialization.json.*
import java.io.IOException
import java.io.PrintStream
import java.net.SocketException
import java.net.SocketTimeoutException

class JsonOutput(private val stream: PrintStream) {
    fun run(operation: () -> Int): Int {
        try {
            return operation()
        } catch (error: OperationFailure) {
            return failure(error)
        } catch (_: SocketTimeoutException) {
            return failure(OperationFailure(ErrorCode.CONNECTION_FAILED,
                "Collector timeout; check TCP access and --timeout-ms; upstream cancellation is not guaranteed"))
        } catch (_: SocketException) {
            return failure(OperationFailure(ErrorCode.CONNECTION_FAILED,
                "Collector connection failed; check --host, --port and TCP access"))
        } catch (_: IOException) {
            return failure(OperationFailure(ErrorCode.IO_ERROR, "Transport or artifact I/O failed"))
        } catch (_: IllegalArgumentException) {
            return failure(OperationFailure(ErrorCode.INVALID_ARGUMENT, "Invalid argument or manifest"))
        } catch (_: java.time.DateTimeException) {
            return failure(OperationFailure(ErrorCode.INVALID_ARGUMENT, "Invalid date or timestamp"))
        } catch (_: Exception) {
            return failure(OperationFailure(ErrorCode.PROTOCOL_ERROR, "Response could not be decoded"))
        }
    }
    fun result(data: JsonElement, complete: Boolean = true): Int {
        emit(buildJsonObject {
            put("schemaVersion", "scouterctl.result/v1")
            put("ok", true)
            put("data", data)
            put("executionComplete", complete)
        })
        return if (complete) 0 else 8
    }

    fun event(event: XLogEvent) = when (event) {
        is XLogEvent.Start -> emit(buildJsonObject {
            put("type", "start")
            put("schemaVersion", "scouterctl.stream/v1")
            put("source", json.encodeToJsonElement(event.source.toDto()))
            put("query", json.encodeToJsonElement(event.query.toDto()))
        })
        is XLogEvent.Record -> emit(buildJsonObject {
            put("type", "record")
            put("data", json.encodeToJsonElement(event.value.toDto()))
        })
        XLogEvent.Complete -> emit(buildJsonObject {
            put("type", "complete")
            put("executionComplete", true)
            put("upstreamCoverage", "unknown")
            put("sampling", "unknown")
        })
    }

    private fun emit(value: JsonElement) {
        stream.println(value)
        if (stream.checkError()) throw OperationFailure(ErrorCode.IO_ERROR, "Output stream failed")
    }

    fun failure(error: OperationFailure): Int {
        stream.println(buildJsonObject {
            put("schemaVersion", "scouterctl.error/v1")
            put("type", "error")
            put("ok", false)
            putJsonObject("error") {
                put("code", error.code.name)
                put("message", error.message)
            }
        })
        return error.code.exitCode()
    }
}

internal fun ErrorCode.exitCode(): Int = when (this) {
    ErrorCode.INVALID_ARGUMENT -> 2
    ErrorCode.AUTH_FAILED -> 3
    ErrorCode.CONNECTION_FAILED -> 5
    ErrorCode.PROTOCOL_ERROR -> 6
    ErrorCode.NOT_FOUND -> 7
    ErrorCode.PARTIAL_RESULT -> 8
    ErrorCode.VERSION_MISMATCH -> 9
    ErrorCode.IO_ERROR -> 10
}
