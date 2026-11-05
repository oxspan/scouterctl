package dev.scouterctl.cli

class CommandDispatcher(
    private val context: ContextCommands,
    private val offline: OfflineCommands,
    private val online: OnlineCommands,
) {
    fun execute(request: CommandRequest, connection: ConnectionOptions): Int = when (request) {
        is ContextRequest -> context.execute(request)
        is OfflineRequest -> offline.execute(request)
        is OnlineRequest -> online.execute(request, connection)
    }
}
