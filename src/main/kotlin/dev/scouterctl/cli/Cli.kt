package dev.scouterctl.cli

import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import picocli.CommandLine.Mixin
import picocli.CommandLine.Spec
import picocli.CommandLine.Model.CommandSpec
import java.nio.file.Path
import java.util.concurrent.Callable

@Command(name = "scouterctl", mixinStandardHelpOptions = true, showAtFileInUsageHelp = true,
    description = ["Scouter Collector CLI. Commands: counter list, counter get, counter history, counter export, collector status, collector config, agent status, agent config, doctor, object list, xlog search, profile get, capture, inspect, inspect search, inspect summary, inspect compare.",
        "Connect: doctor --host HOST --port PORT --user ACCOUNT, then object list with the same options.",
        "Save/select connections: context add/list/use --name NAME. Check credentials: auth login.",
        "Password: SCOUTERCTL_PASSWORD, or --interactive for hidden terminal input. Passwords are never saved.",
        "TCP has no native TLS; use a trusted path."])
class Cli(private val dispatcher: CommandDispatcher, private val output: JsonOutput) : Callable<Int> {
    @Parameters(arity = "1..2", paramLabel = "COMMAND") var words: List<String> = emptyList()
    @Mixin val connection = ConnectionOptions()
    @Spec lateinit var spec: CommandSpec
    @Option(names = ["--name"], description = ["Name for context add/use"]) var name: String? = null
    @Option(names = ["--counter"], description = ["counter get / counter history / counter export: exact counter name"]) var counterName: String? = null
    @Option(names = ["--key"], description = ["collector config / agent config: exact setting name"]) var settingKey: String? = null
    @Option(names = ["--chunk-minutes"], description = ["counter export: 1..1440; default 60, split at Collector midnight"]) var chunkMinutes: Int? = null
    @Option(names = ["--max-chunks"], description = ["counter export: chunks per invocation; default 24"]) var maxChunks: Int? = null
    @Option(names = ["--date"], description = ["Collector storage date (YYYYMMDD). For object list, selects recorded objects."]) var date: String? = null
    @Option(names = ["--from"]) var from: String? = null
    @Option(names = ["--to"]) var to: String? = null
    @Option(names = ["--timezone"], description = ["Operator-declared collector IANA timezone"] ) var timezone: String? = null
    @Option(names = ["--object-hash"]) var objHash: Int? = null
    @Option(names = ["--page-size"], description = ["Default 1000; profile capture default 25, maximum 100"]) var pageSize: Int? = null
    @Option(names = ["--max-pages"], defaultValue = "40") var maxPages: Int = 40
    @Option(names = ["--txid"]) var txid: Long? = null
    @Option(names = ["--directory"]) var directory: Path? = null
    @Option(names = ["--before"], description = ["inspect compare: baseline v2/v3 capture directory"]) var before: Path? = null
    @Option(names = ["--after"], description = ["inspect compare: candidate v2/v3 capture directory"]) var after: Path? = null
    @Option(names = ["--resume"]) var resume: Boolean = false
    @Option(names = ["--include-profiles"], description = [
        "Capture profiles in a v3 bundle for offline inspect.",
        "Default capture writes an XLog-only v1 bundle.",
    ]) var includeProfiles: Boolean = false
    @Option(names = ["--include-text"], description = ["Include potentially sensitive dictionary text in xlog search, profile get, profile capture or inspect"])
    var includeText: Boolean = false
    @Option(names = ["--group-sql"], description = ["profile get / inspect: aggregate decoded SQL steps by hash instead of returning steps"])
    var groupSql: Boolean = false
    @Option(names = ["--limit"], description = ["inspect search/summary/compare: 1..1000 requests/services; default 20"]) var limit: Int? = null
    @Option(names = ["--sort"], description = ["inspect summary: errors, elapsed (maximum), requests; default errors"]) var summarySort: String? = null
    @Option(names = ["--min-elapsed-ms"], description = ["inspect search: inclusive minimum request duration"]) var minElapsedMs: Int? = null
    @Option(names = ["--errors-only"], description = ["inspect search: nonzero XLog error hash"]) var errorsOnly: Boolean = false
    @Option(names = ["--service-hash"], description = ["inspect search: exact service hash"]) var serviceHash: Int? = null
    @Option(names = ["--service-name"], description = ["inspect search: exact stored name; requires --include-text"]) var serviceName: String? = null

    override fun call(): Int = output.run { dispatcher.execute(interpret(), connection) }
}
