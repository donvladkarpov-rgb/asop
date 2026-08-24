package ru.asop.admin.repository

import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import reactor.core.publisher.Flux
import ru.asop.admin.model.OrganizerEntity
import java.time.Instant
import java.util.UUID

/**
 * Repository для /delta запросов организаторов с region-filter через EXISTS subquery
 * (промпт 010). Размещён ОТДЕЛЬНО от [OrganizerRepository], т.к. custom @Query на Spring Data
 * R2DBC плохо работает с entity-mapping'ом: предпочитаем явный row mapping через DatabaseClient.
 *
 * Стратегия: SELECT с явным EXISTS через JOIN ASOP_ORGANIZER_TERRITORIES → ASOP_TERRITORIES,
 * если regionId IS NULL — глобально (full dump).
 */
@Repository
class OrganizerDeltaQuery(
    private val db: DatabaseClient
) {
    fun findByRegion(versionSince: Long?, includeDeleted: Boolean, limit: Int, regionId: UUID?): Flux<OrganizerEntity> {
        val sql = """
            SELECT o.organizer_id   AS "organizerId",
                   o.organizer_name AS "organizerName",
                   o.created_at     AS "createdAt",
                   o.updated_at     AS "updatedAt",
                   o.deleted_at     AS "deletedAt",
                   o.version        AS "version"
            FROM ASOP_ORGANIZERS o
            WHERE (:versionSince IS NULL OR o.version > :versionSince)
              AND (:includeDeleted = TRUE OR o.deleted_at IS NULL)
              AND (
                :regionId::uuid IS NULL
                OR EXISTS (
                  SELECT 1 FROM ASOP_ORGANIZER_TERRITORIES ot
                  JOIN ASOP_TERRITORIES t ON t.territory_id = ot.territory_id
                  WHERE ot.organizer_id = o.organizer_id
                    AND t.region_id = :regionId::uuid
                )
              )
            ORDER BY o.version ASC
            LIMIT :limit
        """.trimIndent()
        var spec: DatabaseClient.GenericExecuteSpec = db.sql(sql)
        if (versionSince != null) spec = spec.bind("versionSince", versionSince) else spec = spec.bindNull("versionSince", Long::class.javaObjectType)
        spec = spec.bind("includeDeleted", includeDeleted)
        if (regionId != null) spec = spec.bind("regionId", regionId.toString()) else spec = spec.bindNull("regionId", String::class.javaObjectType)
        spec = spec.bind("limit", limit)
        return spec
            .fetch()
            .all()
            .map { row ->
                OrganizerEntity(
                    organizerId = asUuid(row["organizerid"]),
                    organizerName = row["organizername"] as String,
                    createdAt = (row["createdat"] as? Instant) ?: Instant.now(),
                    updatedAt = (row["updatedat"] as? Instant) ?: Instant.now(),
                    deletedAt = row["deletedat"] as? Instant,
                    version = (row["version"] as? Number)?.toLong()
                )
            }
    }

    /**
     * Список организаторов региона для web-admin (глобальный фильтр): без delta-условий,
     * region-scope через ту же EXISTS-цепочку organizer_territories → territories.region_id.
     */
    fun listByRegion(regionId: UUID?): Flux<OrganizerEntity> {
        val sql = """
            SELECT o.organizer_id   AS "organizerId",
                   o.organizer_name AS "organizerName",
                   o.created_at     AS "createdAt",
                   o.updated_at     AS "updatedAt",
                   o.deleted_at     AS "deletedAt",
                   o.version        AS "version"
            FROM ASOP_ORGANIZERS o
            WHERE o.deleted_at IS NULL
              AND (
                :regionId::uuid IS NULL
                OR EXISTS (
                  SELECT 1 FROM ASOP_ORGANIZER_TERRITORIES ot
                  JOIN ASOP_TERRITORIES t ON t.territory_id = ot.territory_id
                  WHERE ot.organizer_id = o.organizer_id
                    AND t.region_id = :regionId::uuid
                )
              )
            ORDER BY o.organizer_name ASC
        """.trimIndent()
        var spec: DatabaseClient.GenericExecuteSpec = db.sql(sql)
        spec = if (regionId != null) spec.bind("regionId", regionId.toString()) else spec.bindNull("regionId", String::class.javaObjectType)
        return spec
            .fetch()
            .all()
            .map { row ->
                OrganizerEntity(
                    organizerId = asUuid(row["organizerid"]),
                    organizerName = row["organizername"] as String,
                    createdAt = (row["createdat"] as? Instant) ?: Instant.now(),
                    updatedAt = (row["updatedat"] as? Instant) ?: Instant.now(),
                    deletedAt = row["deletedat"] as? Instant,
                    version = (row["version"] as? Number)?.toLong()
                )
            }
    }

    private fun asUuid(v: Any?): UUID = when (v) {
        is UUID -> v
        is String -> UUID.fromString(v)
        else -> throw IllegalStateException("Cannot convert ${v?.javaClass} to UUID")
    }
}
