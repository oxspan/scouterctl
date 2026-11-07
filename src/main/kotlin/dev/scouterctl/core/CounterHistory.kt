package dev.scouterctl.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class CounterHistoryQuery(
    val date: String, val from: Long, val to: Long, val timezone: String,
    val objHash: Int, val counter: String,
) {
    fun validate() {
        try {
            require(date.matches(Regex("[0-9]{8}")))
            val day = LocalDate.parse(date, DateTimeFormatter.BASIC_ISO_DATE)
            val zone = ZoneId.of(timezone)
            require(from < to)
            require(Instant.ofEpochMilli(from).atZone(zone).toLocalDate() == day)
            require(Instant.ofEpochMilli(to - 1).atZone(zone).toLocalDate() == day)
            require(counter.isNotBlank() && counter.length <= 256 && counter.none(Char::isISOControl))
        } catch (_: IllegalArgumentException) {
            throw OperationFailure(ErrorCode.INVALID_ARGUMENT, "Use one collector-local day, from < to, and an exact counter name")
        } catch (_: java.time.DateTimeException) {
            throw OperationFailure(ErrorCode.INVALID_ARGUMENT, "Invalid date, timezone or time window")
        }
    }
}

data class CounterTarget(val objHash: Int, val objName: String, val objType: String)
data class CounterDefinition(val name: String, val displayName: String?, val unit: String?)
data class CounterPoint(val time: Long, val value: String?, val availability: CounterAvailability)
data class CounterHistory(val target: CounterTarget, val counter: CounterDefinition, val points: List<CounterPoint>)
