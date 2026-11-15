package dev.scouterctl

import dev.scouterctl.adapters.files.fileOperation
import dev.scouterctl.core.ErrorCode
import dev.scouterctl.core.OperationFailure
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileSystemException
import java.nio.file.NoSuchFileException

class FileOperationTest : FunSpec({
    val secret = "private-path-and-filesystem-reason"
    val failures = listOf(
        AccessDeniedException(secret, secret, secret) to "AccessDeniedException",
        NoSuchFileException(secret, secret, secret) to "NoSuchFileException",
        FileAlreadyExistsException(secret, secret, secret) to "FileAlreadyExistsException",
        AtomicMoveNotSupportedException(secret, secret, secret) to "AtomicMoveNotSupportedException",
        FileSystemException(secret, secret, secret) to "FileSystemException",
        IOException(secret) to "IOException",
    )
    for ((cause, category) in failures) {
        test("local I/O identifies $category without disclosing paths or filesystem messages") {
            val failure = shouldThrow<OperationFailure> { fileOperation<Unit> { throw cause } }
            failure.code shouldBe ErrorCode.IO_ERROR
            failure.message shouldBe "Local file I/O failed ($category); check the path, permissions and available disk space"
            failure.message!! shouldNotContain secret
        }
    }
})
