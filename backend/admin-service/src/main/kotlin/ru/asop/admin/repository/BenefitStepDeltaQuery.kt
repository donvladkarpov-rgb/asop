package ru.asop.admin.repository

import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import reactor.core.publisher.Flux
import ru.asop.admin.model.BenefitStepEntity
import java.time.Instant
import java.util.UUID

/**
 * Repository для /delta benefit-step с region-filter через JOIN ASOP_BENEFITS.region_id (промпт 010).
 */
@Repository
class BenefitStepDeltaQuery(
    private val db: DatabaseClient
) {
    fun findByRegion(versionSince: Long?, includeDeleted: Boolean, limit: Int, regionId: UUID?): Flux<BenefitStepEntity> {
        val sql = """
            SELECT bs.step_id             AS "stepId",
                   bs.benefit_id          AS "benefitId",
                   bs.step_order          AS "stepOrder",
                   bs.trip_threshold_from AS "tripThresholdFrom",
                   bs.trip_threshold_to   AS "tripThresholdTo",
                   bs.discount_share      AS "discountShare",
                   bs.period_type         AS "periodType",
                   bs.created_at          AS "createdAt",
                   bs.updated_at          AS "updatedAt",
                   bs.deleted_at          AS "deletedAt",
                   bs.version             AS "version"
            FROM ASOP_BENEFIT_STEPS bs
            JOIN ASOP_BENEFITS b ON b.benefit_id = bs.benefit_id
            WHERE (:versionSince IS NULL OR bs.version > :versionSince)
              AND (:includeDeleted = TRUE OR bs.deleted_at IS NULL)
              AND (:regionId::uuid IS NULL OR b.region_id = :regionId::uuid)
            ORDER BY bs.version ASC
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
                BenefitStepEntity(
                    stepId = asUuid(row["stepid"]),
                    benefitId = asUuid(row["benefitid"]),
                    stepOrder = (row["steporder"] as? Number)?.toInt() ?: 0,
                    tripThresholdFrom = (row["tripthresholdfrom"] as? Number)?.toInt() ?: 0,
                    tripThresholdTo = (row["tripthresholdto"] as? Number)?.toInt(),
                    discountShare = ((row["discountshare"] as? Number)?.toString() ?: "0").toBigDecimal(),
                    periodType = row["periodtype"] as? String ?: "MONTHLY",
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
