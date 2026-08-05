package ru.asop.terminal.ui.screen

import android.nfc.NfcAdapter
import android.nfc.Tag
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.asop.terminal.nfc.DesfireCardReader
import ru.asop.terminal.nfc.TonePlayer
import javax.inject.Inject

@HiltViewModel
class CardReadViewModel @Inject constructor(
    val nfcAdapter: NfcAdapter?
) : ViewModel() {

    data class UiState(
        val supported: Boolean,
        val enabled: Boolean,
        val listening: Boolean = false,
        val result: DesfireCardReader.ReadResult? = null
    )

    private val _state = MutableStateFlow(
        UiState(
            supported = nfcAdapter != null,
            enabled = nfcAdapter?.isEnabled ?: false
        )
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    /**
     * Счётчик чтений: инвалидирует устаревшие результаты, когда пользователь
     * нажал «Прочитать ещё раз» или приложил ДРУГУЮ карту, пока предыдущая
     * операция ещё выполняется в фоне.
     */
    @Volatile
    private var readSequence = 0

    @Volatile
    private var lastTagId: ByteArray? = null

    fun onReadingStarted() {
        _state.update { it.copy(listening = true, result = null) }
    }

    /**
     * Callback NfcAdapter.ReaderCallback диспатчится на главном потоке, а
     * DesfireCardReader.read делает до десятка трансиверов с таймаутом 3 с и
     * ретраями (для клоновых/сломанных карт — десятки секунд). Чтение и звук
     * выносим на IO-диспетчер, иначе main thread блокируется → ANR.
     */
    fun onTagDiscovered(tag: Tag) {
        // NfcA-probe деактивирует карту и провоцирует повторный dispatch той же
        // карты. Если каждый такой dispatch инвалидирует идущее чтение, результат
        // никогда не успевает доехать до UI в цикле re-dispatch. Инвалидируем
        // только при прикладывании ДРУГОЙ карты или явном reset().
        val newTag = !tag.id.contentEquals(lastTagId)
        lastTagId = tag.id
        if (newTag) readSequence++
        val readId = readSequence
        viewModelScope.launch(Dispatchers.IO) {
            val result = DesfireCardReader.read(tag)
            if (readId != readSequence) return@launch
            val current = _state.value.result
            // Не затираем успешный результат неудачным перечитыванием той же карты
            // (например, когда карту уже убрали и кадр оборвался).
            if (current?.version != null && result.version == null) {
                return@launch
            }
            _state.update { it.copy(listening = false, result = result) }
            if (result.error == null) {
                TonePlayer.softBeep()
            }
        }
    }

    fun reset() {
        readSequence++
        lastTagId = null
        _state.update { it.copy(listening = true, result = null) }
    }
}
