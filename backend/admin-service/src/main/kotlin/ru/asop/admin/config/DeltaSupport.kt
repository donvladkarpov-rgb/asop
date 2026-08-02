package ru.asop.admin.config

import org.springframework.data.domain.Sort
import org.springframework.data.relational.core.query.Criteria
import org.springframework.data.relational.core.query.Query

/**
 * Построение R2DBC Query для /delta эндпоинтов дельта-синхронизации.
 * Фильтр: VERSION > versionSince, soft-deleted скрываются если !includeDeleted.
 */
object DeltaSupport {

    fun query(versionSince: Long?, includeDeleted: Boolean, limit: Int, extra: Criteria? = null): Query {
        val list = mutableListOf<Criteria>()
        versionSince?.let { list += Criteria.where("version").greaterThan(it) }
        if (!includeDeleted) list += Criteria.where("deleted_at").isNull()
        extra?.let { list += it }
        val criteria = Criteria.from(list)
        return Query.query(criteria)
            .sort(Sort.by(Sort.Direction.ASC, "version"))
            .limit(limit)
    }
}
