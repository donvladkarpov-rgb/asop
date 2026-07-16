package ru.asop.session.repository

import org.springframework.data.repository.reactive.ReactiveCrudRepository
import org.springframework.stereotype.Repository
import ru.asop.session.model.SessionEntity
import java.util.UUID

@Repository
interface SessionRepository : ReactiveCrudRepository<SessionEntity, UUID>
