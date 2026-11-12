package dev.scouterctl.adapters.collector

import dev.scouterctl.core.*
import dev.scouterctl.application.ObservationSource
import dev.scouterctl.application.collectorSettingValueIsVisible
import dev.scouterctl.application.agentSettingValueIsVisible
import dev.scouterctl.application.agentTextSettingValueIsVisible

import scouter.io.DataInputX
import scouter.io.DataOutputX
import scouter.lang.pack.*
import scouter.lang.TextTypes
import scouter.lang.step.Step
import scouter.lang.step.StepControl
import scouter.lang.value.BooleanValue
import scouter.lang.value.ListValue
import scouter.lang.value.TextValue
import scouter.lang.value.Value
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
    private val reportedTimezone by lazy {
        collectorOperation { decodeCollectorTimezone(exchange(RequestCmd.SERVER_ENV), exchange(RequestCmd.SERVER_TIME)) }
    }

    override fun timezone(): CollectorTimezone? = reportedTimezone

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
        requireSupportedCollector(version)
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

    override fun status(): CollectorStatus = collectorOperation {
        val pack = exchange(RequestCmd.SERVER_STATUS).singleOrNull() as? MapPack
            ?: throw OperationFailure(ErrorCode.PROTOCOL_ERROR, "Expected one Collector status response")
        fun number(key: String): Long = (pack.get(key) as? DecimalValue)?.value
            ?: throw OperationFailure(ErrorCode.PROTOCOL_ERROR, "Invalid Collector status fields")
        val status = CollectorStatus(number("used"), number("total"), number("time"))
        if (status.usedHeapBytes < 0 || status.committedHeapBytes < status.usedHeapBytes || status.serverTimeEpochMs < 0) {
            throw OperationFailure(ErrorCode.PROTOCOL_ERROR, "Invalid Collector status values")
        }
        status
    }

    override fun settings(): List<RuntimeSetting> = collectorOperation {
        parseSettings(exchange(RequestCmd.LIST_CONFIGURE_SERVER), ::collectorSettingValueIsVisible)
    }

    override fun agentStatus(objHash: Int): AgentStatus = collectorOperation {
        val agent = agent(objHash)
        if (!agent.alive) return@collectorOperation AgentStatus(agent, AgentJvmAvailability.OFFLINE, null)
        val response = exchange(RequestCmd.OBJECT_ENV, MapPack().apply { put("objHash", objHash.toLong()) })
        if (response.isEmpty()) return@collectorOperation AgentStatus(agent, AgentJvmAvailability.UNAVAILABLE, null)
        val pack = response.singleOrNull() as? MapPack
            ?: throw OperationFailure(ErrorCode.PROTOCOL_ERROR, "Expected one Agent JVM response")
        fun text(key: String): String? {
            val value = pack.get(key) ?: return null
            return (value as? TextValue)?.value
                ?: throw OperationFailure(ErrorCode.PROTOCOL_ERROR, "Invalid Agent JVM field")
        }
        val javaVersion = text("java.version")
            ?: return@collectorOperation AgentStatus(agent, AgentJvmAvailability.UNAVAILABLE, null)
        if (javaVersion.isBlank()) throw OperationFailure(ErrorCode.PROTOCOL_ERROR, "Invalid Agent Java version")
        AgentStatus(agent, AgentJvmAvailability.AVAILABLE, AgentJvm(javaVersion, text("java.runtime.version"),
            text("java.vm.name"), text("java.vm.version"), text("java.vendor"), text("os.name"), text("os.arch")))
    }

    override fun agentSettings(objHash: Int): AgentConfiguration = collectorOperation {
        val agent = agent(objHash)
        if (!agent.alive) throw OperationFailure(ErrorCode.NOT_FOUND, "Agent is not alive")
        val response = exchange(RequestCmd.LIST_CONFIGURE_WAS, MapPack().apply { put("objHash", objHash.toLong()) })
        if (response.isEmpty()) throw OperationFailure(ErrorCode.NOT_FOUND, "Agent settings are unavailable")
        AgentConfiguration(agent, parseSettings(response, ::agentSettingValueIsVisible, ::agentTextSettingValueIsVisible))
    }

    override fun counterHistory(query: CounterHistoryQuery): CounterHistory = collectorOperation {
        val target = decodeCounterTarget(query.objHash, datedObjects(query.date))
        val metadata = exchange(RequestCmd.GET_XML_COUNTER)
        val response = exchange(RequestCmd.COUNTER_PAST_TIME, MapPack().apply {
            put("objHash", query.objHash.toLong()); put("counter", query.counter)
            put("stime", query.from); put("etime", query.to - 1)
        })
        decodeCounterHistory(query, target, metadata, response)
    }

    override fun realtimeCounters(objHash: Int): RealtimeCounters = collectorOperation {
        val agent = agent(objHash)
        val metadata = exchange(RequestCmd.GET_XML_COUNTER)
        val values = if (agent.alive) exchange(RequestCmd.COUNTER_REAL_TIME_OBJECT_ALL,
            MapPack().apply { put("objHash", objHash.toLong()) }) else emptyList()
        decodeCounters(agent, metadata, values)
    }

    private fun agent(objHash: Int): AgentIdentity {
        val agents = exchange(RequestCmd.OBJECT_LIST_REAL_TIME).map {
            it as? ObjectPack ?: throw OperationFailure(ErrorCode.PROTOCOL_ERROR, "Expected object pack")
        }.filter { it.objHash == objHash }
        if (agents.size > 1) throw OperationFailure(ErrorCode.PROTOCOL_ERROR, "Duplicate Agent identity")
        val agent = agents.singleOrNull() ?: throw OperationFailure(ErrorCode.NOT_FOUND, "Agent was not found")
        return AgentIdentity(agent.objHash, agent.objName ?: "", agent.objType ?: "", agent.alive, agent.version ?: "unknown")
    }

    private fun parseSettings(
        response: List<Pack>,
        valueIsVisible: (String) -> Boolean,
        textValueIsVisible: (String) -> Boolean = { false },
    ): List<RuntimeSetting> {
        val pack = response.singleOrNull() as? MapPack
            ?: throw OperationFailure(ErrorCode.PROTOCOL_ERROR, "Expected one runtime settings response")
        fun items(key: String): ListValue = pack.get(key) as? ListValue
            ?: throw OperationFailure(ErrorCode.PROTOCOL_ERROR, "Invalid runtime settings fields")
        val keys = items("key")
        val values = items("value")
        val defaults = items("default")
        if (keys.size() > 5000 || keys.size() != values.size() || keys.size() != defaults.size()) {
            throw OperationFailure(ErrorCode.PROTOCOL_ERROR, "Inconsistent runtime settings lists")
        }
        val seen = mutableSetOf<String>()
        fun invalidValue(): Nothing = throw OperationFailure(ErrorCode.PROTOCOL_ERROR, "Invalid operational setting value")
        fun visibleValue(key: String, value: Value): String {
            if (textValueIsVisible(key)) return (value as? TextValue)?.value ?: invalidValue()
            return when (value) {
                is DecimalValue -> value.value.toString()
                is BooleanValue -> value.value.toString()
                else -> invalidValue()
            }
        }
        return (0 until keys.size()).map { index ->
            val key = (keys.get(index) as? TextValue)?.value
                ?: throw OperationFailure(ErrorCode.PROTOCOL_ERROR, "Invalid runtime setting key")
            if (key.isEmpty() || key.length > 256 || !seen.add(key)) {
                throw OperationFailure(ErrorCode.PROTOCOL_ERROR, "Invalid or duplicate runtime setting key")
            }
            val visible = valueIsVisible(key)
            RuntimeSetting(key, if (visible) visibleValue(key, values.get(index)) else null,
                if (visible) visibleValue(key, defaults.get(index)) else null, !visible)
        }
    }

    private fun datedObjects(date: String): List<CounterTarget> =
        decodeDatedObjects(exchange(RequestCmd.OBJECT_LIST_LOAD_DATE, MapPack().apply { put("date", date) }))

    override fun objects(date: String?): List<ObjectRecord> = collectorOperation {
        if (date != null) return@collectorOperation datedObjects(date).map {
            ObjectRecord(info.serverId, it.objHash, it.objName, it.objType, alive = false)
        }
        exchange(RequestCmd.OBJECT_LIST_REAL_TIME).map {
            val p = it as? ObjectPack ?: throw OperationFailure(ErrorCode.PROTOCOL_ERROR, "Expected object pack")
            ObjectRecord(info.serverId, p.objHash, p.objName ?: "", p.objType ?: "", p.alive)
        }
    }

    override fun page(query: Query, cursor: Cursor, includeText: Boolean): XLogPage = collectorOperation {
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
        val text = if (includeText) XLogPageText(
            dictionary(query.date, TextTypes.SERVICE, records.map { it.serviceHash }),
            dictionary(query.date, TextTypes.ERROR, records.map { it.errorHash }.filter { it != 0 }),
        ) else null
        XLogPage(records, next, more, text)
    }

    override fun profile(date: String, txid: Long, includeText: Boolean, includeStepDetails: Boolean): ProfileRecord = collectorOperation {
        val request = MapPack().apply { put("date", date); put("txid", txid); put("max", 1000) }
        val packs = exchange(RequestCmd.TRANX_PROFILE, request)
        if (packs.isEmpty()) throw OperationFailure(ErrorCode.NOT_FOUND, "Profile unavailable")
        var truncated = false
        val steps = packs.flatMap {
            val p = it as? XLogProfilePack ?: throw OperationFailure(ErrorCode.PROTOCOL_ERROR, "Expected profile pack")
            (Step.toObjects(p.profile) ?: emptyArray()).map { step ->
                if (step is StepControl && step.code == 0 && step.message == " ** Profile Truncated ** ") truncated = true
                step.toProfileStep(includeStepDetails = includeStepDetails, includeText = includeText)
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
        val targetNotice = if (profile.steps.any { it.details?.target != null })
            " Socket and API call targets are included when recorded and may contain sensitive addresses."
            else ""
        return profile.copy(
            steps = profile.steps.map { step -> step.copy(sql = step.sql?.let {
                it.copy(statement = DictionaryText(it.hash, statements[it.hash]),
                    error = if (it.errorHash == 0) null else DictionaryText(it.errorHash, errors[it.errorHash]))
            }) },
            transaction = if (transaction == null) ProfileTransaction("unavailable") else ProfileTransaction(
                "available", DictionaryText(transaction.service, service?.get(transaction.service)),
                if (transaction.error == 0) null else DictionaryText(transaction.error, errors[transaction.error])),
            detail = "Service, SQL and error dictionary text included.$targetNotice Bind fields and other free text omitted. Dictionary text may contain sensitive values. Error text can include bound values or stored row values.",
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
