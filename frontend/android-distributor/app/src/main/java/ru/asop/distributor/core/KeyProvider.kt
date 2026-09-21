package ru.asop.distributor.core

import ru.asop.distributor.sync.SyncStore
import ru.asop.nfc.TerminalKeyCryptor

/**
 * Источник ключевого материала (ASOP_KEYS) для работы с картами.
 *
 * Ключи приходят через серверный контур (ProvisionSyncManager → SyncStore) и
 * перешифрованы локальным AES-GCM-ключом AndroidKeyStore (TerminalKeyCryptor) —
 * в памяти/на диске только ciphertext. До первого синка — dev-ключ (24-байтный
 * `000102...171617`, совпадает с DEV_ASOP_KEY_BASE64 crypto-service) + нулевой.
 */
class KeyProvider(
    private val store: SyncStore,
    private val cryptor: TerminalKeyCryptor
) {
    companion object {
        /** Dev-ключ crypto-service в dev-режиме (AAECAwQFBgcICQoLDA0ODxAREhMUFRYX). */
        private val DEV_KEY = ByteArray(24) { it.toByte() }
        private val ZERO_KEY = ByteArray(6)
    }

    @Volatile
    private var cached: List<ByteArray>? = null

    /**
     * Кандидаты для auth: дешифрованные синхронизированные ключи + нулевой
     * (клон-карты после writeIdentity с ZERO_KEY). Factory-ключи обрабатываются в lib.
     * Не-блокирующая: до первого refresh() (после синка) — dev+zero (текущий scaffold).
     */
    fun candidateKeys(): List<ByteArray> =
        cached ?: fallback()

    suspend fun refresh() {
        cached = buildCandidates()
    }

    private suspend fun buildCandidates(): List<ByteArray> {
        val decrypted = store.keys().mapNotNull { row ->
            runCatching { cryptor.decrypt(row.keyMaterial) }.onFailure {
                android.util.Log.w("KeyProvider", "decrypt key ${row.keyId} failed: ${it.message}")
            }.getOrNull()
        }
        return if (decrypted.isEmpty()) fallback() else decrypted + ZERO_KEY
    }

    private fun fallback(): List<ByteArray> = listOf(DEV_KEY, ZERO_KEY)
}