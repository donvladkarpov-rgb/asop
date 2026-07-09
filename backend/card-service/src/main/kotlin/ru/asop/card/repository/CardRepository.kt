package ru.asop.card.repository

import org.springframework.data.repository.reactive.ReactiveCrudRepository
import org.springframework.stereotype.Repository
import ru.asop.card.model.CardEntity
import java.util.UUID

@Repository
interface CardRepository : ReactiveCrudRepository<CardEntity, UUID>
