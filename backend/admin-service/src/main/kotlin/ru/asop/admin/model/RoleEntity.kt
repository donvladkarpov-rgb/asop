package ru.asop.admin.model

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.util.UUID

@Table("ASOP_ROLES")
data class RoleEntity(
    @Id
    val roleId: UUID,
    val roleName: String
)
