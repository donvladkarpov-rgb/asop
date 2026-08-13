package ru.asop.terminal.nfc

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Промпт 014: общий рефкаунт NFC-reader'а между ВСЕМИ экранами, которые его
 * армят (SessionFlowScreen, CardActivationScreen, CardReadScreen).
 *
 * Проблема (аналог Промпт 013b, но межэкранная): каждый экран арм/дизармит
 * NfcAdapter.enableReaderMode(activity, ...) на ОДНОМ и том же Activity. При
 * ре-навигации старый экран «доживает» в composition и его onDispose вызывает
 * disableReaderMode ПОЗЖЕ — уже после того как новый экран (напр. «Открыть
 * смену») заармил reader. На Feitian PiccService «победил последний вызов»:
 * поздний disable убивал читалку активного экрана → tap не перехватывался
 * приложением вовсе (в logcat — ноль событий NFC).
 *
 * Решение: physical disable выполняется ТОЛЬКО когда рефкаунт упал до 0, т.е.
 * ни один экран больше не держит reader. Арм считается на каждый
 * enableReaderMode; каждый onDispose делает release.
 */
object NfcReaderRefCount {
    private val lock = ReentrantLock()
    private var count = 0

    fun acquire() {
        lock.withLock { count++ }
    }

    /** @return true если надо физически disable reader (счётчик упал до 0). */
    fun releaseAndShouldDisable(): Boolean =
        lock.withLock {
            count--
            count <= 0
        }

    fun activeCount(): Int = lock.withLock { count }
}