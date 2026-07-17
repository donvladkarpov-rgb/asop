package ru.asop.session.repository

import org.springframework.data.repository.reactive.ReactiveCrudRepository
import org.springframework.stereotype.Repository
import ru.asop.session.model.GpsTrackingEntity
import java.util.UUID

@Repository
interface GpsTrackingRepository : ReactiveCrudRepository<GpsTrackingEntity, UUID>
