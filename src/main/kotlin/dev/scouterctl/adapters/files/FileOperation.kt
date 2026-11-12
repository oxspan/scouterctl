package dev.scouterctl.adapters.files

import dev.scouterctl.core.*

import java.io.IOException

internal inline fun <T> fileOperation(block: () -> T): T = try {
    block()
} catch (error: OperationFailure) {
    throw error
} catch (_: IOException) {
    throw OperationFailure(ErrorCode.IO_ERROR, "Local file I/O failed; check the path, permissions and available disk space")
} catch (_: IllegalArgumentException) {
    throw UnexpectedFailure(ErrorCode.INVALID_ARGUMENT, "Invalid argument or manifest")
} catch (_: java.time.DateTimeException) {
    throw UnexpectedFailure(ErrorCode.INVALID_ARGUMENT, "Invalid date or timestamp")
} catch (_: Exception) {
    throw UnexpectedFailure(ErrorCode.PROTOCOL_ERROR, "Response could not be decoded")
}
