package ru.asop.distributor.core

/**
 * Источник ключевого материала (ASOP_KEYS) для работы с картами.
 *
 * В терминале ключи приходят через delta-sync в `terminal_keys` (перешифрованные
 * TerminalKeyCryptor'ом). Для android-distributor до подключения серверного канала —
 * статический dev-набор (24-байтный 3K3DES dev-ключ `000102...171617`).
 *
 * TODO(Phase 4): заменить на серверный контур (своя cert-sign идентичность + pull `asop_keys`).
 */
class KeyProvider {

    /**
     * Возвращает кандидатов: dev-ключ + нулевой 6-байтный (клон-карты после writeIdentity
     * с ZERO_KEY). Factory-ключи обрабатываются в lib (MifareClassicVcm1.FACTORY_KEYS).
     */
    fun candidateKeys(): List<ByteArray> {
        val dev = ByteArray(24) { it.toByte() }
        val zero = ByteArray(6)
        return listOf(dev, zero)
    }
}