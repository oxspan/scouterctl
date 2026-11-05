package dev.scouterctl

import dev.scouterctl.core.*
import dev.scouterctl.adapters.files.*
import dev.scouterctl.adapters.serialization.*

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

@Serializable
internal data class ScaleScenario(
    val name: String, val records: Int, val services: Int,
    val afterOffset: Int = 0, val oversizedSide: String? = null, val corruptLastPage: Boolean = false,
) {
    val expectedExit: Int get() = when { corruptLastPage -> 10; oversizedSide != null -> 8; else -> 0 }
}

internal val scaleScenarios = listOf(
    ScaleScenario("records-1000", 1000, 100),
    ScaleScenario("records-10000", 10000, 100),
    ScaleScenario("records-100000", 100000, 100),
    ScaleScenario("services-1000", 10000, 1000),
    ScaleScenario("union-2000", 10000, 1000, afterOffset = 1000),
    ScaleScenario("before-1001", 1001, 1000, oversizedSide = "before"),
    ScaleScenario("after-1001", 1001, 1000, oversizedSide = "after"),
    ScaleScenario("corrupt-last-page", 100000, 100, corruptLastPage = true),
)

internal fun generateScaleInputs(root: Path) {
    Files.createDirectory(root)
    for (scenario in scaleScenarios) {
        val directory = Files.createDirectory(root.resolve(scenario.name))
        for (side in listOf("before", "after")) {
            val after = side == "after"
            val count = if (scenario.oversizedSide == side) 1001 else scenario.services
            val store = EvidenceStore(directory.resolve(side))
            store.create()
            val start = 1793491200000L + if (after) 3600000 else 0
            val pages = (0 until scenario.records step 100).mapIndexed { page, first ->
                val rows = (first until minOf(first + 100, scenario.records)).map { index ->
                    val service = index % count + 1 + if (after) scenario.afterOffset else 0
                    val occurrence = index / count
                    val txid = (index + 1L).toString()
                    val error = if (occurrence % 10 < if (after) 3 else 1) 7 else 0
                    EvidenceRecord(XLogRecord(txid, "0", "0", start + index, 42, service, if (after) 150 else 100, error),
                        ProfileAvailability.AVAILABLE, ProfileRecord(txid,
                            listOf(ProfileStep("sql", 0, SqlProfile(9, 10, 0,
                                statement = DictionaryText(9, "select name from sample where id = ?")))),
                            transaction = ProfileTransaction("available", DictionaryText(service, "/scale/service-$service"))))
                }
                store.writePage(page, rows)
            }
            store.save(EvidenceManifest(source = ServerInfo("scale-fixture", "2.21.3", "UTC", "guest"),
                endpoint = "synthetic-offline", query = Query("20261101", start, start + 3600000, "UTC", 42, 100),
                textIncluded = true, executionComplete = true, pages = pages))
            if (scenario.corruptLastPage && after) Files.writeString(directory.resolve(side).resolve(pages.last().name), "corrupt\n")
        }
    }
    Files.writeString(root.resolve("fingerprints.json"), json.encodeToString(scaleFingerprints(root)))
}

internal fun scaleFingerprints(root: Path): Map<String, String> = Files.walk(root).use { paths ->
    paths.filter { Files.isRegularFile(it) && it.fileName.toString() != "fingerprints.json" }.toList()
        .associate { root.relativize(it).toString().replace('\\', '/') to scaleSha(it) }.toSortedMap()
}

internal fun scaleSha(path: Path): String {
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(path).use { input ->
        val buffer = ByteArray(65536)
        while (true) {
            val size = input.read(buffer)
            if (size < 0) break
            digest.update(buffer, 0, size)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
