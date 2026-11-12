package dev.scouterctl.cli

import dev.scouterctl.core.ErrorCode
import dev.scouterctl.core.OperationFailure
import picocli.CommandLine.Model.OptionSpec
import picocli.CommandLine.MissingParameterException
import picocli.CommandLine.ParameterException
import picocli.CommandLine.UnmatchedArgumentException

internal fun argumentFailure(error: ParameterException): OperationFailure {
    val option = (error.argSpec as? OptionSpec)?.longestName()
        ?: (error as? MissingParameterException)?.missing?.filterIsInstance<OptionSpec>()?.firstOrNull()?.longestName()
    val message = if (option != null) {
        "Invalid or missing value for $option; use --help"
    } else if (error is UnmatchedArgumentException) {
        val unknown = error.unmatched.firstOrNull { it.substringBefore('=').matches(Regex("--?[A-Za-z][A-Za-z0-9-]*")) }
            ?.substringBefore('=')
        if (unknown == null) "Invalid command arguments; use --help" else "Unknown option $unknown; use --help"
    } else "Invalid command arguments; use --help"
    return OperationFailure(ErrorCode.INVALID_ARGUMENT, message)
}
