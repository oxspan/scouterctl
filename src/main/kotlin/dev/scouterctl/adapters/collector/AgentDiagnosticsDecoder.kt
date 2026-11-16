package dev.scouterctl.adapters.collector

import dev.scouterctl.core.*
import scouter.lang.pack.MapPack
import scouter.lang.pack.Pack
import scouter.lang.value.BooleanValue
import scouter.lang.value.DecimalValue
import scouter.lang.value.ListValue
import scouter.lang.value.NullValue
import scouter.lang.value.TextValue
import scouter.util.Hexa32

internal fun decodeActiveRequests(agent: AgentIdentity, response: List<Pack>, includeText: Boolean): ActiveRequests {
    val pack = diagnosticPack(response, "Active requests")
    val returnedHash = (pack.get("objHash") as? DecimalValue)?.value
        ?: invalidDiagnostics("Missing active-request Agent identity")
    if (returnedHash != agent.objHash.toLong()) invalidDiagnostics("Active-request Agent identity differs")
    if (pack.get("id") == null && pack.size() == 1) unavailableDiagnostics("Active requests")
    fun column(name: String): ListValue = pack.get(name) as? ListValue
        ?: invalidDiagnostics("Invalid active-request columns")
    val ids = column("id")
    val elapsed = column("elapsed")
    val states = column("stat")
    val transactions = column("txid")
    val cpus = column("cpu")
    val services = column("service")
    val names = column("name")
    if (ids.size() > 10000 || listOf(elapsed, states, transactions, cpus, services, names).any { it.size() != ids.size() }) {
        invalidDiagnostics("Inconsistent or excessive active-request rows")
    }
    val complete = (pack.get("complete") as? BooleanValue)?.value
        ?: invalidDiagnostics("Missing active-request completion flag")
    fun number(column: ListValue, index: Int): Long = (column.get(index) as? DecimalValue)?.value
        ?: invalidDiagnostics("Invalid active-request number")
    fun text(column: ListValue, index: Int): String = (column.get(index) as? TextValue)?.value
        ?: invalidDiagnostics("Invalid active-request text")
    fun optionalText(column: ListValue, index: Int): String? = when (val value = column.get(index)) {
        is NullValue -> null
        is TextValue -> value.value
        else -> invalidDiagnostics("Invalid active-request text")
    }
    val seen = mutableSetOf<Long>()
    val requests = (0 until ids.size()).map { index ->
        val id = number(ids, index)
        val duration = number(elapsed, index)
        val cpu = number(cpus, index)
        val state = text(states, index)
        if (id < 0 || duration < 0 || cpu < -1 || state !in threadStates) invalidDiagnostics("Invalid active-request values")
        val rawTxid = text(transactions, index)
        val txid = try { Hexa32.toLong32(rawTxid) } catch (_: NumberFormatException) {
            invalidDiagnostics("Invalid active-request transaction ID")
        }
        if (rawTxid.isEmpty() || Hexa32.toString32(txid) != rawTxid || !seen.add(txid)) {
            invalidDiagnostics("Invalid or duplicate active-request transaction ID")
        }
        val service = optionalText(services, index)
        val name = optionalText(names, index)
        ActiveRequest(txid.toString(), id.toString(), state, duration,
            cpu.takeIf { it >= 0 }?.toString(), service.takeIf { includeText }, name.takeIf { includeText })
    }
    return ActiveRequests(agent, requests.sortedWith(compareByDescending<ActiveRequest> { it.elapsedMs }.thenBy { it.txid }), complete, includeText)
}

internal fun decodeThreadDump(agent: AgentIdentity, response: List<Pack>): AgentThreadDump {
    val pack = diagnosticPack(response, "Thread dump")
    val error = pack.get("error")
    if (error != null) {
        if (error !is TextValue) invalidDiagnostics("Invalid thread-dump error")
        throw OperationFailure(ErrorCode.NOT_FOUND, "Agent could not produce a thread dump; check Agent JVM attachment support and logs")
    }
    val lines = pack.get("threadDump") as? ListValue ?: invalidDiagnostics("Invalid thread-dump response")
    if (lines.size() == 0) unavailableDiagnostics("Thread dump")
    if (lines.size() > 100000) invalidDiagnostics("Thread dump exceeds 100000 lines")
    var characters = 0L
    val decoded = (0 until lines.size()).map { index ->
        val line = (lines.get(index) as? TextValue)?.value ?: invalidDiagnostics("Invalid thread-dump line")
        characters += line.length
        if (characters > 8_388_608) invalidDiagnostics("Thread dump exceeds 8388608 characters")
        line
    }
    return AgentThreadDump(agent, decoded)
}

private val threadStates = setOf("NEW", "RUNNABLE", "BLOCKED", "WAITING", "TIMED_WAITING", "TERMINATED", "n/a")

private fun diagnosticPack(response: List<Pack>, label: String): MapPack {
    if (response.isEmpty()) unavailableDiagnostics(label)
    return response.singleOrNull() as? MapPack ?: invalidDiagnostics("Expected one $label response")
}

private fun unavailableDiagnostics(label: String): Nothing =
    throw OperationFailure(ErrorCode.NOT_FOUND, "$label unavailable; the Agent may not support this diagnostic or may have disconnected")

private fun invalidDiagnostics(message: String): Nothing = throw OperationFailure(ErrorCode.PROTOCOL_ERROR, message)
