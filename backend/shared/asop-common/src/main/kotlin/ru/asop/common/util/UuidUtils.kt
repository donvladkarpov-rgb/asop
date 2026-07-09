package ru.asop.common.util

import com.github.f4b6a3.uuid.UuidCreator
import java.util.UUID

object UuidUtils {
    fun newId(): UUID = UuidCreator.getTimeOrderedEpoch()

    fun isValid(value: String?): Boolean {
        if (value.isNullOrBlank()) return false
        return try {
            UUID.fromString(value)
            true
        } catch (_: IllegalArgumentException) {
            false
        }
    }

    fun parseOrNull(value: String?): UUID? = try {
        value?.takeIf { it.isNotBlank() }?.let(UUID::fromString)
    } catch (_: IllegalArgumentException) {
        null
    }

    fun parseOrThrow(value: String?): UUID =
        parseOrNull(value) ?: throw IllegalArgumentException("Invalid UUID: $value")
}