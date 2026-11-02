package dev.scouterctl

import java.nio.file.Path

fun inspectCapture(directory: Path, txid: Long, includeText: Boolean): Inspection {
    val store = EvidenceStore(directory)
    val manifest = store.load()
    store.validate(manifest)
    if (includeText && !manifest.textIncluded) {
        throw CliFailure(ErrorCode.INVALID_ARGUMENT, "Text was not stored in this capture")
    }
    var selected: EvidenceRecord? = null
    for (index in manifest.pages.indices) {
        store.readPage(manifest, index).firstOrNull { it.xlog.txid == txid.toString() }?.let { selected = it }
    }
    val record = selected ?: throw CliFailure(ErrorCode.NOT_FOUND,
        if (manifest.executionComplete) "Transaction not found in capture" else "Transaction not present in partial capture")
    val visible = if (includeText) record else record.copy(profile = record.profile?.let { profile ->
        profile.copy(steps = profile.steps.map { step -> step.copy(sql = step.sql?.copy(statement = null, error = null)) },
            transaction = null, detail = "structural-only; free text and binds omitted")
    })
    return Inspection(manifest.source, manifest.query, manifest.executionComplete,
        manifest.pages.sumOf { it.unavailableProfiles }, manifest.textIncluded, includeText, visible,
        manifest.upstreamCoverage, manifest.sampling)
}
