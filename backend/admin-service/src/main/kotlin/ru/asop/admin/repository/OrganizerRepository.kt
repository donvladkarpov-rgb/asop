package ru.asop.admin.repository

import org.springframework.data.r2dbc.repository.R2dbcRepository
import org.springframework.stereotype.Repository
import ru.asop.admin.model.OrganizerEntity
import java.util.UUID

@Repository
interface OrganizerRepository : R2dbcRepository<OrganizerEntity, UUID>
