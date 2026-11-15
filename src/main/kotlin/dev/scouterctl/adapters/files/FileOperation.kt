package dev.scouterctl.adapters.files

import dev.scouterctl.core.*

import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileSystemException
import java.nio.file.NoSuchFileException

internal inline fun <T> fileOperation(block: () -> T): T = try {
    block()
} catch (error: OperationFailure) {
    throw error
} catch (error: IOException) {
    val category = when (error) {
        is AccessDeniedException -> "AccessDeniedException"
        is NoSuchFileException -> "NoSuchFileException"
        is FileAlreadyExistsException -> "FileAlreadyExistsException"
        is AtomicMoveNotSupportedException -> "AtomicMoveNotSupportedException"
        is FileSystemException -> "FileSystemException"
        else -> "IOException"
    }
    throw OperationFailure(ErrorCode.IO_ERROR, "Local file I/O failed ($category); check the path, permissions and available disk space")
} catch (_: IllegalArgumentException) {
    throw UnexpectedFailure(ErrorCode.INVALID_ARGUMENT, "Invalid argument or manifest")
} catch (_: java.time.DateTimeException) {
    throw UnexpectedFailure(ErrorCode.INVALID_ARGUMENT, "Invalid date or timestamp")
} catch (_: Exception) {
    throw UnexpectedFailure(ErrorCode.PROTOCOL_ERROR, "Response could not be decoded")
}
