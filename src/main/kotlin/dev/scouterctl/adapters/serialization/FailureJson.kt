package dev.scouterctl.adapters.serialization

import dev.scouterctl.core.ErrorCode
import kotlinx.serialization.Serializable

@Serializable
enum class ErrorCodeDto {
    INVALID_ARGUMENT, AUTH_FAILED, CONNECTION_FAILED, PROTOCOL_ERROR,
    NOT_FOUND, PARTIAL_RESULT, VERSION_MISMATCH, IO_ERROR
}
fun ErrorCode.toDto(): ErrorCodeDto = ErrorCodeDto.valueOf(name)
fun ErrorCodeDto.toModel(): ErrorCode = ErrorCode.valueOf(name)
