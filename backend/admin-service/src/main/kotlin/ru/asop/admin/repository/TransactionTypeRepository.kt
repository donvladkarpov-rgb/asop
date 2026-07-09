package ru.asop.admin.repository

import org.springframework.data.r2dbc.repository.R2dbcRepository
import org.springframework.stereotype.Repository
import ru.asop.admin.model.TransactionTypeEntity
import java.util.UUID

@Repository
interface TransactionTypeRepository : R2dbcRepository<TransactionTypeEntity, UUID>
