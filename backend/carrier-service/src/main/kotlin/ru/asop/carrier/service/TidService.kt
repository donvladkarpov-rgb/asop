package ru.asop.carrier.service

import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import org.springframework.stereotype.Service
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import ru.asop.api.tid.dto.request.TidCreateRequest
import ru.asop.api.tid.dto.request.TidUpdateRequest
import ru.asop.api.tid.dto.response.TidResponse
import ru.asop.carrier.model.TidEntity
import ru.asop.carrier.repository.TidRepository
import ru.asop.common.util.UuidUtils
import java.time.Instant
import java.util.UUID

/**
 * TID-пулы банковского эквайринга. TID привязан к ДОГОВОРУ перевозчика с банком
 * (ASOP_CONTRACTS.CONTRACTOR_TYPE='BANK'), а не напрямую к перевозчику:
 * договор задаёт актуальность (STATUS + даты действия) — протухший договор
 * автоматически деактивирует его TID-ы (isValid=false в дельте для терминалов).
 */
@Service
class TidService(
    private val template: R2dbcEntityTemplate,
    private val tidRepository: TidRepository,
    private val db: DatabaseClient
) {

    /** Договор банковского эквайринга: контрагент, перевозчик, актуален ли сейчас. */
    data class BankContract(
        val contractId: UUID,
        val carrierId: UUID,
        val contractNumber: String,
        val contractorType: String,
        val isValid: Boolean
    )

    fun findBankContract(contractId: UUID): Mono<BankContract?> =
        db.sql(
            """
            SELECT c.CONTRACT_ID, c.CARRIER_ID, c.CONTRACT_NUMBER, c.CONTRACTOR_TYPE,
                   (c.CONTRACTOR_TYPE = 'BANK'
                    AND c.STATUS = 'ACTIVE'
                    AND c.START_DATE <= CURRENT_DATE
                    AND (c.END_DATE IS NULL OR c.END_DATE >= CURRENT_DATE)) AS IS_VALID
            FROM ASOP_CONTRACTS c
            WHERE c.CONTRACT_ID = :contractId AND c.DELETED_AT IS NULL
            LIMIT 1
            """.trimIndent()
        )
            .bind("contractId", contractId)
            .fetch()
            .one()
            .map { row ->
                BankContract(
                    contractId = row["CONTRACT_ID"] as UUID,
                    carrierId = row["CARRIER_ID"] as UUID,
                    contractNumber = row["CONTRACT_NUMBER"] as String,
                    contractorType = row["CONTRACTOR_TYPE"] as String,
                    isValid = (row["IS_VALID"] as Boolean?) == true
                )
            }

    fun list(carrierId: UUID?, regionId: UUID?): Flux<TidResponse> {
        val conditions = mutableListOf<String>("t.DELETED_AT IS NULL")
        if (carrierId != null) conditions += "c.CARRIER_ID = :carrierId"
        if (regionId != null) conditions += "c.CARRIER_ID IN (SELECT carrier_id FROM ASOP_CARRIERS WHERE region_id = :regionId AND deleted_at IS NULL)"
        val sql = """
            SELECT t.TID_ID, t.CONTRACT_ID, c.CARRIER_ID, t.TERMINAL_ID, t.TID_VALUE, t.STATUS,
                   t.ASSIGNED_AT, t.UNASSIGNED_AT, t.CREATED_AT, t.UPDATED_AT
            FROM ASOP_TIDS t
            JOIN ASOP_CONTRACTS c ON c.CONTRACT_ID = t.CONTRACT_ID
            WHERE ${conditions.joinToString(" AND ")}
            ORDER BY t.TID_VALUE ASC
        """.trimIndent()
        var spec = db.sql(sql)
        if (carrierId != null) spec = spec.bind("carrierId", carrierId)
        if (regionId != null) spec = spec.bind("regionId", regionId)
        return spec.fetch().all().map { row -> rowToResponse(row) }
    }

    fun getById(id: UUID): Mono<TidResponse> =
        db.sql(
            """
            SELECT t.TID_ID, t.CONTRACT_ID, c.CARRIER_ID, t.TERMINAL_ID, t.TID_VALUE, t.STATUS,
                   t.ASSIGNED_AT, t.UNASSIGNED_AT, t.CREATED_AT, t.UPDATED_AT
            FROM ASOP_TIDS t
            JOIN ASOP_CONTRACTS c ON c.CONTRACT_ID = t.CONTRACT_ID
            WHERE t.TID_ID = :id AND t.DELETED_AT IS NULL
            LIMIT 1
            """.trimIndent()
        )
            .bind("id", id)
            .fetch()
            .one()
            .map { rowToResponse(it) }

    fun create(request: TidCreateRequest): Mono<TidResponse> {
        return findBankContract(request.contractId)
            .flatMap<BankContract> { contract -> Mono.justOrEmpty(contract) }
            .switchIfEmpty(Mono.error(IllegalArgumentException(
                "Договор ${request.contractId} не найден")))
            .flatMap { contract ->
                when {
                    contract.contractorType != "BANK" -> Mono.error(IllegalArgumentException(
                        "TID можно привязать только к банковскому договору (CONTRACTOR_TYPE='BANK'), а это '${contract.contractorType}'"))
                    !contract.isValid -> Mono.error(IllegalArgumentException(
                        "Договор '${contract.contractNumber}' не актуален (статус/даты действия) — TID вешать нельзя"))
                    else -> {
                        val now = Instant.now()
                        val entity = TidEntity(
                            tidId = UuidUtils.newId(),
                            contractId = request.contractId,
                            tidValue = request.tidValue,
                            status = "UNUSED",
                            createdAt = now,
                            updatedAt = now
                        )
                        template.insert(entity).then(getById(entity.tidId))
                    }
                }
            }
    }

    fun update(id: UUID, request: TidUpdateRequest): Mono<TidResponse> {
        // Смена договора: новый должен быть актуальным BANK-договором.
        val newContractId = request.contractId
        val contractCheck: Mono<Void> = if (newContractId != null) {
            findBankContract(newContractId)
                .flatMap<BankContract> { Mono.justOrEmpty(it) }
                .switchIfEmpty(Mono.error(IllegalArgumentException("Договор $newContractId не найден")))
                .flatMap { contract ->
                    when {
                        contract.contractorType != "BANK" -> Mono.error(IllegalArgumentException(
                            "TID можно привязать только к банковскому договору, а это '${contract.contractorType}'"))
                        !contract.isValid -> Mono.error(IllegalArgumentException(
                            "Договор '${contract.contractNumber}' не актуален"))
                        else -> Mono.empty()
                    }
                }
        } else {
            Mono.empty()
        }
        return contractCheck.then(tidRepository.findById(id))
            .flatMap { existing ->
                val now = Instant.now()
                val newStatus = request.status ?: existing.status
                val assignedAt = when {
                    request.status == "ASSIGNED" && existing.status != "ASSIGNED" -> now
                    else -> existing.assignedAt
                }
                val unassignedAt = when {
                    request.status != null && request.status != "ASSIGNED" && existing.status == "ASSIGNED" -> now
                    request.terminalId == null && existing.terminalId != null -> now
                    else -> existing.unassignedAt
                }
                val updated = existing.copy(
                    contractId = request.contractId ?: existing.contractId,
                    tidValue = request.tidValue ?: existing.tidValue,
                    status = newStatus,
                    terminalId = request.terminalId,
                    assignedAt = assignedAt,
                    unassignedAt = unassignedAt,
                    updatedAt = now
                )
                tidRepository.save(updated).then(getById(id))
            }
    }

    fun delete(id: UUID): Mono<Void> =
        tidRepository.deleteById(id).then()

    private fun rowToResponse(row: Map<String, Any?>): TidResponse = TidResponse(
        id = row["TID_ID"] as UUID,
        contractId = row["CONTRACT_ID"] as UUID,
        carrierId = row["CARRIER_ID"] as UUID,
        terminalId = row["TERMINAL_ID"] as? UUID,
        tidValue = row["TID_VALUE"] as String,
        status = row["STATUS"] as? String ?: "UNUSED",
        assignedAt = row["ASSIGNED_AT"] as? Instant,
        unassignedAt = row["UNASSIGNED_AT"] as? Instant,
        createdAt = row["CREATED_AT"] as? Instant ?: Instant.now(),
        updatedAt = row["UPDATED_AT"] as? Instant ?: Instant.now()
    )
}
