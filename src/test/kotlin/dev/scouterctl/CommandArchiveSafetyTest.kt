package dev.scouterctl

import dev.scouterctl.bootstrap.BuildInfo
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class CommandArchiveSafetyTest : FunSpec({
    for (unsafe in listOf("/escape", "C:/escape", "../escape", "nested/../../escape", "nested\\escape")) {
        test("package validation rejects unsafe relative archive member $unsafe before installation") {
            val original = Path.of(System.getProperty("cli.distribution"))
            val damaged = tempdir().toPath().resolve("package.zip")
            val libraries = ZipFile(original.toFile()).use { zip ->
                val entries = zip.entries().asSequence().toList()
                ZipOutputStream(Files.newOutputStream(damaged)).use { output ->
                    for (entry in entries) {
                        output.putNextEntry(ZipEntry(entry.name))
                        if (!entry.isDirectory) zip.getInputStream(entry).use { it.copyTo(output) }
                        output.closeEntry()
                    }
                    output.putNextEntry(ZipEntry("scouterctl-${BuildInfo.version}/$unsafe"))
                    output.write("unexpected outside file".toByteArray())
                    output.closeEntry()
                }
                entries.filter { !it.isDirectory && "/lib/" in it.name }.map { it.name.substringAfter("/lib/") }.toSet()
            }

            shouldThrow<IllegalStateException> {
                readCommandArchive(damaged, BuildInfo.version, libraries)
            }.message shouldBe "Unsafe package entry"
        }
    }
})
