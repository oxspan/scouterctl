package dev.scouterctl.cli

import dev.scouterctl.core.*

import java.io.IOError

fun interface PasswordInput {
    fun read(interactive: Boolean, noInput: Boolean, login: Boolean): String
}

class TerminalPasswordInput : PasswordInput {
    override fun read(interactive: Boolean, noInput: Boolean, login: Boolean): String {
        System.getenv("SCOUTERCTL_PASSWORD")?.let { return it }
        val console = if (!noInput && (interactive || login)) System.console() else null
        if (console == null) throw OperationFailure(ErrorCode.INVALID_ARGUMENT,
            "Set SCOUTERCTL_PASSWORD, or use --interactive on a terminal for hidden input")
        val eof = if (System.getProperty("os.name").startsWith("Windows")) "Ctrl+Z then Enter" else "Ctrl+D"
        System.err.print("Collector password (hidden; $eof or Ctrl+C cancels): ")
        System.err.flush()
        val chars = try { console.readPassword() } catch (_: IOError) {
            throw OperationFailure(ErrorCode.IO_ERROR, "Could not read terminal password")
        }
        System.err.println()
        if (chars == null) throw OperationFailure(ErrorCode.INVALID_ARGUMENT, "Password input cancelled")
        return try {
            if (chars.isEmpty()) throw OperationFailure(ErrorCode.INVALID_ARGUMENT, "Password input is empty")
            String(chars)
        } finally { chars.fill('\u0000') }
    }
}
