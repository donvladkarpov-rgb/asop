package ru.asop.fiscal.repository

import org.springframework.data.repository.reactive.ReactiveCrudRepository
import org.springframework.stereotype.Repository
import ru.asop.fiscal.model.FiscalReceiptEntity
import java.util.UUID

@Repository
interface FiscalReceiptRepository : ReactiveCrudRepository<FiscalReceiptEntity, UUID>
