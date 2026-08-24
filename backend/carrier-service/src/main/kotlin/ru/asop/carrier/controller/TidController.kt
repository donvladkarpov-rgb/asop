package ru.asop.carrier.controller

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import ru.asop.api.tid.controller.TidApi
import ru.asop.api.tid.dto.request.TidCreateRequest
import ru.asop.api.tid.dto.request.TidUpdateRequest
import ru.asop.api.tid.dto.response.TidResponse
import ru.asop.carrier.service.TidService
import java.util.UUID

@RestController
class TidController(
    private val tidService: TidService,
    private val db: DatabaseClient
) : TidApi {

    override fun listTids(carrierId: UUID?, regionId: UUID?): Flux<TidResponse> =
        tidService.list(carrierId, regionId)

    override fun getTid(id: UUID): Mono<ResponseEntity<TidResponse>> =
        tidService.getById(id)
            .map { ResponseEntity.ok(it) }
            .defaultIfEmpty(ResponseEntity.notFound().build())

    override fun createTid(request: TidCreateRequest): Mono<ResponseEntity<TidResponse>> =
        tidService.create(request)
            .map { ResponseEntity.status(HttpStatus.CREATED).body(it) }

    override fun updateTid(id: UUID, request: TidUpdateRequest): Mono<ResponseEntity<TidResponse>> =
        tidService.update(id, request)
            .map { ResponseEntity.ok(it) }
            .defaultIfEmpty(ResponseEntity.notFound().build())

    override fun deleteTid(id: UUID): Mono<ResponseEntity<Void>> =
        tidService.delete(id)
            .thenReturn(ResponseEntity.noContent().build<Void>())

    /**
     * Дельта для терминалов: TID + contractId + carrierId (из договора) + isValid
     * (актуален ли банковский договор: CONTRACTOR_TYPE='BANK' + STATUS='ACTIVE' +
     * даты действия). Договоры на терминал НЕ синкаются — только их тиды с признаком
     * актуальности (TID-picker при старте рейса показывает только isValid=true).
     * Изменение статуса/дат договора bump'ает VERSION его тидов (триггер
     * trg_contracts_bump_tids) — дельта привезёт обновлённый признак.
     */
    @GetMapping("/delta")
    fun listDelta(
        @RequestParam(required = false) versionSince: Long?,
        @RequestParam(required = false) includeDeleted: Boolean,
        @RequestParam(required = false) carrierId: UUID?,
        @RequestParam(required = false, defaultValue = "10000") limit: Int
    ): Flux<Map<String, Any?>> {
        val conditions = mutableListOf<String>()
        if (versionSince != null) conditions += "t.version > :since"
        if (!includeDeleted) conditions += "t.deleted_at IS NULL"
        if (carrierId != null) conditions += "c.carrier_id = :carrierId"
        val where = if (conditions.isEmpty()) "" else " WHERE ${conditions.joinToString(" AND ")}"
        val sql = """
            SELECT t.TID_ID          AS "tidId",
                   t.CONTRACT_ID     AS "contractId",
                   c.CARRIER_ID      AS "carrierId",
                   t.TERMINAL_ID     AS "terminalId",
                   t.TID_VALUE       AS "tidValue",
                   t.STATUS          AS "status",
                   t.ASSIGNED_AT     AS "assignedAt",
                   t.UNASSIGNED_AT   AS "unassignedAt",
                   t.CREATED_AT      AS "createdAt",
                   t.UPDATED_AT      AS "updatedAt",
                   t.DELETED_AT      AS "deletedAt",
                   t.VERSION         AS "version",
                   (c.CONTRACTOR_TYPE = 'BANK'
                    AND c.STATUS = 'ACTIVE'
                    AND c.START_DATE <= CURRENT_DATE
                    AND (c.END_DATE IS NULL OR c.END_DATE >= CURRENT_DATE)) AS "isValid"
            FROM ASOP_TIDS t
            JOIN ASOP_CONTRACTS c ON c.CONTRACT_ID = t.CONTRACT_ID
            $where
            ORDER BY t.version ASC
            LIMIT :limit
        """.trimIndent()
        var spec = db.sql(sql)
        if (versionSince != null) spec = spec.bind("since", versionSince)
        if (carrierId != null) spec = spec.bind("carrierId", carrierId)
        return spec.bind("limit", limit).fetch().all()
    }
}
