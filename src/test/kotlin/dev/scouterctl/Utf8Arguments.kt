package dev.scouterctl

import java.nio.file.Files
import java.nio.file.Path

internal fun utf8Arguments(directory: Path, args: List<String>): String {
    val file = Files.createTempFile(directory, "command-", ".args")
    Files.writeString(file, args.joinToString("\n") { arg ->
        "\"" + arg.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\""
    } + "\n")
    return "@${file.fileName}"
}
