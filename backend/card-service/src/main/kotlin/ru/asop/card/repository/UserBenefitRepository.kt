package ru.asop.card.repository

import org.springframework.data.repository.reactive.ReactiveCrudRepository
import org.springframework.data.r2dbc.repository.Query
import org.springframework.stereotype.Repository
import reactor.core.publisher.Flux
import ru.asop.card.model.UserBenefitEntity
import java.util.UUID

@Repository
interface UserBenefitRepository : ReactiveCrudRepository<UserBenefitEntity, UUID> {

    @Query("""
        SELECT * FROM ASOP_USER_BENEFITS
        WHERE (:userIdsInStr IS NULL OR USER_ID = ANY(string_to_array(:userIdsInStr, ',')::uuid[]))
          AND (:versionSince IS NULL OR VERSION > :versionSince)
          AND (:includeDeleted = TRUE OR DELETED_AT IS NULL)
        ORDER BY VERSION ASC
        LIMIT :limit
    """)
    fun findDelta(userIdsInStr: String?, versionSince: Long?, includeDeleted: Boolean, limit: Int): Flux<UserBenefitEntity>
}
