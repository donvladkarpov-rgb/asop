package ru.asop.common.kafka

/**
 * Константы Kafka-топиков.
 * Формат: asop.{domain}.{commands|events}
 */
object KafkaTopic {
    // Carrier
    const val CARRIER_COMMANDS = "asop.carrier.commands"
    const val CARRIER_EVENTS = "asop.carrier.events"

    // Terminal
    const val TERMINAL_COMMANDS = "asop.terminal.commands"
    const val TERMINAL_EVENTS = "asop.terminal.events"

    // User
    const val USER_COMMANDS = "asop.user.commands"
    const val USER_EVENTS = "asop.user.events"

    // Card
    const val CARD_COMMANDS = "asop.card.commands"
    const val CARD_EVENTS = "asop.card.events"

    // Session
    const val SESSION_COMMANDS = "asop.session.commands"
    const val SESSION_EVENTS = "asop.session.events"

    // Transaction
    const val TRANSACTION_COMMANDS = "asop.transaction.commands"
    const val TRANSACTION_EVENTS = "asop.transaction.events"

    // Debt
    const val DEBT_COMMANDS = "asop.debt.commands"
    const val DEBT_EVENTS = "asop.debt.events"

    // Fiscal
    const val FISCAL_COMMANDS = "asop.fiscal.commands"
    const val FISCAL_EVENTS = "asop.fiscal.events"

    // Audit
    const val AUDIT_COMMANDS = "asop.audit.commands"
    const val AUDIT_EVENTS = "asop.audit.events"
}