package ru.asop.route.entity

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import ru.asop.common.util.UuidUtils
import java.time.Instant
import java.util.UUID

@Table("ASOP_PATH_DISCOUNTS")
data class PathDiscountEntity(
    @Id
    @Column("PATH_DISCOUNT_ID")
    val pathDiscountId: UUID = UuidUtils.newId(),
    @Column("PATH_ID")
    val pathId: UUID,
    @Column("CARRIER_ID")
    val carrierId: UUID? = null,
    @Column("VEHICLE_ID")
    val vehicleId: UUID? = null,
    @Column("TARIFF_TYPE_ID")
    val tariffTypeId: UUID? = null,
    @Column("DISCOUNT_NAME")
    val discountName: String,
    @Column("DISCOUNT_TYPE")
    val discountType: String,
    @Column("DISCOUNT_VALUE")
    val discountValue: Double,
    @Column("VALID_FROM")
    val validFrom: Instant,
    @Column("VALID_UNTIL")
    val validUntil: Instant? = null,
    @Column("IS_ACTIVE")
    val isActive: Boolean? = null
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "id" to pathDiscountId.toString(),
        "pathId" to pathId.toString(),
        "carrierId" to carrierId?.toString(),
        "vehicleId" to vehicleId?.toString(),
        "tariffTypeId" to tariffTypeId?.toString(),
        "discountName" to discountName,
        "discountType" to discountType,
        "discountValue" to discountValue,
        "validFrom" to validFrom.toString(),
        "validUntil" to validUntil?.toString(),
        "isActive" to isActive
    )

    fun toDbMap(): Map<String, Any?> = mapOf(
        "path_discount_id" to pathDiscountId,
        "path_id" to pathId,
        "carrier_id" to carrierId,
        "vehicle_id" to vehicleId,
        "tariff_type_id" to tariffTypeId,
        "discount_name" to discountName,
        "discount_type" to discountType,
        "discount_value" to discountValue,
        "valid_from" to validFrom,
        "valid_until" to validUntil,
        "is_active" to isActive
    )

    companion object {
        fun fromRequest(data: Map<String, String?>): PathDiscountEntity = PathDiscountEntity(
            pathDiscountId = data["id"]?.let { UUID.fromString(it) } ?: UuidUtils.newId(),
            pathId = UUID.fromString(data["pathId"] ?: error("pathId is required")),
            carrierId = data["carrierId"]?.takeIf { it.isNotBlank() }?.let { UUID.fromString(it) },
            vehicleId = data["vehicleId"]?.takeIf { it.isNotBlank() }?.let { UUID.fromString(it) },
            tariffTypeId = data["tariffTypeId"]?.takeIf { it.isNotBlank() }?.let { UUID.fromString(it) },
            discountName = data["discountName"] ?: error("discountName is required"),
            discountType = data["discountType"] ?: error("discountType is required"),
            discountValue = data["discountValue"]?.toDoubleOrNull() ?: error("discountValue is required"),
            validFrom = data["validFrom"]?.let { Instant.parse(it) } ?: error("validFrom is required"),
            validUntil = data["validUntil"]?.let { Instant.parse(it) },
            isActive = data["isActive"]?.toBoolean()
        )

        fun fromDbRow(row: Map<String, Any?>): PathDiscountEntity = PathDiscountEntity(
            pathDiscountId = row["path_discount_id"] as? UUID ?: error("path_discount_id is required"),
            pathId = row["path_id"] as? UUID ?: error("path_id is required"),
            carrierId = row["carrier_id"] as? UUID,
            vehicleId = row["vehicle_id"] as? UUID,
            tariffTypeId = row["tariff_type_id"] as? UUID,
            discountName = row["discount_name"] as? String ?: error("discount_name is required"),
            discountType = row["discount_type"] as? String ?: error("discount_type is required"),
            discountValue = row["discount_value"] as? Double ?: error("discount_value is required"),
            validFrom = row["valid_from"] as? Instant ?: error("valid_from is required"),
            validUntil = row["valid_until"] as? Instant,
            isActive = row["is_active"] as? Boolean
        )
    }
}
