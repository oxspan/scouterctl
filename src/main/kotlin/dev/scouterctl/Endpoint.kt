package dev.scouterctl

data class Endpoint(val host: String, val port: Int, val user: String, val timeoutMs: Int) {
    init {
        if (host.isBlank() || host.any(Char::isWhitespace) || '/' in host) {
            throw CliFailure(ErrorCode.INVALID_ARGUMENT, "--host must be a hostname or IP address, without a URL scheme or path")
        }
        if (user.isBlank()) throw CliFailure(ErrorCode.INVALID_ARGUMENT, "--user must name a Collector account")
        if (port !in 1..65535 || timeoutMs !in 1..60000) {
            throw CliFailure(ErrorCode.INVALID_ARGUMENT, "Use --port 1..65535 and --timeout-ms 1..60000")
        }
    }
}
