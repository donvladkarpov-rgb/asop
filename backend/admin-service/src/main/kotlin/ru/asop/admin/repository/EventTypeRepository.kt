package ru.asop.admin.repository

import org.springframework.data.r2dbc.repository.R2dbcRepository
import org.springframework.stereotype.Repository
import ru.asop.admin.model.EventTypeEntity

@Repository
interface EventTypeRepository : R2dbcRepository<EventTypeEntity, String>
