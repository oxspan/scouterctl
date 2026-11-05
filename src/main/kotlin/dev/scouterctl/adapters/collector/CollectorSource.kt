package dev.scouterctl.adapters.collector

import dev.scouterctl.core.*
import dev.scouterctl.application.ObservationSource

import scouter.io.DataInputX
import scouter.io.DataOutputX
import scouter.lang.pack.*
import scouter.lang.TextTypes
import scouter.lang.step.Step
import scouter.lang.step.StepControl
import scouter.lang.step.SqlStep
import scouter.lang.step.SqlStep3
import scouter.lang.value.BooleanValue
import scouter.lang.value.DecimalValue
import scouter.net.NetCafe
import scouter.net.RequestCmd
import scouter.net.TcpFlag
import scouter.util.CipherUtil
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.InetSocketAddress
import java.net.Socket

class CollectorSource private constructor(private val socket: Socket, private val clientVersion: String) : ObservationSource {
    private val input = object : DataInputX(BufferedInputStream(socket.getInputStream())) {
        override fun readBlob(): ByteArray {
            val marker = readUnsignedByte()
            val length = when (marker) { 255 -> readUnsignedShort(); 254 -> readInt(); else -> marker }
            if (length !in 0..8_388_608) throw OperationFailure(ErrorCode.PROTOCOL_ERROR, "Blob exceeds 8 MiB limit")
            return read(length)
        }
    }
    private val output = DataOutputX(BufferedOutputStream(socket.getOutputStream()))
    private var session = 0L
    override lateinit var info: ServerInfo
        private set

    companion object {
        fun connect(endpoint: Endpoint, password: String, clientVersion: String): CollectorSource = collectorOperation {
            val socket = Socket()
            try {
                socket.connect(InetSocketAddress(endpoint.host, endpoint.port), endpoint.timeoutMs)
                socket.soTimeout = endpoint.timeoutMs
                val client = CollectorSource(socket, clientVersion)
                client.output.writeInt(NetCafe.TCP_CLIENT)
                client.output.flush()
                client.login(endpoint.user, password)
                return@collectorOperation client
            } catch (error: Exception) {
                socket.close()
                throw error
            }

        }
    }

    private fun login(user: String, password: String) {
        val request = MapPack().apply {
            put("id", user); put("pass", CipherUtil.sha256(password))
            put("version", "scouterctl/$clientVersion"); put("hostname", "scouterctl")
            put("ip", socket.localAddress.hostAddress)
        }
        val reply = exchange(RequestCmd.LOGIN, request).singleOrNull() as? MapPack
            ?: throw OperationFailure(ErrorCode.PROTOCOL_ERROR, "Expected one login response")
        session = reply.getLong("session")
        if (session == 0L) throw OperationFailure(ErrorCode.AUTH_FAILED,
            "Collector authentication failed; check --user and SCOUTERCTL_PASSWORD with the Collector administrator")
        val version = reply.getText("version") ?: "unknown"
        if (version.substringBefore(' ') != "2.21.3") {
            throw OperationFailure(ErrorCode.VERSION_MISMATCH, "This POC requires Collector 2.21.3")
        }
        info = ServerInfo(reply.getText("server_id") ?: "unknown", version,
            reply.getText("timezone") ?: "unknown", reply.getText("type") ?: "unknown")
    }

    // Only typed read builders below may reach this private transport boundary.
    private fun exchange(command: String, request: MapPack? = null): List<Pack> {
        output.writeText(command)
        output.writeLong(session)
        if (request != null) output.writePack(request)
        output.flush()
        val result = mutableListOf<Pack>()
        while (true) {
            when (input.readByte()) {
                TcpFlag.HasNEXT -> {
                    if (result.size >= 10001) throw OperationFailure(ErrorCode.PROTOCOL_ERROR, "Response pack limit exceeded")
                    result.add(input.readPack() ?: throw OperationFailure(ErrorCode.PROTOCOL_ERROR, "Missing pack"))
                }
                TcpFlag.NoNEXT -> return result
                TcpFlag.INVALID_SESSION -> throw OperationFailure(ErrorCode.AUTH_FAILED, "Collector session expired")
                else -> throw OperationFailure(ErrorCode.PROTOCOL_ERROR, "Unexpected response flag")
            }
        }
    }

    override fun objects(): List<ObjectRecord> = collectorOperation {
        exchange(RequestCmd.OBJECT_LIST_REAL_TIME).map {
            val p = it as? ObjectPack ?: throw OperationFailure(ErrorCode.PROTOCOL_ERROR, "Expected object pack")
            ObjectRecord(info.serverId, p.objHash, p.objName ?: "", p.objType ?: "", p.alive)
        }
    }

