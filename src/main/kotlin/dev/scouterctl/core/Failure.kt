package dev.scouterctl.core

enum class ErrorCode {
    INVALID_ARGUMENT, AUTH_FAILED, CONNECTION_FAILED, PROTOCOL_ERROR,
    NOT_FOUND, PARTIAL_RESULT, VERSION_MISMATCH, IO_ERROR
}

open class OperationFailure(val code: ErrorCode, message: String) : RuntimeException(message)

class UnexpectedFailure(code: ErrorCode, message: String) : OperationFailure(code, message)
