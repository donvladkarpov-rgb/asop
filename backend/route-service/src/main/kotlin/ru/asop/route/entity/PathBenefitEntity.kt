package ru.asop.route.entity

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import ru.asop.common.util.UuidUtils
import java.util.UUID

@Table("ASOP_PATH_BENEFITS")
data class PathBenefitEntity(
    @Id
    @Column("PATH_BENEFIT_ID")
    val pathBenefitId: UUID = UuidUtils.newId(),
    @Column("PATH_ID")
    val pathId: UUID,
    @Column("BENEFIT_ID")
    val benefitId: UUID
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "id" to pathBenefitId.toString(),
        "pathId" to pathId.toString(),
        "benefitId" to benefitId.toString()
    )

    fun toDbMap(): Map<String, Any?> = mapOf(
        "path_benefit_id" to pathBenefitId,
        "path_id" to pathId,
        "benefit_id" to benefitId
    )

    companion object {
        fun fromRequest(data: Map<String, String?>): PathBenefitEntity = PathBenefitEntity(
            pathBenefitId = data["id"]?.let { UUID.fromString(it) } ?: UuidUtils.newId(),
            pathId = UUID.fromString(data["pathId"] ?: error("pathId is required")),
            benefitId = UUID.fromString(data["benefitId"] ?: error("benefitId is required"))
        )

        fun fromDbRow(row: Map<String, Any?>): PathBenefitEntity = PathBenefitEntity(
            pathBenefitId = row["path_benefit_id"] as? UUID ?: error("path_benefit_id is required"),
            pathId = row["path_id"] as? UUID ?: error("path_id is required"),
            benefitId = row["benefit_id"] as? UUID ?: error("benefit_id is required")
        )
    }
}
