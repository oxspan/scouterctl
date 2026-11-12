package dev.scouterctl.cli

import picocli.CommandLine
import picocli.CommandLine.Help
import picocli.CommandLine.Model.CommandSpec

internal fun Cli.configureHelp(command: CommandLine) {
    val ansi = if (System.console() == null || System.getenv("NO_COLOR") != null) Help.Ansi.OFF else Help.Ansi.AUTO
    command.setColorScheme(Help.defaultColorScheme(ansi))
    command.setHelpFactory { root, colors -> Help(helpSpec(root, words.joinToString(" ")), colors) }
}

private fun helpSpec(root: CommandSpec, name: String): CommandSpec {
    val guide = commandGuides[name]
    val children = commandGuides.values.filter { it.name.startsWith("$name ") }
    val help = CommandSpec.create().name(if (guide == null) "scouterctl" else "scouterctl $name")
    val usage = help.usageMessage().optionListHeading("Options:%n").sortOptions(false)
    if (guide != null) {
        val footer = listOf("  scouterctl ${guide.example}") + if (children.isEmpty()) emptyList() else
            listOf("", "Subcommands:") + children.map { "  ${it.name.padEnd(18)} ${it.description}" }
        usage.description(guide.description, *guide.notes.toTypedArray())
            .footerHeading("%nExample:%n").footer(*footer.toTypedArray())
        root.options().filter { it.usageHelp() || it.versionHelp() || it.longestName() in guide.allowed }.forEach { option ->
            help.addOption(option.toBuilder().required(option.longestName() in guide.required).build())
        }
    } else {
        val entries = if (name.isEmpty()) commandGuides.values else children
        if (entries.isEmpty()) return root
        usage.customSynopsis("scouterctl ${if (name.isEmpty()) "COMMAND" else "$name COMMAND"} [OPTIONS]")
            .description(*(if (name.isEmpty()) root.usageMessage().description() else arrayOf("Scouter $name commands.")))
            .footerHeading("%nCommands:%n").footer(*entries.map { "  ${it.name.padEnd(18)} ${it.description}" }.toTypedArray(),
                "", "Use scouterctl COMMAND --help for required options, accepted options and an example.")
        root.options().filter { it.usageHelp() || it.versionHelp() }.forEach { help.addOption(it.toBuilder().build()) }
    }
    return help
}
