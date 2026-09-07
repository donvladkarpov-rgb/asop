package ru.asop.admin.model

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant
import java.util.UUID

/**
 * Профиль настроек терминала (ASOP_TERMINAL_PROFILES).
 * PROFILE_PARAMS — JSONB в виде JSON-строки (сериализуется ObjectMapper),
 * например интервалы воркеров терминала. IS_BASE — признак базового профиля
 * (назначается терминалам при регистрации, если PROFILE_ID не передан;
 * активный базовый профиль может быть ровно один — уникальный частичный индекс).
 */
@Table("ASOP_TERMINAL_PROFILES")
data class TerminalProfileEntity(
    @Id
    val profileId: UUID,
    val profileName: String,
    val profileParams: String? = null,
    val isBase: Boolean = false,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
    val deletedAt: Instant? = null,
    val version: Long? = null
)