package ru.asop.route.entity

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import ru.asop.common.util.UuidUtils
import java.util.UUID

@Table("ASOP_VEHICLES")
data class VehicleEntity(
    @Id
    @Column("VEHICLE_ID")
    val vehicleId: UUID = UuidUtils.newId(),
    @Column("CARRIER_ID")
    val carrierId: UUID? = null,
    @Column("VEHICLE_TYPE_ID")
    val vehicleTypeId: UUID,
    @Column("VEHICLE_MODEL_ID")
    val vehicleModelId: UUID,
    @Column("VEHICLE_NUMBER")
    val vehicleNumber: String,
    @Column("VEHICLE_NAME")
    val vehicleName: String
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "id" to vehicleId.toString(),
        "carrierId" to carrierId?.toString(),
        "vehicleTypeId" to vehicleTypeId.toString(),
        "vehicleModelId" to vehicleModelId.toString(),
        "vehicleNumber" to vehicleNumber,
        "vehicleName" to vehicleName
    )

    fun toDbMap(): Map<String, Any?> = mapOf(
        "vehicle_id" to vehicleId,
        "carrier_id" to carrierId,
        "vehicle_type_id" to vehicleTypeId,
        "vehicle_model_id" to vehicleModelId,
        "vehicle_number" to vehicleNumber,
        "vehicle_name" to vehicleName
    )

    companion object {
        fun fromRequest(data: Map<String, String?>): VehicleEntity = VehicleEntity(
            vehicleId = data["id"]?.let { UUID.fromString(it) } ?: UuidUtils.newId(),
            carrierId = data["carrierId"]?.let { UUID.fromString(it) },
            vehicleTypeId = UUID.fromString(data["vehicleTypeId"] ?: error("vehicleTypeId is required")),
            vehicleModelId = UUID.fromString(data["vehicleModelId"] ?: error("vehicleModelId is required")),
            vehicleNumber = data["vehicleNumber"] ?: error("vehicleNumber is required"),
            vehicleName = data["vehicleName"] ?: error("vehicleName is required")
        )

        fun fromDbRow(row: Map<String, Any?>): VehicleEntity = VehicleEntity(
            vehicleId = row["vehicle_id"] as? UUID ?: error("vehicle_id is required"),
            carrierId = row["carrier_id"] as? UUID,
            vehicleTypeId = row["vehicle_type_id"] as? UUID ?: error("vehicle_type_id is required"),
            vehicleModelId = row["vehicle_model_id"] as? UUID ?: error("vehicle_model_id is required"),
            vehicleNumber = row["vehicle_number"] as? String ?: error("vehicle_number is required"),
            vehicleName = row["vehicle_name"] as? String ?: error("vehicle_name is required")
        )
    }
}
