package ru.asop.terminal.repository

import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.r2dbc.repository.R2dbcRepository
import org.springframework.stereotype.Repository
import ru.asop.terminal.model.TerminalProfileEntity
import java.util.UUID
import reactor.core.publisher.Mono

@Repository
interface TerminalProfileRepository : R2dbcRepository<TerminalProfileEntity, UUID> {

    /** Активный базовый профиль (IS_BASE=TRUE, не soft-deleted) — назначается при регистрации. */
    @Query(
        """SELECT PROFILE_ID, PROFILE_NAME, IS_BASE FROM ASOP_TERMINAL_PROFILES
           WHERE IS_BASE = TRUE AND DELETED_AT IS NULL
           LIMIT 1"""
    )
    fun findBase(): Mono<TerminalProfileEntity>
}