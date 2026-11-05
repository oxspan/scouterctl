package dev.scouterctl

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.collections.shouldContain
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.Path
import java.util.spi.ToolProvider

class ArchitectureTest : FunSpec({
    test("every compiled production class follows inward dependencies") {
        val roots = System.getProperty("architecture.main.classes").split(File.pathSeparator).map(Path::of).filter(Files::isDirectory)
        check(roots.isNotEmpty()) { "Missing production class directories" }
        val classes = roots.flatMap { root ->
            Files.walk(root).use { paths -> paths.filter { it.toString().endsWith(".class") }.map {
                root.relativize(it).toString().removeSuffix(".class").replace(File.separatorChar, '.')
            }.toList() }
        }
        check(classes.any { it.startsWith("dev.scouterctl.core.") })
        check(classes.any { it.startsWith("dev.scouterctl.application.") })
        classes.filter { layer(it) == null } shouldBe emptyList()
        val edges = dependencies(roots)
        val violations = edges.mapNotNull(::violation)
        println("Architecture: ${classes.size} production classes, ${edges.size} dependency edges, ${violations.size} violations")
        violations shouldBe emptyList()
    }
    test("compiled fully qualified I/O adapter properties and serialization annotations are rejected") {
        val fixtureRoot = Path.of(dev.scouterctl.architecturefixtures.core.InnerOnly::class.java.protectionDomain.codeSource.location.toURI())
        val directory = fixtureRoot.resolve("dev/scouterctl/architecturefixtures/core")
        val edges = dependencies(listOf(directory))
        val normalized = edges.map { it.copy(from = it.from.replace(".architecturefixtures.core.", ".core.")) }
        val violations = normalized.mapNotNull(::violation)
        violations.map { it.substringBefore(" -> ") }.toSet().apply {
            shouldContain("dev.scouterctl.core.FullyQualifiedIo")
            shouldContain("dev.scouterctl.core.AdapterProperty")
            shouldContain("dev.scouterctl.core.WireAnnotation")
        }
        normalized.filter { it.from == "dev.scouterctl.core.InnerOnly" }.mapNotNull(::violation) shouldBe emptyList()
        violations.any { it.endsWith(" -> java.nio.file.Files") } shouldBe true
        violations.any { it.endsWith(" -> dev.scouterctl.adapters.files.EvidenceStore") } shouldBe true
        violations.any { it.endsWith(" -> kotlinx.serialization.Serializable") } shouldBe true
        violations.any { it.endsWith(" -> java.lang.ProcessHandle") } shouldBe true
        violations.any { it.endsWith(" -> java.lang.reflect.Method") } shouldBe true
        violations.any { it.endsWith(" -> java.util.ServiceLoader") } shouldBe true
        println("Architecture negative fixtures: ${violations.joinToString()}")
    }
})

private data class Dependency(val from: String, val to: String)
private fun dependencies(paths: List<Path>): List<Dependency> {
    val output = StringWriter()
    val errors = StringWriter()
    val tool = ToolProvider.findFirst("jdeps").orElseThrow { IllegalStateException("Tests require the JDK dependency analyzer") }
    val args = listOf("-verbose:class", "-filter:none", "--multi-release", "21") + paths.map(Path::toString)
    val exit = tool.run(PrintWriter(output), PrintWriter(errors), *args.toTypedArray())
    check(exit == 0) { "jdeps failed ($exit): $errors" }
    val line = Regex("""^\s+(\S+)\s+->\s+(\S+)\s+.*$""")
    val edges = output.toString().lineSequence().mapNotNull { text ->
        line.matchEntire(text)?.destructured?.let { (from, to) -> Dependency(from, to) }
    }.filter { it.from.startsWith("dev.scouterctl.") }.toList()
    check(edges.isNotEmpty()) { "jdeps returned no class dependency edges: $output $errors" }
    return edges
}

private fun layer(name: String): String? = when {
    name == "dev.scouterctl.MainKt" -> "entry"
    name.startsWith("dev.scouterctl.core.") -> "core"
    name.startsWith("dev.scouterctl.application.") -> "application"
    name.startsWith("dev.scouterctl.adapters.serialization.") -> "serialization"
    name.startsWith("dev.scouterctl.adapters.files.") -> "files"
    name.startsWith("dev.scouterctl.adapters.collector.") -> "collector"
    name.startsWith("dev.scouterctl.cli.") -> "cli"
    name.startsWith("dev.scouterctl.bootstrap.") -> "bootstrap"
    else -> null
}

private fun violation(edge: Dependency): String? {
    val from = layer(edge.from) ?: return "${edge.from} -> ${edge.to}"
    val to = layer(edge.to)
    val allowed = when (from) {
        "core" -> setOf("core")
        "application" -> setOf("core", "application")
        "serialization" -> setOf("core", "serialization")
        "files" -> setOf("core", "application", "serialization", "files")
        "collector" -> setOf("core", "application", "collector")
        "cli" -> setOf("core", "application", "serialization", "cli")
        "bootstrap" -> setOf("core", "application", "serialization", "files", "collector", "cli", "bootstrap")
        "entry" -> setOf("bootstrap", "entry")
        else -> emptySet()
    }
    if (to != null) return if (to in allowed) null else "${edge.from} -> ${edge.to}"
    if (edge.to.startsWith("dev.scouterctl.")) return "${edge.from} -> ${edge.to}"
    if (from !in setOf("core", "application")) return null
    val valueUtility = edge.to.startsWith("kotlin.") && !edge.to.startsWith("kotlin.io.") && !edge.to.startsWith("kotlin.system.") && !edge.to.startsWith("kotlin.reflect.") && !edge.to.startsWith("kotlin.concurrent.") ||
        edge.to.startsWith("java.time.") || edge.to.startsWith("java.util.") && !edge.to.startsWith("java.util.ServiceLoader") ||
        edge.to.startsWith("java.lang.") && !edge.to.startsWith("java.lang.reflect.") &&
            edge.to !in setOf("java.lang.System", "java.lang.Runtime") &&
            listOf("java.lang.Process", "java.lang.Thread", "java.lang.Runtime$").none { edge.to.startsWith(it) }
    return if (valueUtility) null else "${edge.from} -> ${edge.to}"
}
