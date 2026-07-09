package ru.asop.admin.repository

import org.springframework.data.r2dbc.repository.R2dbcRepository
import org.springframework.stereotype.Repository
import ru.asop.admin.model.TransactionResultEntity
import java.util.UUID

@Repository
interface TransactionResultRepository : R2dbcRepository<TransactionResultEntity, UUID>
