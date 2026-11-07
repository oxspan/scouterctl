package dev.scouterctl

import dev.scouterctl.core.Endpoint
import scouter.io.DataInputX
import scouter.io.DataOutputX
import scouter.lang.pack.MapPack
import scouter.lang.value.*
import scouter.net.NetCafe
import scouter.net.RequestCmd
import scouter.net.TcpFlag
import scouter.util.CipherUtil
import java.net.Socket

internal data class StoredCounterSample(val time: Long, val value: String)

/** Direct wire oracle: no production CollectorSource, history decoder, planner or capture reader. */
internal fun counterHistoryReference(endpoint: Endpoint, password: String, objHash: Int,
    counter: String, from: Long, to: Long): List<StoredCounterSample> = Socket(endpoint.host, endpoint.port).use { socket ->
    socket.soTimeout = 5000
    val input = DataInputX(socket.getInputStream().buffered())
    val output = DataOutputX(socket.getOutputStream().buffered())
    output.writeInt(NetCafe.TCP_CLIENT)
    fun call(command: String, session: Long, request: MapPack): MapPack {
        output.writeText(command)
        output.writeLong(session)
        output.writePack(request)
        output.flush()
        check(input.readByte() == TcpFlag.HasNEXT) { "Reference response missing" }
        val reply = input.readPack() as MapPack
        check(input.readByte() == TcpFlag.NoNEXT) { "Reference response not complete" }
        return reply
    }
    val login = call(RequestCmd.LOGIN, 0, MapPack().apply {
        put("id", endpoint.user); put("pass", CipherUtil.sha256(password))
        put("version", "counter-reference"); put("hostname", "counter-reference"); put("ip", "127.0.0.1")
    })
    val session = login.getLong("session")
    check(session != 0L) { "Reference authentication failed" }
    val reply = call(RequestCmd.COUNTER_PAST_TIME, session, MapPack().apply {
        put("objHash", objHash.toLong()); put("counter", counter); put("stime", from); put("etime", to - 1)
    })
    val times = reply.getList("time")
    val values = reply.getList("value")
    check(times.size() == values.size()) { "Reference lists differ" }
    (0 until times.size()).map { index ->
        val time = (times.get(index) as DecimalValue).value
        val value = when (val raw = values.get(index)) {
            is DecimalValue -> raw.value.toString()
            is FloatValue -> { check(raw.value.isFinite()); raw.value.toString() }
            is DoubleValue -> { check(raw.value.isFinite()); raw.value.toString() }
            else -> error("Non-numeric reference sample")
        }
        StoredCounterSample(time, value)
    }
}
