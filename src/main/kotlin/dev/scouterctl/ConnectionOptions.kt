package dev.scouterctl

import picocli.CommandLine.Option
import java.io.IOError

class ConnectionOptions {
    @Option(names = ["--host"], description = ["Collector host; saved context or 127.0.0.1"]) var host: String? = null
    @Option(names = ["--port"], description = ["Collector TCP port; saved context or 6100"]) var port: Int? = null
    @Option(names = ["--user"], description = ["Collector account; overrides saved context"]) var user: String? = null
    @Option(names = ["--context"], description = ["Use this saved context for this invocation"]) var context: String? = null
    @Option(names = ["--timeout-ms"], defaultValue = "5000") var timeoutMs: Int = 5000
    @Option(names = ["--interactive"], description = ["Allow hidden password input on a terminal"]) var interactive: Boolean = false
    @Option(names = ["--no-input"], description = ["Never prompt; overrides --interactive and auth login"]) var noInput: Boolean = false

    fun endpoint(): Endpoint {
        val saved = if (context == null && host != null && port != null && user != null) null
            else ContextStore().selected(context)
        return Endpoint(host ?: saved?.host ?: "127.0.0.1", port ?: saved?.port ?: 6100,
            user ?: saved?.user ?: throw CliFailure(ErrorCode.INVALID_ARGUMENT, "Missing --user; select a context or provide a Collector account"), timeoutMs)
    }

    fun password(login: Boolean): String {
        System.getenv("SCOUTERCTL_PASSWORD")?.let { return it }
        val console = if (!noInput && (interactive || login)) System.console() else null
        if (console == null) throw CliFailure(ErrorCode.INVALID_ARGUMENT,
            "Set SCOUTERCTL_PASSWORD, or use --interactive on a terminal for hidden input")
        val eof = if (System.getProperty("os.name").startsWith("Windows")) "Ctrl+Z then Enter" else "Ctrl+D"
        System.err.print("Collector password (hidden; $eof or Ctrl+C cancels): ")
        System.err.flush()
        val chars = try { console.readPassword() } catch (_: IOError) {
            throw CliFailure(ErrorCode.IO_ERROR, "Could not read terminal password")
        }
        System.err.println()
        if (chars == null) throw CliFailure(ErrorCode.INVALID_ARGUMENT, "Password input cancelled")
        return try {
            if (chars.isEmpty()) throw CliFailure(ErrorCode.INVALID_ARGUMENT, "Password input is empty")
            String(chars)
        } finally { chars.fill('\u0000') }
    }
}
