package ru.asop.admin.repository

import org.springframework.data.r2dbc.repository.R2dbcRepository
import org.springframework.stereotype.Repository
import ru.asop.admin.model.RoleEntity
import java.util.UUID

@Repository
interface RoleRepository : R2dbcRepository<RoleEntity, UUID>
