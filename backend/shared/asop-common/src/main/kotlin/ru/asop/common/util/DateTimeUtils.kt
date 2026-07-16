package ru.asop.common.util

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Хелперы для работы с UTC-моментами и IANA timezone в бизнес-логике.
 * Все методы возвращают либо UTC (Instant), либо значения в явно заданной zone — никаких дефолтных зон.
 */
object DateTimeUtils {

    const val UTC = "UTC"

    // ----- Parsers -----

    fun parseInstant(s: String): Instant? = try {
        Instant.parse(s)
    } catch (_: Exception) {
        try {
            ZonedDateTime.parse(s, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant()
        } catch (_: Exception) {
            try {
                fromLocalDateTime(LocalDateTime.parse(s, DateTimeFormatter.ISO_LOCAL_DATE_TIME), ZoneId.of(UTC))
            } catch (_: Exception) {
                null
            }
        }
    }

    fun parseLocalDate(s: String): LocalDate? = try {
        LocalDate.parse(s, DateTimeFormatter.ISO_LOCAL_DATE)
    } catch (_: Exception) {
        null
    }

    fun parseLocalTime(s: String): LocalTime? = try {
        LocalTime.parse(s, DateTimeFormatter.ISO_LOCAL_TIME)
    } catch (_: Exception) {
        null
    }

    // ----- Formatters -----

    fun instantToUiString(instant: Instant, zone: ZoneId = ZoneId.of(UTC)): String {
        val zdt = instant.atZone(zone)
        return zdt.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
    }

    fun instantToDisplayString(instant: Instant, zone: ZoneId = ZoneId.of(UTC)): String {
        val zdt = instant.atZone(zone)
        val offset = zdt.offset.id
        return zdt.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) + " (UTC$offset)"
    }

    fun instantOfLocal(isoLocal: String, zone: ZoneId = ZoneId.of(UTC)): Instant {
        val local = LocalDateTime.parse(isoLocal, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        return local.atZone(zone).toInstant()
    }

    // ----- TZ-aware conversions -----

    fun now(): Instant = Instant.now()

    fun todayInZone(zone: ZoneId): LocalDate = LocalDate.now(zone)

    fun nowTimeInZone(zone: ZoneId): LocalTime = LocalTime.now(zone)

    fun localDateOfInstant(instant: Instant, zone: ZoneId): LocalDate =
        instant.atZone(zone).toLocalDate()

    fun localTimeOfInstant(instant: Instant, zone: ZoneId): LocalTime =
        instant.atZone(zone).toLocalTime()

    fun toLocalDateTime(instant: Instant, zone: ZoneId): LocalDateTime =
        instant.atZone(zone).toLocalDateTime()

    fun toLocalDateInZone(instant: Instant, zone: ZoneId): LocalDate =
        instant.atZone(zone).toLocalDate()

    fun fromLocalDateTime(local: LocalDateTime, zone: ZoneId): Instant =
        local.atZone(zone).toInstant()

    fun toInstantInZone(date: LocalDate, time: LocalTime, zone: ZoneId): Instant =
        ZonedDateTime.of(date, time, zone).toInstant()
}
