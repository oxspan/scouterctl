package dev.scouterctl

import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

enum class ErrorCode(val exit: Int) {
    INVALID_ARGUMENT(2), AUTH_FAILED(3), CONNECTION_FAILED(5), PROTOCOL_ERROR(6),
    NOT_FOUND(7), PARTIAL_RESULT(8), VERSION_MISMATCH(9), IO_ERROR(10)
}
class CliFailure(val code: ErrorCode, message: String) : RuntimeException(message)

@Serializable
data class ServerInfo(
    val serverId: String, val version: String, val timezoneDisplayName: String,
    val group: String, val source: String = "collector-tcp",
    val effectivePermission: String = "unknown", val nativeTls: Boolean = false,
)
@Serializable
data class ObjectRecord(val serverId: String, val objHash: Int, val objName: String, val objType: String, val alive: Boolean)
@Serializable
data class XLogRecord(
    val txid: String, val gxid: String, val caller: String, val endTime: Long,
    val objHash: Int, val serviceHash: Int, val elapsed: Int, val errorHash: Int,
)
@Serializable
data class SqlProfile(
    val hash: Int, val elapsedMs: Int, val errorHash: Int, val updated: Int? = null,
    val statement: DictionaryText? = null, val error: DictionaryText? = null,
)
@Serializable
data class DictionaryText(val hash: Int, val text: String? = null) {
    val status: String = if (text == null) "unresolved" else "resolved"
}
@Serializable
data class ProfileTransaction(
    val status: String, val service: DictionaryText? = null, val error: DictionaryText? = null,
)
@Serializable
data class ProfileStep(val type: String, val order: Int, val sql: SqlProfile? = null)
@Serializable
data class ProfileRecord(
    val txid: String, val steps: List<ProfileStep>,
    val detail: String = "structural-only; free text and binds omitted",
    val transaction: ProfileTransaction? = null,
)
@Serializable
data class Cursor(val bucketTime: Long = 0, val txid: String = "0")
@Serializable
data class Query(val date: String, val from: Long, val to: Long, val timezone: String, val objHash: Int, val pageSize: Int) {
    fun validate() {
        try {
            require(date.matches(Regex("[0-9]{8}")))
            val day = LocalDate.parse(date, DateTimeFormatter.BASIC_ISO_DATE)
            val zone = ZoneId.of(timezone)
            require(from < to && pageSize in 1..5000)
            require(Instant.ofEpochMilli(from).atZone(zone).toLocalDate() == day)
            require(Instant.ofEpochMilli(to - 1).atZone(zone).toLocalDate() == day)
        } catch (_: IllegalArgumentException) {
            throw CliFailure(ErrorCode.INVALID_ARGUMENT, "Use one collector-local day, from < to, and page-size 1..5000")
        } catch (_: java.time.DateTimeException) {
            throw CliFailure(ErrorCode.INVALID_ARGUMENT, "Invalid date, timezone or time window")
        }
    }
}
data class XLogPage(val records: List<XLogRecord>, val cursor: Cursor, val hasMore: Boolean)

interface ObservationSource : AutoCloseable {
    val info: ServerInfo
    fun objects(): List<ObjectRecord>
    fun page(query: Query, cursor: Cursor): XLogPage
    fun profile(date: String, txid: Long, includeText: Boolean = false): ProfileRecord
}