    override fun page(query: Query, cursor: Cursor): XLogPage = collectorOperation {
        query.validate()
        val request = MapPack().apply {
            put("date", query.date); put("stime", query.from); put("etime", query.to - 1)
            put("pageCount", query.pageSize.toLong()); put("lastBucketTime", cursor.bucketTime)
            put("txid", cursor.txid.toLong()); newList("objHash").add(query.objHash.toLong())
        }
        val packs = exchange(RequestCmd.TRANX_LOAD_TIME_GROUP_V2, request)
        val meta = packs.lastOrNull() as? MapPack
            ?: throw OperationFailure(ErrorCode.PROTOCOL_ERROR, "XLog page metadata missing")
        if (meta.get("hasMore") !is BooleanValue || meta.get("lastXLogTime") !is DecimalValue ||
            meta.get("lastTxid") !is DecimalValue) {
            throw OperationFailure(ErrorCode.PROTOCOL_ERROR, "Invalid XLog page metadata")
        }
        val records = packs.dropLast(1).map {
            val p = it as? XLogPack ?: throw OperationFailure(ErrorCode.PROTOCOL_ERROR, "Expected XLog pack")
            if (p.objHash != query.objHash || p.endTime !in query.from until query.to) {
                throw OperationFailure(ErrorCode.PROTOCOL_ERROR, "XLog outside requested scope")
            }
            XLogRecord(p.txid.toString(), p.gxid.toString(), p.caller.toString(), p.endTime,
                p.objHash, p.service, p.elapsed, p.error)
        }
        if (records.size > query.pageSize) throw OperationFailure(ErrorCode.PROTOCOL_ERROR, "Page exceeds requested size")
        val next = Cursor(meta.getLong("lastXLogTime"), meta.getLong("lastTxid").toString())
        val more = meta.getBoolean("hasMore")
        if (more && (records.isEmpty() || next.bucketTime == 0L || next.txid == "0" || next == cursor)) {
            throw OperationFailure(ErrorCode.PROTOCOL_ERROR, "Non-advancing XLog cursor")
        }
        XLogPage(records, next, more)
    }

    override fun profile(date: String, txid: Long, includeText: Boolean): ProfileRecord = collectorOperation {
        val request = MapPack().apply { put("date", date); put("txid", txid); put("max", 1000) }
        val packs = exchange(RequestCmd.TRANX_PROFILE, request)
        if (packs.isEmpty()) throw OperationFailure(ErrorCode.NOT_FOUND, "Profile unavailable")
        var truncated = false
        val steps = packs.flatMap {
            val p = it as? XLogProfilePack ?: throw OperationFailure(ErrorCode.PROTOCOL_ERROR, "Expected profile pack")
            (Step.toObjects(p.profile) ?: emptyArray()).map { step ->
                if (step is StepControl && step.code == 0 && step.message == " ** Profile Truncated ** ") truncated = true
                val sql = if (step is SqlStep) SqlProfile(step.hash, step.elapsed, step.error,
                    (step as? SqlStep3)?.updated) else null
                ProfileStep(step.stepTypeName, step.order, sql)
            }
        }
        val profile = ProfileRecord(txid.toString(), steps, completeness =
            if (truncated) ProfileCompleteness.TRUNCATED else ProfileCompleteness.UNKNOWN)
        if (includeText) resolveText(date, profile) else profile
    }

    private fun resolveText(date: String, profile: ProfileRecord): ProfileRecord {
        val packs = exchange(RequestCmd.XLOG_READ_BY_TXID, MapPack().apply {
            put("date", date); put("txid", profile.txid.toLong())
        })
        val transaction = if (packs.isEmpty()) null else {
            val p = packs.singleOrNull() as? XLogPack
                ?: throw OperationFailure(ErrorCode.PROTOCOL_ERROR, "Expected one transaction pack")
            if (p.txid.toString() != profile.txid) {
                throw OperationFailure(ErrorCode.PROTOCOL_ERROR, "Transaction identity differs from requested profile")
            }
            p
        }
        val sql = profile.steps.mapNotNull { it.sql }
        val statements = dictionary(date, TextTypes.SQL, sql.map { it.hash })
        val errors = dictionary(date, TextTypes.ERROR,
            (sql.map { it.errorHash } + listOfNotNull(transaction?.error)).filter { it != 0 })
        val service = transaction?.let { dictionary(date, TextTypes.SERVICE, listOf(it.service)) }
        return profile.copy(
            steps = profile.steps.map { step -> step.copy(sql = step.sql?.let {
                it.copy(statement = DictionaryText(it.hash, statements[it.hash]),
                    error = if (it.errorHash == 0) null else DictionaryText(it.errorHash, errors[it.errorHash]))
            }) },
            transaction = if (transaction == null) ProfileTransaction("unavailable") else ProfileTransaction(
                "available", DictionaryText(transaction.service, service?.get(transaction.service)),
                if (transaction.error == 0) null else DictionaryText(transaction.error, errors[transaction.error])),
            detail = "service, SQL and error dictionary text included; bind fields and other free text omitted; dictionary text may contain sensitive values",
        )
    }

    private fun dictionary(date: String, type: String, hashes: List<Int>): Map<Int, String> {
        val result = mutableMapOf<Int, String>()
        hashes.distinct().chunked(100).forEach { batch ->
            val packs = exchange(RequestCmd.GET_TEXT_PACK, MapPack().apply {
                put("date", date); put("type", type)
                val requested = newList("hash")
                batch.forEach { requested.add(it.toLong()) }
            })
            packs.forEach {
                val p = it as? TextPack ?: throw OperationFailure(ErrorCode.PROTOCOL_ERROR, "Expected dictionary text pack")
                if (p.xtype != type || p.hash !in batch || p.text == null || result.containsKey(p.hash)) {
                    throw OperationFailure(ErrorCode.PROTOCOL_ERROR, "Invalid dictionary response")
                }
                result[p.hash] = p.text
            }
        }
        return result
    }

    override fun close() = collectorOperation {
        socket.close()
    }
}
