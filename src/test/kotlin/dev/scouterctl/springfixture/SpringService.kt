package dev.scouterctl.springfixture

import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import java.nio.file.Files
import java.nio.file.Path

@SpringBootApplication(proxyBeanMethods = false)
class SpringService

@RestController
class FixtureController(private val jdbc: JdbcTemplate) {
    @GetMapping("/poc/ok")
    fun ok(): String = "ok"

    @GetMapping("/poc/sql")
    fun sql(): String = jdbc.queryForObject("select marker from fixture_rows where id = ?", String::class.java, 1)!!

    @GetMapping("/poc/slow-sql")
    fun slowSql(): Int = jdbc.queryForObject("call fixture_delay(?)", Int::class.java, 350L)!!

    @GetMapping("/poc/app-error")
    fun applicationError(): String {
        sql()
        throw IllegalStateException("private-application-error-marker")
    }

    @GetMapping("/poc/sql-error")
    fun sqlError(): String {
        jdbc.update("insert into fixture_rows(id, marker) values (?, ?)", 1, "private-bind-marker")
        return "unexpected"
    }
}

object SlowQuery {
    @JvmStatic fun delay(milliseconds: Long): Int {
        Thread.sleep(milliseconds)
        return 1
    }
}

fun main(args: Array<String>) {
    val context = SpringApplication.run(SpringService::class.java,
        "--server.address=127.0.0.1", "--server.port=0", "--spring.main.banner-mode=off",
        "--spring.datasource.url=jdbc:h2:mem:servletfixture;DB_CLOSE_DELAY=-1",
        "--spring.datasource.username=sa", "--spring.datasource.password=",
        "--server.error.include-message=never", "--server.error.include-stacktrace=never")
    val jdbc = context.getBean(JdbcTemplate::class.java)
    jdbc.execute("create alias fixture_delay for 'dev.scouterctl.springfixture.SlowQuery.delay'")
    jdbc.execute("create table fixture_rows(id integer primary key, marker varchar(100))")
    jdbc.update("insert into fixture_rows(id, marker) values (?, ?)", 1, "private-bind-marker")
    val port = (context as ServletWebServerApplicationContext).webServer.port
    Files.writeString(Path.of(args.single()), port.toString())
}
