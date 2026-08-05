package ru.asop.terminal.nfc

import android.media.AudioManager
import android.media.ToneGenerator

/**
 * Нежный подтверждающий звук при успешном чтении карты.
 * Короткий двухчастотный тон на низкой громкости.
 */
object TonePlayer {

    fun softBeep() {
        try {
            val tone = ToneGenerator(AudioManager.STREAM_MUSIC, 35)
            tone.startTone(ToneGenerator.TONE_PROP_ACK, 140)
            Thread.sleep(220)
            tone.release()
        } catch (_: Exception) {
            // звук не критичен — не роняем чтение
        }
    }
}
