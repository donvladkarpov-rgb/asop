package ru.asop.terminal.model

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant
import java.util.UUID

/**
 * Платёжный терминал дистрибьютора карт (ASOP_DISTRIBUTOR_TERMINALS).
 * CRUD через gateway sync-proxy (distributor-terminals → terminal-service:8084).
 */
@Table("ASOP_DISTRIBUTOR_TERMINALS")
data class DistributorTerminalEntity(
    @Id
    val distributorTerminalId: UUID,
    val cardsDistributorId: UUID,
    val contractId: UUID? = null,
    val terminalNumber: String,
    val terminalSerial: String,
    val terminalModel: String? = null,
    val paymentProviderId: String,
    val status: String = "WAREHOUSE",
    val molUserId: UUID? = null,
    val profileId: UUID? = null,
    val softwareVersionId: UUID? = null,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now()
)