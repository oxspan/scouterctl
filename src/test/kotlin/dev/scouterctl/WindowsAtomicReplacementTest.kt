package dev.scouterctl

import com.sun.nio.file.ExtendedOpenOption.NOSHARE_DELETE
import dev.scouterctl.adapters.files.replaceAtomically
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption.READ
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit.MILLISECONDS
import java.util.concurrent.TimeUnit.SECONDS

class WindowsAtomicReplacementTest : FunSpec({
    if (System.getProperty("os.name").startsWith("Windows")) {
        test("replacement succeeds after a temporary Windows handle releases the committed file") {
            val work = tempdir().toPath()
            val target = Files.writeString(work.resolve("manifest.json"), "committed")
            val partial = Files.writeString(work.resolve("manifest.json.partial"), "replacement")
            val released = CompletableFuture<Unit>()

            FileChannel.open(target, READ, NOSHARE_DELETE).use { held ->
                Executors.newSingleThreadScheduledExecutor().use { scheduler ->
                    scheduler.schedule({
                        try { held.close(); released.complete(Unit) }
                        catch (failure: IOException) { released.completeExceptionally(failure) }
                    }, 100, MILLISECONDS)
                    replaceAtomically(partial, target)
                    released.get(5, SECONDS)
                }
            }

            Files.readString(target) shouldBe "replacement"
            Files.exists(partial) shouldBe false
        }
    }
})
