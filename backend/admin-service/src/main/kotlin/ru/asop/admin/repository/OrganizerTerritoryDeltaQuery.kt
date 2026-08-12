package ru.asop.admin.repository

import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import reactor.core.publisher.Flux
import ru.asop.admin.model.OrganizerTerritoryEntity
import java.time.Instant
import java.util.UUID

/**
 * Repository для /delta организатор↔территория с JOIN на ASOP_TERRITORIES.region_id (промпт 010).
 * Кастом @Query над ReactiveCrudRepository плохо mapping'ится, поэтому явный row mapping.
 */
@Repository
class OrganizerTerritoryDeltaQuery(
    private val db: DatabaseClient
) {
    fun findByRegion(versionSince: Long?, includeDeleted: Boolean, limit: Int, regionId: UUID?): Flux<OrganizerTerritoryEntity> {
        val sql = """
            SELECT ot.organizer_id AS "organizerId",
                   ot.territory_id AS "territoryId",
                   ot.created_at   AS "createdAt",
                   ot.updated_at   AS "updatedAt",
                   ot.deleted_at   AS "deletedAt",
                   ot.version      AS "version"
            FROM ASOP_ORGANIZER_TERRITORIES ot
            JOIN ASOP_TERRITORIES t ON t.territory_id = ot.territory_id
            WHERE (:versionSince IS NULL OR ot.version > :versionSince)
              AND (:includeDeleted = TRUE OR ot.deleted_at IS NULL)
              AND (:regionId::uuid IS NULL OR t.region_id = :regionId::uuid)
            ORDER BY ot.version ASC
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
                OrganizerTerritoryEntity(
                    organizerId = asUuid(row["organizerid"]),
                    territoryId = asUuid(row["territoryid"]),
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
