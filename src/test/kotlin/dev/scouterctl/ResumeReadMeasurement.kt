package dev.scouterctl

import jdk.jfr.Recording
import jdk.jfr.consumer.RecordingFile
import java.nio.file.Path
import java.time.Duration

internal data class ResumeReadMeasurement<T>(val result: T, val bytes: Map<Path, Long>, val elapsedNanos: Long)

internal fun <T> measureResumeReads(directory: Path, operation: () -> T): ResumeReadMeasurement<T> {
    val recordingFile = directory.resolve("reads.jfr")
    val measuredDirectory = directory.toAbsolutePath().normalize()
    val elapsed: Long
    val result: T
    Recording().use { recording ->
        recording.setToDisk(false)
        recording.enable("jdk.FileRead").withThreshold(Duration.ZERO).withoutStackTrace()
        recording.start()
        val started = System.nanoTime()
        result = operation()
        elapsed = System.nanoTime() - started
        recording.dump(recordingFile)
        recording.stop()
    }
    val bytes = mutableMapOf<Path, Long>()
    RecordingFile(recordingFile).use { events ->
        while (events.hasMoreEvents()) {
            val event = events.readEvent()
            if (event.eventType.name != "jdk.FileRead" || event.getLong("bytesRead") <= 0) continue
            val path = Path.of(event.getString("path")).toAbsolutePath().normalize()
            if (path.parent != measuredDirectory || !path.fileName.toString().startsWith("page-")) continue
            bytes.merge(path, event.getLong("bytesRead"), Long::plus)
        }
    }
    return ResumeReadMeasurement(result, bytes, elapsed)
}
