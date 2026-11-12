package dev.scouterctl.application

import dev.scouterctl.core.*
import java.time.DateTimeException
import java.time.Instant
import java.time.ZoneId

fun describeCollectorTimezone(id: String, referenceTimeEpochMs: Long): CollectorTimezone? {
    val zone = try { ZoneId.of(id, ZoneId.SHORT_IDS) } catch (_: DateTimeException) { return null }
    val offset = zone.rules.getOffset(Instant.ofEpochMilli(referenceTimeEpochMs))
    return CollectorTimezone(zone.id, offset.totalSeconds, referenceTimeEpochMs)
}

fun validateCollectorTimezone(declared: String, from: Long, to: Long, collector: CollectorTimezone?) {
    if (collector == null) return
    val requested = ZoneId.of(declared).rules
    val reported = ZoneId.of(collector.id).rules
    val start = Instant.ofEpochMilli(from)
    val end = Instant.ofEpochMilli(to)
    fun check(instant: Instant) {
        if (requested.getOffset(instant) != reported.getOffset(instant)) {
            throw OperationFailure(ErrorCode.INVALID_ARGUMENT,
                "--timezone $declared differs from reported Collector time zone ${collector.id} in the requested interval")
        }
    }
    check(start)
    for (rules in listOf(requested, reported)) {
        var transition = rules.nextTransition(start)
        while (transition != null && transition.instant < end) {
            check(transition.instant)
            transition = rules.nextTransition(transition.instant)
        }
    }
}
