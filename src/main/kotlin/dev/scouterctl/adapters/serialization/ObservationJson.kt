package dev.scouterctl.adapters.serialization

import dev.scouterctl.core.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

@Serializable
data class ServerInfoDto(
    val serverId: String, val version: String, val timezoneDisplayName: String,
    val group: String, val source: String = "collector-tcp",
    val effectivePermission: String = "unknown", val nativeTls: Boolean = false,
)

@Serializable
data class ObjectRecordDto(val serverId: String, val objHash: Int, val objName: String, val objType: String, val alive: Boolean)

@Serializable
data class XLogRecordDto(
    val txid: String, val gxid: String, val caller: String, val endTime: Long,
    val objHash: Int, val serviceHash: Int, val elapsed: Int, val errorHash: Int,
)

@Serializable
data class SqlProfileDto(
    val hash: Int, val elapsedMs: Int, val errorHash: Int, val updated: Int? = null,
    val statement: DictionaryTextDto? = null, val error: DictionaryTextDto? = null,
)

@Serializable
data class DictionaryTextDto(val hash: Int, val text: String? = null) {
    var status: String = if (text == null) "unresolved" else "resolved"
        internal set
}

@Serializable
data class ProfileTransactionDto(
    val status: String, val service: DictionaryTextDto? = null, val error: DictionaryTextDto? = null,
)

@Serializable
data class ProfileStepDto(val type: String, val order: Int, val sql: SqlProfileDto? = null)

@Serializable
enum class ProfileCompletenessDto { @SerialName("unknown") UNKNOWN, @SerialName("truncated") TRUNCATED }

@Serializable
data class ProfileRecordDto(
    val txid: String, val steps: List<ProfileStepDto>,
    val detail: String = "structural-only; free text and binds omitted",
    val transaction: ProfileTransactionDto? = null,
    val completeness: ProfileCompletenessDto = ProfileCompletenessDto.UNKNOWN,
)

@Serializable
data class CursorDto(val bucketTime: Long = 0, val txid: String = "0")

@Serializable
data class QueryDto(val date: String, val from: Long, val to: Long, val timezone: String, val objHash: Int, val pageSize: Int)

fun ServerInfo.toDto(): ServerInfoDto = ServerInfoDto(serverId, version, timezoneDisplayName, group, source, effectivePermission, nativeTls)

fun ServerInfoDto.toModel(): ServerInfo = ServerInfo(serverId, version, timezoneDisplayName, group, source, effectivePermission, nativeTls)

fun ObjectRecord.toDto(): ObjectRecordDto = ObjectRecordDto(serverId, objHash, objName, objType, alive)

fun ObjectRecordDto.toModel(): ObjectRecord = ObjectRecord(serverId, objHash, objName, objType, alive)

fun XLogRecord.toDto(): XLogRecordDto = XLogRecordDto(txid, gxid, caller, endTime, objHash, serviceHash, elapsed, errorHash)

fun XLogRecordDto.toModel(): XLogRecord = XLogRecord(txid, gxid, caller, endTime, objHash, serviceHash, elapsed, errorHash)

fun SqlProfile.toDto(): SqlProfileDto = SqlProfileDto(hash, elapsedMs, errorHash, updated, statement?.toDto(), error?.toDto())

fun SqlProfileDto.toModel(): SqlProfile = SqlProfile(hash, elapsedMs, errorHash, updated, statement?.toModel(), error?.toModel())

fun DictionaryText.toDto(): DictionaryTextDto = DictionaryTextDto(hash, text).also { it.status = status }

fun DictionaryTextDto.toModel(): DictionaryText = DictionaryText(hash, text).also { it.status = status }

fun ProfileTransaction.toDto(): ProfileTransactionDto = ProfileTransactionDto(status, service?.toDto(), error?.toDto())

fun ProfileTransactionDto.toModel(): ProfileTransaction = ProfileTransaction(status, service?.toModel(), error?.toModel())

fun ProfileStep.toDto(): ProfileStepDto = ProfileStepDto(type, order, sql?.toDto())

fun ProfileStepDto.toModel(): ProfileStep = ProfileStep(type, order, sql?.toModel())

fun ProfileRecord.toDto(): ProfileRecordDto = ProfileRecordDto(txid, steps.map { it.toDto() }, detail, transaction?.toDto(),
    when (completeness) { ProfileCompleteness.UNKNOWN -> ProfileCompletenessDto.UNKNOWN; ProfileCompleteness.TRUNCATED -> ProfileCompletenessDto.TRUNCATED })

fun ProfileRecordDto.toModel(): ProfileRecord = ProfileRecord(txid, steps.map { it.toModel() }, detail, transaction?.toModel(),
    when (completeness) { ProfileCompletenessDto.UNKNOWN -> ProfileCompleteness.UNKNOWN; ProfileCompletenessDto.TRUNCATED -> ProfileCompleteness.TRUNCATED })

fun Cursor.toDto(): CursorDto = CursorDto(bucketTime, txid)

fun CursorDto.toModel(): Cursor = Cursor(bucketTime, txid)

fun Query.toDto(): QueryDto = QueryDto(date, from, to, timezone, objHash, pageSize)

fun QueryDto.toModel(): Query = Query(date, from, to, timezone, objHash, pageSize)
