package ru.asop.payment.acquirer

/**
 * Детерминированные сценарии мока эквайринга (см. prompt_016 §3.1.9).
 *
 * Выбор по «копейкам» суммы (`x.01` → DECLINE, `x.02` → TIMEOUT, `x.03` → DEFER,
 * `x.04` → REAUTH_REQUIRED), либо явный override через `POST /api/v1/payments/mock/scenario`.
 */
enum class AcquirerScenario {
    APPROVE,
    DECLINE,
    TIMEOUT,
    DEFER,
    REAUTH_REQUIRED,
    DUPLICATE;

    companion object {
        /** Правило по сумме: копейки 01..04, иначе APPROVE. */
        fun byAmountMinor(minorUnits: Int): AcquirerScenario = when (minorUnits) {
            1 -> DECLINE
            2 -> TIMEOUT
            3 -> DEFER
            4 -> REAUTH_REQUIRED
            5 -> DUPLICATE
            else -> APPROVE
        }
    }
}
