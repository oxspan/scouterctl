package dev.scouterctl

import kotlinx.serialization.json.*

// Project only known fields and reasons; raw output, arguments and exception messages can contain secrets.
internal fun connectionDiagnostic(exit: Int?, elapsedMs: Long, output: String, stderr: String,
                                  fixture: JsonObject? = null): JsonObject {
    val error = output.lineSequence().filter { it.length <= 65_536 }.mapNotNull { line ->
        try { json.parseToJsonElement(line) as? JsonObject } catch (_: IllegalArgumentException) { null }
    }.lastOrNull { (it["type"] as? JsonPrimitive)?.contentOrNull == "error" }
        ?.get("error") as? JsonObject
    val code = (error?.get("code") as? JsonPrimitive)?.contentOrNull
        ?.takeIf { candidate -> ErrorCode.entries.any { it.name == candidate } }
    val reason = when ((error?.get("message") as? JsonPrimitive)?.contentOrNull) {
        "Collector timeout; check TCP access and --timeout-ms; upstream cancellation is not guaranteed" -> "socket_timeout"
        "Collector connection failed; check --host, --port and TCP access" -> "socket_failure"
        "Transport or artifact I/O failed" -> "transport_or_artifact_io"
        else -> "unclassified"
    }
    return buildJsonObject {
        put("process", if (exit == null) "deadline_exceeded" else "exited")
        put("exit", exit?.let(::JsonPrimitive) ?: JsonNull)
        put("elapsedMs", elapsedMs)
        put("stdoutBytes", output.toByteArray(Charsets.UTF_8).size)
        put("stderrBytes", stderr.toByteArray(Charsets.UTF_8).size)
        put("error", buildJsonObject {
            put("code", code?.let(::JsonPrimitive) ?: JsonNull)
            put("reason", reason)
        })
        put("fixture", fixture ?: JsonNull)
    }
}
