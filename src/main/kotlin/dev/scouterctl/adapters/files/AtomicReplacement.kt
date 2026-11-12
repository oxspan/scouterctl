package dev.scouterctl.adapters.files

import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

internal fun replaceAtomically(partial: Path, target: Path) {
    val attempts = if (System.getProperty("os.name").startsWith("Windows")) 5 else 1
    repeat(attempts) { attempt ->
        try {
            Files.move(partial, target, ATOMIC_MOVE, REPLACE_EXISTING)
            return
        } catch (failure: AccessDeniedException) {
            if (attempt == attempts - 1) throw failure
            try {
                Thread.sleep(50)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                throw failure
            }
        }
    }
}
