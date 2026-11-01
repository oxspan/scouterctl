package dev.scouterctl

import scouter.io.DataInputX
import scouter.io.DataOutputX
import scouter.lang.pack.*
import scouter.lang.step.Step
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

class CollectorSource private constructor(private val socket: Socket) : ObservationSource {
    private val input = object : DataInputX(BufferedInputStream(socket.getInputStream())) {
        override fun readBlob(): ByteArray {
            val marker = readUnsignedByte()
            val length = when (marker) { 255 -> readUnsignedShort(); 254 -> readInt(); else -> marker }
            if (length !in 0..8_388_608) throw CliFailure(ErrorCode.PROTOCOL_ERROR, "Blob exceeds 8 MiB limit")
            return read(length)
        }
    }
    private val output = DataOutputX(BufferedOutputStream(socket.getOutputStream()))
    private var session = 0L
    override lateinit var info: ServerInfo
        private set

    companion object {
        fun connect(endpoint: Endpoint, password: String): CollectorSource {
            val socket = Socket()
            try {
                socket.connect(InetSocketAddress(endpoint.host, endpoint.port), endpoint.timeoutMs)
                socket.soTimeout = endpoint.timeoutMs
                val client = CollectorSource(socket)
                client.output.writeInt(NetCafe.TCP_CLIENT)
                client.output.flush()
                client.login(endpoint.user, password)
                return client
            } catch (error: Exception) {
                socket.close()
                throw error
            }
        }
    }

    private fun login(user: String, password: String) {
        val request = MapPack().apply {
            put("id", user); put("pass", CipherUtil.sha256(password))
            put("version", "scouterctl/${BuildInfo.version}"); put("hostname", "scouterctl")
            put("ip", socket.localAddress.hostAddress)
        }
        val reply = exchange(RequestCmd.LOGIN, request).singleOrNull() as? MapPack
            ?: throw CliFailure(ErrorCode.PROTOCOL_ERROR, "Expected one login response")
        session = reply.getLong("session")
        if (session == 0L) throw CliFailure(ErrorCode.AUTH_FAILED, "Collector authentication failed")
        val version = reply.getText("version") ?: "unknown"
        if (version.substringBefore(' ') != "2.21.3") {
            throw CliFailure(ErrorCode.VERSION_MISMATCH, "This POC requires Collector 2.21.3")
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
                    if (result.size >= 10001) throw CliFailure(ErrorCode.PROTOCOL_ERROR, "Response pack limit exceeded")
                    result.add(input.readPack() ?: throw CliFailure(ErrorCode.PROTOCOL_ERROR, "Missing pack"))
                }
                TcpFlag.NoNEXT -> return result
                TcpFlag.INVALID_SESSION -> throw CliFailure(ErrorCode.AUTH_FAILED, "Collector session expired")
                else -> throw CliFailure(ErrorCode.PROTOCOL_ERROR, "Unexpected response flag")
            }
        }
    }

    override fun objects(): List<ObjectRecord> = exchange(RequestCmd.OBJECT_LIST_REAL_TIME).map {
        val p = it as? ObjectPack ?: throw CliFailure(ErrorCode.PROTOCOL_ERROR, "Expected object pack")
        ObjectRecord(info.serverId, p.objHash, p.objName ?: "", p.objType ?: "", p.alive)
    }

    override fun page(query: Query, cursor: Cursor): XLogPage {
        query.validate()
        val request = MapPack().apply {
            put("date", query.date); put("stime", query.from); put("etime", query.to - 1)
            put("pageCount", query.pageSize.toLong()); put("lastBucketTime", cursor.bucketTime)
            put("txid", cursor.txid.toLong()); newList("objHash").add(query.objHash.toLong())
        }
        val packs = exchange(RequestCmd.TRANX_LOAD_TIME_GROUP_V2, request)
        val meta = packs.lastOrNull() as? MapPack
            ?: throw CliFailure(ErrorCode.PROTOCOL_ERROR, "XLog page metadata missing")
        if (meta.get("hasMore") !is BooleanValue || meta.get("lastXLogTime") !is DecimalValue ||
            meta.get("lastTxid") !is DecimalValue) {
            throw CliFailure(ErrorCode.PROTOCOL_ERROR, "Invalid XLog page metadata")
        }
        val records = packs.dropLast(1).map {
            val p = it as? XLogPack ?: throw CliFailure(ErrorCode.PROTOCOL_ERROR, "Expected XLog pack")
            if (p.objHash != query.objHash || p.endTime !in query.from until query.to) {
                throw CliFailure(ErrorCode.PROTOCOL_ERROR, "XLog outside requested scope")
            }
            XLogRecord(p.txid.toString(), p.gxid.toString(), p.caller.toString(), p.endTime,
                p.objHash, p.service, p.elapsed, p.error)
        }
        if (records.size > query.pageSize) throw CliFailure(ErrorCode.PROTOCOL_ERROR, "Page exceeds requested size")
        val next = Cursor(meta.getLong("lastXLogTime"), meta.getLong("lastTxid").toString())
        val more = meta.getBoolean("hasMore")
        if (more && (records.isEmpty() || next.bucketTime == 0L || next.txid == "0" || next == cursor)) {
            throw CliFailure(ErrorCode.PROTOCOL_ERROR, "Non-advancing XLog cursor")
        }
        return XLogPage(records, next, more)
    }

    override fun profile(date: String, txid: Long): ProfileRecord {
        val request = MapPack().apply { put("date", date); put("txid", txid); put("max", 1000) }
        val packs = exchange(RequestCmd.TRANX_PROFILE, request)
        if (packs.isEmpty()) throw CliFailure(ErrorCode.NOT_FOUND, "Profile unavailable")
        val steps = packs.flatMap {
            val p = it as? XLogProfilePack ?: throw CliFailure(ErrorCode.PROTOCOL_ERROR, "Expected profile pack")
            (Step.toObjects(p.profile) ?: emptyArray()).map { step ->
                val sql = if (step is SqlStep) SqlProfile(step.hash, step.elapsed, step.error,
                    (step as? SqlStep3)?.updated) else null
                ProfileStep(step.stepTypeName, step.order, sql)
            }
        }
        return ProfileRecord(txid.toString(), steps)
    }

    override fun close() { socket.close() }
}

data class Endpoint(val host: String, val port: Int, val user: String, val timeoutMs: Int)
