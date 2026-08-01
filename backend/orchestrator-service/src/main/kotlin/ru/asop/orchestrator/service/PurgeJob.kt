package ru.asop.orchestrator.service

import io.r2dbc.spi.Connection
import io.r2dbc.spi.ConnectionFactory
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import ru.asop.orchestrator.config.OrchestratorProperties
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Purge-job: раз в час физически удаляет soft-deleted строки старше 6 месяцев.
 * Триггеры soft-delete отключаются через SET session_replication_role='replica'
 * (пользователь asop — SUPERUSER, см. schema v001 раздел 11).
 */
@Component
class PurgeJob(
    private val connectionFactory: ConnectionFactory,
    private val props: OrchestratorProperties
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${asop.purge.interval-ms}", initialDelayString = "60000")
    fun purge() {
        if (!props.purge.enabled) {
            log.debug("Purge disabled")
            return
        }
        Mono.usingWhen(
            connectionFactory.create(),
            { conn -> runPurge(conn) },
            { conn -> conn.close() }
        ).subscribe(
            { total -> if (total > 0) log.info("Purge complete: {} rows deleted", total) else log.debug("Purge: nothing to delete") },
            { err -> log.error("Purge failed", err) }
        )
    }

    private fun runPurge(conn: Connection): Mono<Long> {
        val cutoff = LocalDateTime.now().minusMonths(props.purge.retentionMonths.toLong())
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        return setRole(conn, "replica")
            .thenMany(Flux.fromIterable(MasterRegistry.ALL.keys))
            .concatMap { table -> deleteTable(conn, table, cutoff) }
            .collectList()
            .flatMap { counts ->
                setRole(conn, "origin")
                    .thenReturn(counts.sum())
                    .onErrorResume { err ->
                        log.error("Failed to restore session_replication_role", err)
                        setRole(conn, "origin").thenReturn(counts.sum())
                    }
            }
    }

    private fun deleteTable(conn: Connection, table: String, cutoff: String): Mono<Long> {
        val sql = "DELETE FROM $table WHERE DELETED_AT IS NOT NULL AND DELETED_AT < TIMESTAMP '$cutoff'"
        return Flux.from(conn.createStatement(sql).execute())
            .flatMap { result -> Mono.from(result.rowsUpdated) }
            .reduce(0L) { a, b -> a + b }
            .doOnNext { n -> if (n > 0L) log.info("  purged {} rows from {}", n, table) }
    }

    private fun setRole(conn: Connection, role: String): Mono<Void> {
        return Flux.from(conn.createStatement("SET session_replication_role = '$role'").execute()).then()
    }
}
