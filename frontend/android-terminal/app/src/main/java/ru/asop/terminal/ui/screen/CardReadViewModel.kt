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
import ru.asop.terminal.activation.CardIdentityVcm1
import ru.asop.terminal.db.SignatureVerifier
import ru.asop.terminal.db.TerminalKeyCryptor
import ru.asop.terminal.db.dao.TerminalKeyDao
import ru.asop.terminal.nfc.DesfireCardReader
import ru.asop.terminal.nfc.MifareClassicReader
import ru.asop.terminal.nfc.TonePlayer
import javax.inject.Inject

@HiltViewModel
class CardReadViewModel @Inject constructor(
    val nfcAdapter: NfcAdapter?,
    private val terminalKeyDao: TerminalKeyDao,
    private val keyCryptor: TerminalKeyCryptor,
    private val signatureVerifier: SignatureVerifier,
    private val syncPreferences: ru.asop.terminal.db.SyncPreferences
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

    @Volatile
    private var currentReadJob: kotlinx.coroutines.Job? = null

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
        // НЕ отменяем предыдущий read — пусть каждый NFC tag event даёт свой результат,
        // merge между ними происходит в `_state.update` через mergeClassicInfo.
        // Это даёт нам накопление partial reads (один tap = sectors 1-4 OK, следующий
        // tap event = sectors 5-9 OK, суммарно все секторы).
        // currentReadJob?.cancel() -- ранее приводил к потере sector-reads между tag-events.
        val scope = viewModelScope
        currentReadJob = scope.launch(Dispatchers.IO) {
            val result = if (MifareClassicReader.isMifareClassic(tag)) {
                val keys = terminalKeyDao.getActive(10)
                    .mapNotNull { e ->
                        try { keyCryptor.decrypt(e.keyMaterialEnc) }
                        catch (x: Exception) { Log.w(CardReadViewModel.TAG, "key decrypt: ${x.message}"); null }
                    }
                MifareClassicReader.read(tag, keys)
            } else {
                DesfireCardReader.read(tag)
            }

            val rawIdentity = if (result.isDesfire && result.applications.any { it.replace(" ", "") == aidHex }) {
                _state.update { it.copy(identityLoading = true) }
                val keys = terminalKeyDao.getActive(10)
                    .mapNotNull { e ->
                        try { keyCryptor.decrypt(e.keyMaterialEnc) }
                        catch (x: Exception) { Log.w(CardReadViewModel.TAG, "key decrypt: ${x.message}"); null }
                    }
                DesfireCardReader.readAsopIdentity(tag, keys)
            } else if (result.isClassic && result.classicInfo?.vcm1Identity != null) {
                _state.update { it.copy(identityLoading = true) }
                val vcm1 = result.classicInfo.vcm1Identity
                Log.i(CardReadViewModel.TAG, "Classic VCM1 detected: bitmask=0x${vcm1.bitmask.toString(16)}, " +
                    "cardId=${vcm1.cardId}")
                // Card-side cache для server whitelist (uid, cardId) проверок
                // в последующих sync-командах (transaction, session close, etc.).
                // Это промпт 008 disconnect-fix: без этого cache server не знает
                // что конкретный uid+cardId был активирован для текущего tap.
                val uidHex = result.uid
                scope.launch {
                    try {
                        syncPreferences.setLastCardTap(uidHex, vcm1.cardId.toString())
                        Log.d(CardReadViewModel.TAG,
                            "Saved lastCardTap: uid=$uidHex cardId=${vcm1.cardId}")
                    } catch (e: Exception) {
                        Log.w(CardReadViewModel.TAG, "setLastCardTap failed: ${e.message}")
                    }
                }
                // Промпт 008: VCM1 не имеет подписи — identity "valid" by construction.
                // Server-side cardId whitelist в `/sync/cardauth` обеспечивает protection.
                convertVcm1ToAsopIdentity(vcm1)
            } else null

            // VCM1-flow для Classic: identity valid by construction (нет подписи).
            // DESFire: signature verify (RSA-PSS-SHA256).
            val identity = if (rawIdentity != null && rawIdentity.signatureBase64 != null &&
                (rawIdentity.signatureBase64.isNotEmpty() ?: false) && result.isDesfire) {
                // DESFire verify path (только когда есть signature)
                val valid = signatureVerifier.verify(
                    rawIdentity.protoBytes ?: ByteArray(0),
                    rawIdentity.signatureBase64
                )
                Log.i(CardReadViewModel.TAG, "DESFire ASOP signature verify: $valid")
                rawIdentity.copy(signatureValid = valid)
            } else if (rawIdentity != null && result.isClassic) {
                // VCM1 — без подписи, всегда valid по construction.
                Log.i(CardReadViewModel.TAG, "Classic VCM1: identity valid by construction (no signature)")
                rawIdentity.copy(signatureValid = true)
            } else rawIdentity

            if (readId != readSequence) return@launch
            val previous = _state.value.result
            if (previous?.version != null && result.version == null) {
                return@launch
            }
            // MERGE через несколько tag events: предыдущий read мог прочитать sectors 1-4,
            // новый read — sectors 5-9. Склеиваем allBlocks, keyLabels — preferring non-failed.
            val mergedClassic = if (result.isClassic && previous?.isClassic == true) {
                mergeClassicInfo(previous.classicInfo!!, result.classicInfo!!)
            } else result.classicInfo
            val resultForState = if (mergedClassic != null) result.copy(classicInfo = mergedClassic) else result
            val resultWithIdentity = if (identity != null) resultForState.copy(identity = identity) else resultForState
            _state.update { it.copy(listening = false, result = resultWithIdentity, identityLoading = false) }
            if (result.error == null) {
                TonePlayer.successBeep()
            } else {
                TonePlayer.errorBeep()
            }
            currentReadJob = null
        }
    }

    /**
     * Склеивает results двух partial reads одной Classic-карты. Для каждого sector
     * предпочитает non-failed blocks. Используется когда новый tag event даёт свежую
     * CRYPTO1-сессию (clone-карты теряют сессию после ~4 reads); без merge каждый новый
     * tap стирал бы предыдущие sectors 1-4 успешно прочитанные.
     */
    private fun mergeClassicInfo(
        previous: DesfireCardReader.ClassicInfo,
        latest: DesfireCardReader.ClassicInfo
    ): DesfireCardReader.ClassicInfo {
        val mergedBlocks = LinkedHashMap<Int, List<String>>(previous.allBlocks)
        for ((sector, latestBlocks) in latest.allBlocks) {
            val oldBlocks = previous.allBlocks[sector]
            val latestOk = latestBlocks.count { it != "(read failed)" }
            val oldOk = oldBlocks?.count { it != "(read failed)" } ?: 0
            val merged = when {
                oldBlocks == null -> latestBlocks
                // Берём версию у которой больше OK-блоков (т.е. свежее прочитанных)
                latestOk > oldOk -> latestBlocks
                oldOk > latestOk -> oldBlocks
                oldBlocks.size == latestBlocks.size -> oldBlocks.zip(latestBlocks).map { (old, new) ->
                    if (old != "(read failed)") old else new
                }
                // size mismatch с равным числом OK — берём тот, что больше по size
                latestBlocks.size > oldBlocks.size -> latestBlocks
                else -> oldBlocks
            }
            mergedBlocks[sector] = merged
            Log.d(CardReadViewModel.TAG, "merge sector $sector: old=$oldOk OK/${oldBlocks?.size}, new=$latestOk OK/${latestBlocks.size} → ${merged.count { it != "(read failed)" }} OK/${merged.size}")
        }
        val mergedLabels = LinkedHashMap<Int, String>(previous.trailerKeyLabels)
        latest.trailerKeyLabels.forEach { (s, l) -> if (mergedLabels[s] == null) mergedLabels[s] = l }
        // VCM1 identity: previous wins (server-trusted cardId уже сохранён).
        val vcm1 = previous.vcm1Identity ?: latest.vcm1Identity
        return previous.copy(
            allBlocks = mergedBlocks,
            trailerKeyLabels = mergedLabels,
            sac1Identity = null,  // legacy SAC1 больше не используется
            vcm1Identity = vcm1
        )
    }

    /**
     * VCM1 → legacy AsopIdentity shape для UI (промпт 008 legacy compat).
     * VCM1 un-signed, поэтому signature пустая.
     */
    private fun convertVcm1ToAsopIdentity(vcm1: CardIdentityVcm1): DesfireCardReader.AsopIdentity {
        // VCM1 doesn't have signature. Создаём fake protoBytes = пустой массив и пустую signature.
        return DesfireCardReader.AsopIdentity(
            identityJson = buildVcm1JsonString(vcm1),
            signatureBase64 = "",
            protoBytes = null  // чтобы UI понимал это как VCM1 (нет proto)
        )
    }

    private fun buildVcm1JsonString(vcm1: CardIdentityVcm1): String {
        // Canonical JSON-like: {cardId, bitmask, entity{type,id}, format:VCM1}
        val buf = StringBuilder()
        buf.append('{').append('"').append("format").append('"').append(':')
            .append('"').append("VCM1").append('"').append(',')
        buf.append('"').append("cardId").append('"').append(':')
            .append('"').append(vcm1.cardId.toString()).append('"').append(',')
        buf.append('"').append("bitmask").append('"').append(':').append(vcm1.bitmask).append(',')
        val e = vcm1.entity
        if (e != null) {
            buf.append('"').append("entity").append('"').append(':').append('{')
                .append('"').append("type").append('"').append(':')
                .append('"').append(e.type.fieldName).append('"').append(',')
                .append('"').append("id").append('"').append(':')
                .append('"').append(e.id.toString()).append('"').append('}')
        }
        buf.append('}')
        return buf.toString()
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
