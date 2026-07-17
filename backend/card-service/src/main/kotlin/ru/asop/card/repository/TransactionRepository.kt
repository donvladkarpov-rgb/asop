package ru.asop.card.repository

import org.springframework.data.repository.reactive.ReactiveCrudRepository
import org.springframework.stereotype.Repository
import ru.asop.card.model.TransactionEntity
import java.util.UUID

@Repository
interface TransactionRepository : ReactiveCrudRepository<TransactionEntity, UUID>
