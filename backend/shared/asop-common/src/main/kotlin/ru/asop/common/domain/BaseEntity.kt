package ru.asop.common.domain

import com.github.f4b6a3.uuid.UuidCreator
import java.time.Instant
import java.util.UUID

/**
 * Базовая сущность для всех таблиц ASOP.
 * Использует UUIDv7 (Time-Ordered UUID) для оптимизации B-tree индексов PostgreSQL.
 */
abstract class BaseEntity(
    val id: UUID = UuidCreator.getTimeOrderedEpoch(),
    val createdAt: Instant = Instant.now(),
    var updatedAt: Instant = Instant.now()
) {
    fun markUpdated() {
        updatedAt = Instant.now()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BaseEntity) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String = "${this::class.simpleName}(id=$id)"
}