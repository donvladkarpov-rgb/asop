package ru.asop.route.entity

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import ru.asop.common.util.UuidUtils
import java.util.UUID

@Table("ASOP_PATH_SERVICES")
data class PathServiceEntity(
    @Id
    @Column("PATH_SERVICE_ID")
    val pathServiceId: UUID = UuidUtils.newId(),
    @Column("PATH_ID")
    val pathId: UUID,
    @Column("SERVICE_ID")
    val serviceId: UUID,
    @Column("CARRIER_ID")
    val carrierId: UUID? = null,
    @Column("VEHICLE_ID")
    val vehicleId: UUID? = null,
    @Column("TARIFF_TYPE_ID")
    val tariffTypeId: UUID? = null,
    @Column("PRICE")
    val price: Double,
    @Column("IS_ACTIVE")
    val isActive: Boolean? = null
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "id" to pathServiceId.toString(),
        "pathId" to pathId.toString(),
        "serviceId" to serviceId.toString(),
        "carrierId" to carrierId?.toString(),
        "vehicleId" to vehicleId?.toString(),
        "tariffTypeId" to tariffTypeId?.toString(),
        "price" to price,
        "isActive" to isActive
    )

    fun toDbMap(): Map<String, Any?> = mapOf(
        "path_service_id" to pathServiceId,
        "path_id" to pathId,
        "service_id" to serviceId,
        "carrier_id" to carrierId,
        "vehicle_id" to vehicleId,
        "tariff_type_id" to tariffTypeId,
        "price" to price,
        "is_active" to isActive
    )

    companion object {
        fun fromRequest(data: Map<String, String?>): PathServiceEntity = PathServiceEntity(
            pathServiceId = data["id"]?.let { UUID.fromString(it) } ?: UuidUtils.newId(),
            pathId = UUID.fromString(data["pathId"] ?: error("pathId is required")),
            serviceId = UUID.fromString(data["serviceId"] ?: error("serviceId is required")),
            carrierId = data["carrierId"]?.let { UUID.fromString(it) },
            vehicleId = data["vehicleId"]?.let { UUID.fromString(it) },
            tariffTypeId = data["tariffTypeId"]?.let { UUID.fromString(it) },
            price = data["price"]?.toDoubleOrNull() ?: error("price is required"),
            isActive = data["isActive"]?.toBoolean()
        )

        fun fromDbRow(row: Map<String, Any?>): PathServiceEntity = PathServiceEntity(
            pathServiceId = row["path_service_id"] as? UUID ?: error("path_service_id is required"),
            pathId = row["path_id"] as? UUID ?: error("path_id is required"),
            serviceId = row["service_id"] as? UUID ?: error("service_id is required"),
            carrierId = row["carrier_id"] as? UUID,
            vehicleId = row["vehicle_id"] as? UUID,
            tariffTypeId = row["tariff_type_id"] as? UUID,
            price = row["price"] as? Double ?: error("price is required"),
            isActive = row["is_active"] as? Boolean
        )
    }
}
