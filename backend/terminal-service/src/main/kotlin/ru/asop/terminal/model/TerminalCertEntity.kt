package ru.asop.terminal.model

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant
import java.util.UUID

@Table("ASOP_TERMINAL_CERTS")
data class TerminalCertEntity(
    @Id
    val certId: UUID,
    val terminalId: UUID,
    val certSerial: String,
    val issuedAt: Instant,
    val expiresAt: Instant,
    val revokedAt: Instant? = null,
    val revocationReason: String? = null,
    val isCurrent: Boolean = true,
    val certData: String,
    val caChain: String? = null,
    val createdAt: Instant = Instant.now()
)