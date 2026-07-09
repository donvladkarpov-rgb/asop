package ru.asop.carrier.repository

import org.springframework.data.repository.reactive.ReactiveCrudRepository
import org.springframework.stereotype.Repository
import ru.asop.carrier.model.CarrierEntity
import java.util.UUID

@Repository
interface CarrierRepository : ReactiveCrudRepository<CarrierEntity, UUID>
