package ru.asop.terminal.worker

object EventTypes {
    const val SESSION_OPEN = "SESSION_OPEN"
    const val SESSION_CLOSE = "SESSION_CLOSE"
    const val TRANSACTION_COMPLETE = "TRANSACTION_COMPLETE"
    const val CARD_REGISTER = "CARD_REGISTER"
    const val CARD_BLOCK = "CARD_BLOCK"
    const val DEBT_CREATE = "DEBT_CREATE"
    const val DEBT_RECOVER = "DEBT_RECOVER"
    const val FISCAL_RECEIPT = "FISCAL_RECEIPT"
    const val AUDIT_TASK = "AUDIT_TASK"
    const val GPS_POSITION = "GPS_POSITION"
}
