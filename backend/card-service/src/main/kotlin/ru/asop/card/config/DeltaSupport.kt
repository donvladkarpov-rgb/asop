package ru.asop.card.config

import org.springframework.data.domain.Sort
import org.springframework.data.relational.core.query.Criteria
import org.springframework.data.relational.core.query.Query
import java.time.Instant

object DeltaSupport {

    fun query(updatedAtSince: Instant?, includeDeleted: Boolean, limit: Int, extra: Criteria? = null): Query {
        val list = mutableListOf<Criteria>()
        updatedAtSince?.let { list += Criteria.where("updated_at").greaterThan(it) }
        if (!includeDeleted) list += Criteria.where("deleted_at").isNull()
        extra?.let { list += it }
        val criteria = Criteria.from(list)
        return Query.query(criteria)
            .sort(Sort.by(Sort.Direction.ASC, "updated_at"))
            .limit(limit)
    }
}
