package dev.scouterctl.adapters.files

import dev.scouterctl.core.ErrorCode
import dev.scouterctl.core.OperationFailure
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.NotDirectoryException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

private const val MISSING_PARENT = "Capture parent directory does not exist. Create it before starting a capture."

internal fun createCaptureDirectory(directory: Path): Unit = fileOperation {
    directory.toAbsolutePath().parent?.let { parent ->
        requireDirectory(parent, MISSING_PARENT, "Capture parent path is not a directory. Choose an existing parent directory.")
    }

    try {
        Files.createDirectory(directory)
    } catch (_: FileAlreadyExistsException) {
        throw OperationFailure(
            ErrorCode.INVALID_ARGUMENT,
            "Capture destination already exists. Choose an unused path, or use --resume for a compatible capture."
        )
    } catch (_: NoSuchFileException) {
        throw OperationFailure(ErrorCode.INVALID_ARGUMENT, MISSING_PARENT)
    }
}

internal fun requireCaptureDirectory(directory: Path) = requireDirectory(
    directory,
    "Capture directory does not exist. Specify the path to an existing capture.",
    "Capture path is not a directory. Specify the path to an existing capture."
)

private fun requireDirectory(path: Path, missing: String, invalid: String) {
    val attributes = try {
        Files.readAttributes(path, BasicFileAttributes::class.java)
    } catch (_: NoSuchFileException) {
        throw OperationFailure(ErrorCode.INVALID_ARGUMENT, missing)
    } catch (_: NotDirectoryException) {
        throw OperationFailure(ErrorCode.INVALID_ARGUMENT, invalid)
    }
    if (!attributes.isDirectory) throw OperationFailure(ErrorCode.INVALID_ARGUMENT, invalid)
}
