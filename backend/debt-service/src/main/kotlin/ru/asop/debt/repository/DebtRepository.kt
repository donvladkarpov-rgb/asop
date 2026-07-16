package ru.asop.debt.repository

import org.springframework.data.repository.reactive.ReactiveCrudRepository
import org.springframework.stereotype.Repository
import ru.asop.debt.model.DebtEntity
import java.util.UUID

@Repository
interface DebtRepository : ReactiveCrudRepository<DebtEntity, UUID>
