package dev.scouterctl.fixture

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.util.concurrent.Executors

/** Real HTTP service. Scouter instruments execute/work/JDBC; no telemetry packs are constructed here. */
fun main(args: Array<String>) {
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.executor = Executors.newFixedThreadPool(8)
    val service = TestService()
    server.createContext("/") { exchange ->
        val (status, body) = try {
            if (exchange.requestURI.path == "/health") 200 to "ready"
            else 200 to service.execute(exchange.requestURI.path)
        } catch (_: IllegalStateException) {
            500 to "expected test failure"
        }
        val bytes = body.toByteArray()
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
        exchange.close()
    }
    server.start()
    Files.writeString(Path.of(args.single()), server.address.port.toString())
}

class TestService {
    fun execute(name: String): String {
        val result = TestWork().work()
        return when (name) {
            "/poc/ok" -> "ok:$result"
            "/poc/slow" -> { Thread.sleep(180); "slow:$result" }
            "/poc/error" -> throw IllegalStateException("fixture-error-marker")
            "/poc/sql" -> DriverManager.getConnection("jdbc:h2:mem:poc;DB_CLOSE_DELAY=-1").use { connection ->
                connection.prepareStatement("select ? as marker").use { statement ->
                    statement.setString(1, "private-bind-marker")
                    statement.executeQuery().use { rows -> check(rows.next()); "sql:${rows.getString(1).length}" }
                }
            }
            else -> throw IllegalStateException("unknown test route")
        }
    }
}

class TestWork {
    fun work(): Long = (1L..10_000L).sum()
}
