package ru.asop.terminal.model

import java.util.UUID

/**
 * Промпт 012: terminal event watermark response.
 */
data class TerminalEventWatermark(
    val terminalId: UUID,
    val lastSeq: Long,
    val pendingSeqCount: Long,
    val lastEventAt: String?   // ISO timestamp or null when never applied
)
