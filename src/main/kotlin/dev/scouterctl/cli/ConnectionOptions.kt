package dev.scouterctl.cli

import picocli.CommandLine.Option

class ConnectionOptions {
    @Option(names = ["--host"], description = ["Collector host; saved context or 127.0.0.1"]) var host: String? = null
    @Option(names = ["--port"], description = ["Collector TCP port; saved context or 6100"]) var port: Int? = null
    @Option(names = ["--user"], description = ["Collector account; overrides saved context"]) var user: String? = null
    @Option(names = ["--context"], description = ["Use this saved context for this invocation"]) var context: String? = null
    @Option(names = ["--timeout-ms"], defaultValue = "5000") var timeoutMs: Int = 5000
    @Option(names = ["--interactive"], description = ["Allow hidden password input on a terminal"]) var interactive: Boolean = false
    @Option(names = ["--no-input"], description = ["Never prompt; overrides --interactive and auth login"]) var noInput: Boolean = false

}
