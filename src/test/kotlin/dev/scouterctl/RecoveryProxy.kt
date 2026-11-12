package dev.scouterctl

import scouter.io.DataInputX
import scouter.io.DataOutputX
import scouter.net.NetCafe
import scouter.net.RequestCmd
import scouter.net.TcpFlag
import java.io.EOFException
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Pauses a real request at a repeatable checkpoint without changing its payload. */
internal class RecoveryProxy(
    private val upstreamPort: Int,
    private val commandToPause: String,
    private val occurrence: Int,
) : AutoCloseable {
    private val listener = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    val port: Int = listener.localPort
    private val entered = CountDownLatch(1)
    private val released = CountDownLatch(1)
    private val executor = Executors.newSingleThreadExecutor()
    @Volatile private var client: Socket? = null
    @Volatile private var upstream: Socket? = null
    @Volatile var transportFailures: Int = 0
        private set
    private var matches = 0
    private val future = executor.submit { serve() }

    fun awaitPaused() {
        check(entered.await(15, TimeUnit.SECONDS)) { "Capture did not reach recovery boundary" }
    }

    fun release() { released.countDown() }

    fun disconnectUpstream() { checkNotNull(upstream).close(); release() }

    private fun serve() {
        while (!listener.isClosed) {
            val accepted = try { listener.accept() } catch (_: SocketException) {
                check(listener.isClosed)
                break
            }
            accepted.use { downstream ->
                client = downstream
                Socket("127.0.0.1", upstreamPort).use { target ->
                    upstream = target
                    downstream.soTimeout = 20000
                    target.soTimeout = 20000
                    relay(downstream, target)
                }
            }
        }
    }

    private fun relay(downstream: Socket, target: Socket) {
        val input = DataInputX(downstream.getInputStream().buffered())
        val output = DataOutputX(downstream.getOutputStream().buffered())
        val targetInput = DataInputX(target.getInputStream().buffered())
        val targetOutput = DataOutputX(target.getOutputStream().buffered())
        check(input.readInt() == NetCafe.TCP_CLIENT)
        targetOutput.writeInt(NetCafe.TCP_CLIENT)
        targetOutput.flush()
        while (!listener.isClosed) {
            val command = try { input.readText() } catch (_: EOFException) { return }
            check(command in setOf(RequestCmd.LOGIN, RequestCmd.TRANX_LOAD_TIME_GROUP_V2,
                RequestCmd.TRANX_PROFILE, RequestCmd.XLOG_READ_BY_TXID, RequestCmd.GET_TEXT_PACK,
                RequestCmd.OBJECT_LIST_LOAD_DATE, RequestCmd.GET_XML_COUNTER, RequestCmd.COUNTER_PAST_TIME,
                RequestCmd.SERVER_ENV, RequestCmd.SERVER_TIME))
            val session = input.readLong()
            val request = if (command in setOf(RequestCmd.GET_XML_COUNTER, RequestCmd.SERVER_ENV,
                    RequestCmd.SERVER_TIME)) null else input.readMapPack()
            if (command == commandToPause && ++matches == occurrence) {
                entered.countDown()
                check(released.await(15, TimeUnit.SECONDS)) { "Recovery boundary was not released" }
            }
            try {
                targetOutput.writeText(command)
                targetOutput.writeLong(session)
                if (request != null) targetOutput.writePack(request)
                targetOutput.flush()
                while (true) {
                    val flag = targetInput.readByte()
                    output.writeByte(flag.toInt())
                    if (flag == TcpFlag.HasNEXT) output.writePack(targetInput.readPack())
                    else {
                        check(flag == TcpFlag.NoNEXT || flag == TcpFlag.INVALID_SESSION)
                        break
                    }
                }
                output.flush()
            } catch (_: IOException) {
                transportFailures++
                return
            }
        }
    }

    override fun close() {
        listener.close()
        release()
        client?.close()
        upstream?.close()
        try { future.get(25, TimeUnit.SECONDS) } finally { executor.shutdownNow() }
    }
}
