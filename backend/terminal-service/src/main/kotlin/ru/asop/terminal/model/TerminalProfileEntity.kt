package ru.asop.terminal.model

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.util.UUID

/**
 * Профиль настроек терминала (ASOP_TERMINAL_PROFILES) — привязка терминала к профилю
 * при регистрации (базовый профиль IS_BASE=TRUE, если PROFILE_ID не передан).
 */
@Table("ASOP_TERMINAL_PROFILES")
data class TerminalProfileEntity(
    @Id
    val profileId: UUID,
    val profileName: String,
    val isBase: Boolean = false
)