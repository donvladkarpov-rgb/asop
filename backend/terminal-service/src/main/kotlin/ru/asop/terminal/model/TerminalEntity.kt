package ru.asop.terminal.model

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant
import java.util.UUID

@Table("ASOP_TERMINALS")
data class TerminalEntity(
    @Id
    val terminalId: UUID,
    val carrierId: UUID?,
    val terminalNumber: String?,
    val terminalSerial: String,
    val terminalModel: String?,
    val status: String = "WAREHOUSE",
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now()
)
