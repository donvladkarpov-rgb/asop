package ru.asop.terminal.nfc

import android.media.AudioManager
import android.media.ToneGenerator

/**
 * Звуковые сигналы терминала. Промпт 008 UX: терминал **НЕ пищит в промежутках**
 * между шагами чтения/записи (никаких промежуточных Тонов). Один звук — только
 * после полного завершения работы с картой (success / error / ready-to-tap-next).
 *
 *  - [successBeep]  — успех: восходящий двухтонный «дзынь-дзынь»
 *  - [errorBeep]    — провал: длинный низкий «буу»
 *  - [readyBeep]    — готов к следующему тапу: короткий одинарный «тик»
 *
 * Все по низкой громкости (35/100), чтобы не оглушать водителя/кондуктора.
 */
object TonePlayer {

    /** Успешное завершение операции с картой (чтение / активация / запись). */
    fun successBeep() = playSequence(
        listOf(ToneGenerator.TONE_PROP_ACK to 140, ToneGenerator.TONE_PROP_ACK to 140),
        gapMs = 80
    )

    /** Ошибка — единичный низкий длинный тон. */
    fun errorBeep() = playSequence(
        listOf(ToneGenerator.TONE_PROP_BEEP2 to 600), // 600мс — заметно длиннее success
        gapMs = 0
    )

    /** Готов к следующему действию (например, после auth — приложите целевую). */
    fun readyBeep() = playSequence(
        listOf(ToneGenerator.TONE_PROP_BEEP to 80),
        gapMs = 0
    )

    /**
     * Промпт 011: короткий tick при обнаружении NFC-карты в режиме открытия смены —
     * пользователь сразу понимает «карта обнаружена», пока ReadVcm1 читает sector 1.
     * Это единственный «промежуточный» beep — до этого правило «никаких пиков между шагами»
     * соблюдается для активации карт; открытие смены — другой UX, tag-обнаружение немедленно
     * подтверждаем, иначе tap выглядит «в пустоту».
     */
    fun tapBeep() = playSequence(
        listOf(ToneGenerator.TONE_PROP_BEEP to 50),
        gapMs = 0
    )

    /**
     * Устаревший short beep — для обратной совместимости. Совпадает с [successBeep].
     * Все промежуточные шаги должны теперь использовать ничего или [readyBeep] (последний).
     */
    @Deprecated("Используйте successBeep/errorBeep/readyBeep; промежуточных бипов не должно быть")
    fun softBeep() = successBeep()

    private fun playSequence(tones: List<Pair<Int, Int>>, gapMs: Int) {
        try {
            val tg = ToneGenerator(AudioManager.STREAM_MUSIC, 35)
            for ((tone, durMs) in tones) {
                tg.startTone(tone, durMs)
                try { Thread.sleep(durMs.toLong()) } catch (_: InterruptedException) { /* ok */ }
                if (gapMs > 0) {
                    try { Thread.sleep(gapMs.toLong()) } catch (_: InterruptedException) { /* ok */ }
                }
            }
            tg.release()
        } catch (_: Exception) {
            // звук не критичен — не роняем операцию
        }
    }
}
