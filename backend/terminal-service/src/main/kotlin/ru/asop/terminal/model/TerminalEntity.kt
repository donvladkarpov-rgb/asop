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
    val terminalNumber: String? = null,
    val terminalSerial: String,
    val terminalModel: String? = null,
    val status: String = "WAREHOUSE",
    val timezone: String? = null,
    val profileId: UUID? = null,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now()
)
