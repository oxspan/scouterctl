package dev.scouterctl.bootstrap

import dev.scouterctl.core.*

import dev.scouterctl.adapters.collector.CollectorSource
import dev.scouterctl.adapters.files.*
import dev.scouterctl.cli.*
import java.io.PrintStream
import java.nio.file.Path
import picocli.CommandLine
import kotlin.system.exitProcess

fun runCli(args: Array<String>) {
    val output = JsonOutput(PrintStream(System.out, true, Charsets.UTF_8))
    val contexts = { ContextStore(configPath()) }
    val dispatcher = CommandDispatcher(
        ContextCommands(contexts, output),
        OfflineCommands(::EvidenceStore, output),
        OnlineCommands(contexts, { endpoint, password -> CollectorSource.connect(endpoint, password, BuildInfo.version) },
            ::CaptureStore, ::EvidenceStore, ::CounterCaptureStore, TerminalPasswordInput(), output),
    )
    val command = CommandLine(Cli(dispatcher, output))
    command.commandSpec.version("scouterctl ${BuildInfo.version}")
    command.setParameterExceptionHandler { _, _ -> output.failure(OperationFailure(ErrorCode.INVALID_ARGUMENT, "Invalid command arguments; use --help")) }
    exitProcess(command.execute(*args))
}

private fun configPath(): Path {
    val explicit = System.getenv("SCOUTERCTL_CONFIG")
    if (explicit != null) {
        if (explicit.isBlank()) throw OperationFailure(ErrorCode.INVALID_ARGUMENT, "SCOUTERCTL_CONFIG must name a file")
        return Path.of(explicit).toAbsolutePath()
    }
    val xdg = System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }
    val base = if (xdg != null) Path.of(xdg) else Path.of(System.getProperty("user.home"), ".config")
    if (!base.isAbsolute) throw OperationFailure(ErrorCode.INVALID_ARGUMENT, "XDG_CONFIG_HOME must be absolute")
    return base.resolve("scouterctl/config.json")
}
