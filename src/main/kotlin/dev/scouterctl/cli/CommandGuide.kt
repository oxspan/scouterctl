package dev.scouterctl.cli

internal data class CommandGuide(
    val name: String,
    val description: String,
    val required: Set<String>,
    val optional: Set<String>,
    val example: String,
    val notes: List<String> = emptyList(),
) {
    val allowed: Set<String> get() = required + optional
}

private val connectionOptions = setOf("--host", "--port", "--user", "--context", "--timeout-ms", "--interactive", "--no-input")
private val windowOptions = setOf("--from", "--to", "--timezone", "--object-hash")
private val xlogOptions = windowOptions + "--date"
private val pagingOptions = setOf("--page-size", "--max-pages")
private val offlineOptions = setOf("--include-text", "--no-input")
private val connectionNotes = listOf("Connection options override a saved context; otherwise host/port default to 127.0.0.1:6100.",
    "Provide an account with --user or a saved context. Use SCOUTERCTL_PASSWORD, or --interactive for hidden terminal input.")
private const val windowExample = "--date 20261101 --from 2026-11-01T00:00:00Z --to 2026-11-01T00:01:00Z --timezone UTC --object-hash 42"

internal val commandGuides = listOf(
    CommandGuide("doctor", "Check Collector connectivity and authentication.", emptySet(), connectionOptions,
        "doctor --host localhost --user reader --no-input", connectionNotes),
    CommandGuide("auth login", "Check credentials without saving a password.", emptySet(), connectionOptions,
        "auth login --host localhost --user reader --interactive", connectionNotes),
    CommandGuide("context add", "Save a named connection without its password.", setOf("--name", "--user"),
        setOf("--host", "--port", "--no-input"), "context add --name local --host localhost --user reader"),
    CommandGuide("context list", "List saved connections and the selected context.", emptySet(), setOf("--no-input"), "context list"),
    CommandGuide("context use", "Select a saved connection.", setOf("--name"), setOf("--no-input"), "context use --name local"),
    CommandGuide("collector status", "Read Collector status.", emptySet(), connectionOptions,
        "collector status --user reader --no-input", connectionNotes),
    CommandGuide("collector config", "Read safe Collector settings; optionally select an exact key.", emptySet(), connectionOptions + "--key",
        "collector config --user reader --no-input", connectionNotes),
    CommandGuide("agent status", "Read Agent JVM and runtime status.", setOf("--object-hash"), connectionOptions,
        "agent status --object-hash 42 --user reader --no-input", connectionNotes),
    CommandGuide("agent config", "Read safe Agent settings; optionally select an exact key.", setOf("--object-hash"), connectionOptions + "--key",
        "agent config --object-hash 42 --user reader --no-input", connectionNotes),
    CommandGuide("object list", "List live objects, or recorded objects for --date.", emptySet(), connectionOptions + "--date",
        "object list --user reader --no-input", connectionNotes),
    CommandGuide("counter list", "List available real-time counters for an object.", setOf("--object-hash"), connectionOptions,
        "counter list --object-hash 42 --user reader --no-input", connectionNotes),
    CommandGuide("counter get", "Read one exact real-time counter.", setOf("--object-hash", "--counter"), connectionOptions,
        "counter get --object-hash 42 --counter Cpu --user reader --no-input", connectionNotes),
    CommandGuide("counter history", "Stream stored counter values for a dated window.", xlogOptions + "--counter", connectionOptions,
        "counter history $windowExample --counter Cpu --user reader --no-input", connectionNotes),
    CommandGuide("counter export", "Export stored counters in resumable chunks.", windowOptions + setOf("--counter", "--directory"),
        connectionOptions + setOf("--resume", "--chunk-minutes", "--max-chunks"),
        "counter export --from 2026-11-01T00:00:00Z --to 2026-11-01T00:01:00Z --timezone UTC --object-hash 42 --counter Cpu --directory counters --user reader --no-input",
        connectionNotes + "Use the same query and directory with --resume; chunk boundaries split at Collector midnight."),
    CommandGuide("xlog search", "Stream XLogs for a storage date and time window.", xlogOptions, connectionOptions + pagingOptions + "--include-text",
        "xlog search $windowExample --user reader --no-input", connectionNotes),
    CommandGuide("profile get", "Read one stored transaction profile.", setOf("--date", "--txid"), connectionOptions + "--include-text",
        "profile get --date 20261101 --txid 123 --user reader --no-input", connectionNotes),
    CommandGuide("capture", "Save a resumable XLog capture, optionally including profiles.", xlogOptions + "--directory",
        connectionOptions + pagingOptions + setOf("--resume", "--include-profiles", "--include-text"),
        "capture $windowExample --directory capture --user reader --no-input",
        connectionNotes + listOf("--include-text requires --include-profiles. Text can contain sensitive values.",
            "Use the same query and directory with --resume. Profile capture defaults to page size 25, maximum 100.")),
    CommandGuide("inspect", "Read a captured profile offline.", setOf("--directory", "--txid"), offlineOptions,
        "inspect --directory capture --txid 123"),
    CommandGuide("inspect search", "Search captured requests offline.", setOf("--directory"), offlineOptions +
        setOf("--limit", "--min-elapsed-ms", "--errors-only", "--service-hash", "--service-name"),
        "inspect search --directory capture --errors-only", listOf("--service-name requires --include-text and stored dictionary text.")),
    CommandGuide("inspect summary", "Summarize captured services offline.", setOf("--directory"), offlineOptions + setOf("--limit", "--sort"),
        "inspect summary --directory capture --sort elapsed"),
    CommandGuide("inspect compare", "Compare baseline and candidate captures offline.", setOf("--before", "--after"), offlineOptions + "--limit",
        "inspect compare --before baseline --after candidate"),
).associateBy { it.name }
