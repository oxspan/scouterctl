package dev.scouterctl.adapters.collector

import dev.scouterctl.core.*

import java.io.IOException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

internal inline fun <T> collectorOperation(block: () -> T): T = try {
    block()
} catch (error: OperationFailure) {
    throw error
} catch (_: UnknownHostException) {
    throw OperationFailure(ErrorCode.CONNECTION_FAILED, "Collector host could not be resolved; check --host and DNS")
} catch (_: SocketTimeoutException) {
    throw OperationFailure(ErrorCode.CONNECTION_FAILED,
        "Collector timeout; check TCP access and --timeout-ms; upstream cancellation is not guaranteed")
} catch (_: SocketException) {
    throw OperationFailure(ErrorCode.CONNECTION_FAILED,
        "Collector connection failed; check --host, --port and TCP access")
} catch (_: IOException) {
    throw OperationFailure(ErrorCode.IO_ERROR, "Transport or artifact I/O failed")
} catch (_: IllegalArgumentException) {
    throw UnexpectedFailure(ErrorCode.INVALID_ARGUMENT, "Invalid argument or manifest")
} catch (_: java.time.DateTimeException) {
    throw UnexpectedFailure(ErrorCode.INVALID_ARGUMENT, "Invalid date or timestamp")
} catch (_: Exception) {
    throw UnexpectedFailure(ErrorCode.PROTOCOL_ERROR, "Response could not be decoded")
}
