package ru.asop.terminal.ui.screen

import android.nfc.NfcAdapter
import android.nfc.Tag
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.asop.terminal.db.TerminalKeyCryptor
import ru.asop.terminal.db.dao.TerminalKeyDao
import ru.asop.terminal.nfc.DesfireCardReader
import ru.asop.terminal.nfc.TonePlayer
import javax.inject.Inject

@HiltViewModel
class CardReadViewModel @Inject constructor(
    val nfcAdapter: NfcAdapter?,
    private val terminalKeyDao: TerminalKeyDao,
    private val keyCryptor: TerminalKeyCryptor
) : ViewModel() {

    data class UiState(
        val supported: Boolean,
        val enabled: Boolean,
        val listening: Boolean = false,
        val result: DesfireCardReader.ReadResult? = null,
        val identityLoading: Boolean = false
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
        val newTag = !tag.id.contentEquals(lastTagId)
        lastTagId = tag.id
        if (newTag) readSequence++
        val readId = readSequence
        viewModelScope.launch(Dispatchers.IO) {
            val result = DesfireCardReader.read(tag)
            // Чтение ASOP identity (нужна отдельная IsoDep-сессия)
            val identity = if (result.isDesfire && result.applications.any { it == aidHex }) {
                _state.update { it.copy(identityLoading = true) }
                val keys = terminalKeyDao.getActive(10)
                    .mapNotNull { e ->
                        try { keyCryptor.decrypt(e.keyMaterialEnc) }
                        catch (x: Exception) { Log.w(CardReadViewModel.TAG, "key decrypt: ${x.message}"); null }
                    }
                DesfireCardReader.readAsopIdentity(tag, keys)
            } else null

            if (readId != readSequence) return@launch
            val current = _state.value.result
            if (current?.version != null && result.version == null) {
                return@launch
            }
            val resultWithIdentity = if (identity != null) result.copy(identity = identity) else result
            _state.update { it.copy(listening = false, result = resultWithIdentity, identityLoading = false) }
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

    companion object {
        private const val TAG = "CardReadViewModel"
        private const val aidHex = "A05A01"
    }
}
