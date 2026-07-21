package ru.asop.carrier.repository

import org.springframework.data.repository.reactive.ReactiveCrudRepository
import org.springframework.stereotype.Repository
import ru.asop.carrier.model.CardsDistributorEntity
import java.util.UUID

@Repository
interface CardsDistributorRepository : ReactiveCrudRepository<CardsDistributorEntity, UUID>
