package ru.asop.terminal.ui.screen

import android.app.Application
import android.content.Context
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.nfc.tech.MifareClassic
import android.nfc.tech.NfcA
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.f4b6a3.uuid.UuidCreator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import ru.asop.terminal.activation.AsopCardType
import ru.asop.terminal.activation.CardActivationMatrix
import ru.asop.terminal.activation.CardIdentityVcm1
import ru.asop.terminal.activation.EntityRef
import ru.asop.terminal.activation.EntityType
import ru.asop.proto.v1.CardIdentity as ProtoCardIdentity
import ru.asop.terminal.db.CardIdentityCodec
import ru.asop.terminal.db.TerminalKeyCryptor
import ru.asop.terminal.db.dao.ReferenceRowDao
import ru.asop.terminal.db.dao.TerminalKeyDao
import ru.asop.terminal.nfc.DesfireCardReader
import ru.asop.terminal.nfc.DesfireCardWriter
import ru.asop.terminal.nfc.MifareClassicCardWriter
import ru.asop.terminal.nfc.MifareClassicReader
import ru.asop.terminal.nfc.TonePlayer
import ru.asop.terminal.network.SyncApi
import ru.asop.terminal.network.models.CardActivateClassicRequest
import ru.asop.terminal.network.models.CardActivateRequest
import ru.asop.terminal.network.models.CardIdentity
import ru.asop.terminal.network.models.CardIdentitySignRequest
import ru.asop.terminal.network.models.RootLoginRequest
import javax.inject.Inject

/**
 * Flow активации карт АСОП (промпт 005, п.9.2):
 *   Network → Auth (своя карта / root login) → Target (целевая карта) →
 *   ReferenceForm (dropdown'ы) → sign → activate → прошивка карты.
 *
 * Сервер повторно проверяет авторизацию (operatorRoles/authorizedByRoot)
 * и подпись RSA-PSS — локальный role-check носит UX-характер.
 */
@HiltViewModel
class CardActivationViewModel @Inject constructor(
    private val application: Application,
    val nfcAdapter: NfcAdapter?,
    private val syncApi: SyncApi,
    private val terminalKeyDao: TerminalKeyDao,
    private val terminalKeyCryptor: TerminalKeyCryptor,
    private val referenceRowDao: ReferenceRowDao,
    private val syncPreferences: ru.asop.terminal.db.SyncPreferences
) : ViewModel() {

    companion object {
        // Master PICC ключ новой карты (фабричный) — 24 байта нулей (3K3DES).
        private val ZERO_KEY = ByteArray(24)

        /** Технология NFC-карты (промпт 007). */
        enum class CardTech { DESFIRE, CLASSIC, UNSUPPORTED }

        /** Детекция технологии по Tag (см. промпт 007, §1). */
        fun detectTech(tag: Tag): CardTech = when {
            MifareClassic.get(tag) != null -> CardTech.CLASSIC
            IsoDep.get(tag) != null        -> CardTech.DESFIRE
            else                           -> CardTech.UNSUPPORTED
        }
    }

    data class RefOption(val id: String, val label: String)

    enum class Step { NetworkCheck, RootForm, AuthForm, TargetCard, ReferenceForm, Busy, Done, Success, Error }

    data class UiState(
        val cardType: AsopCardType? = null,
        val step: Step = Step.NetworkCheck,
        val message: String = "",
        // Промпт 008 UX: чек на экране терминала (timeline действий с таймстампами).
        // Наполняется на key steps, рендерится в ReceiptCard после Success.
        val receiptEntries: List<ReceiptEntry> = emptyList(),
        // авторизация
        val rootUsername: String = "admin@asop.local",
        val rootPassword: String = "admin",
        val operatorRoles: List<String> = emptyList(),
        val authorizedByRoot: Boolean = false,
        val rootUserId: String? = null,
        val validatedError: String? = null,
        // целевая карта
        val targetCardUid: String? = null,
        val targetCardMode: String? = null, // "new" | "existing"
        val previousIdentityJson: String? = null,
        val previousRegionId: String? = null,
        val previousOrganizerId: String? = null,
        val previousCarrierId: String? = null,
        val previousDistributorId: String? = null,
        val previousAuditServiceId: String? = null,
        val previousUserId: String? = null,
        val workingKeyForCard: ByteArray? = null,
        // Classic-flow (промпт 007): работающий ASOP key A и key B для existing карты (по 6 байт).
        val workingKeyClassicA: ByteArray? = null,
        val workingKeyClassicB: ByteArray? = null,
        // Технология, определённая на процессе целевой карты (для dispatch в runWrite).
        val pendingWriteTech: CardTech? = null,
        // справочники
        val regions: List<RefOption> = emptyList(),
        val organizers: List<RefOption> = emptyList(),
        val carriers: List<RefOption> = emptyList(),
        val distributors: List<RefOption> = emptyList(),
        val auditServices: List<RefOption> = emptyList(),
        val users: List<RefOption> = emptyList(),
        val selectedRegionId: String? = null,
        val selectedOrganizerId: String? = null,
        val selectedCarrierId: String? = null,
        val selectedDistributorId: String? = null,
        val selectedAuditServiceId: String? = null,
        val selectedUserId: String? = null,
        val userQuery: String = "",
        val busy: Boolean = false,
        val finalResult: String? = null,
        val finalOk: Boolean = false,
        val serverRegistered: Boolean = false,
        val cardWritten: Boolean = false
    ) {
        val needsRoot: Boolean get() = cardType?.requiresRoot == true
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    // Токен приложенной карты (IsoDep-канал открывается в момент прикладывания).
    @Volatile
    private var heldTag: Tag? = null

    // Для повторного прикладывания после серверной регистрации.
    private var pendingWriteProto: ByteArray? = null
    private var pendingWriteSignature: String? = null
    /** VCM1-payload (промпт 008) — modernized replacement для pendingWriteProto/Signature. */
    private var pendingWriteVcm1: CardIdentityVcm1? = null
    /** serverCardId, которую сервер мог переписать поверх clientCardId (для re-write block 1). */
    private var pendingServerCardIdOverride: String? = null

    val nfcAvailable: Boolean get() = nfcAdapter != null && (nfcAdapter?.isEnabled ?: false)

    private val humanTagsByStep = mapOf(
        Step.AuthForm to "Приложите карту авторизации",
        Step.ReferenceForm to "Заполните поля и нажмите «Активировать»"
    )

    // ---------- Шаг 1: выбор типа ----------

    fun onCardTypeSelected(type: AsopCardType) {
        if (_state.value.step == Step.Busy) return
        val online = hasNetwork()
        _state.update {
            it.copy(
                cardType = type,
                step = when {
                    !online -> Step.Error
                    type.requiresRoot -> Step.RootForm
                    else -> Step.AuthForm
                },
                message = when {
                    !online -> "Нет сети: активация недоступна офлайн"
                    type.requiresRoot -> "Введите логин/пароль Главного администратора"
                    else -> "Приложите карту авторизации"
                },
                operatorRoles = emptyList(),
                authorizedByRoot = false,
                rootUserId = null,
                targetCardUid = null,
                targetCardMode = null,
                previousIdentityJson = null,
                previousRegionId = null, previousOrganizerId = null,
                previousCarrierId = null, previousDistributorId = null,
                previousAuditServiceId = null, previousUserId = null,
                workingKeyForCard = null,
                workingKeyClassicA = null,
                workingKeyClassicB = null,
                pendingWriteTech = null,
                selectedRegionId = null, selectedOrganizerId = null,
                selectedCarrierId = null, selectedDistributorId = null,
                selectedAuditServiceId = null, selectedUserId = null,
                userQuery = "",
                finalResult = null, finalOk = false,
                serverRegistered = false, cardWritten = false,
                validatedError = null
            )
        }
    }

    // ---------- Шаг 2: авторизация ----------

    private fun hasNetwork(): Boolean {
        val cm = application.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    fun onRootUsername(v: String) = _state.update { it.copy(rootUsername = v) }
    fun onRootPassword(v: String) = _state.update { it.copy(rootPassword = v) }

    fun rootLogin() {
        val s = _state.value
        if (s.busy || s.rootUsername.isBlank() || s.rootPassword.isBlank()) {
            _state.update { it.copy(validatedError = "Заполните логин и пароль") }
            return
        }
        _state.update { it.copy(busy = true, message = "Проверка учётной записи Главного администратора…") }
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching {
                val resp = syncApi.rootLogin(RootLoginRequest(s.rootUsername.trim(), s.rootPassword))
                if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code()}")
                val body = resp.body() ?: throw IllegalStateException("пустой ответ auth/root")
                body.userId
            }
            result.fold(
                onSuccess = { userId ->
                    _state.update {
                        it.copy(
                            authorizedByRoot = true,
                            rootUserId = userId,
                            operatorRoles = listOf("SUPER_ADMIN"),
                            busy = false,
                            step = Step.TargetCard,
                            message = "Теперь приложите целевую карту"
                        )
                    }
                },
                onFailure = { e ->
                    _state.update { it.copy(busy = false, validatedError = "Авторизация Главного администратора не прошла: ${e.message}") }
                }
            )
        }
    }

    // ---------- Шаг 3: NFC ----------

    // Промпт 014: debounce повторных onTagDiscovered (~250мс при hold карты).
    private var lastTapUidHex: String? = null
    private var lastTapAtMillis: Long = 0L
    private val debounceMs = 1500L

    fun onTagDiscovered(tag: Tag) {
        val now = System.currentTimeMillis()
        val newUid = tag.id.joinToString("") { "%02X".format(it) }
        if (newUid == lastTapUidHex && (now - lastTapAtMillis) < debounceMs) return
        lastTapUidHex = newUid
        lastTapAtMillis = now

        val s = _state.value
        if (s.busy || s.finalResult != null) return
        // Step.Success — финальное состояние после успешной прошивки карты.
        // Не реагируем на новые NFC-tap'ы: всё уже сделано, чтобы случайный
        // повторный tap не запустил processTargetCard/processAuth и не сбросил state.
        if (s.step == Step.Success) return
        if (s.serverRegistered && s.targetCardMode != null) {
            runWrite(tag)
            return
        }
        val tech = detectTech(tag)
        if (tech == CardTech.UNSUPPORTED) {
            _state.update { it.copy(message = "Карта не поддерживается (нет ни MifareClassic, ни IsoDep)") }
            return
        }
        when (s.step) {
            Step.AuthForm -> identifyAuthCard(tag, tech)
            Step.TargetCard -> processTargetCard(tag, tech)
            else -> Unit
        }
    }

    /** Авторизующая карта: идентификация по ключам (9.5) → роли → role-check. */
    private fun identifyAuthCard(tag: Tag, tech: CardTech = CardTech.DESFIRE) {
        when (tech) {
            CardTech.CLASSIC -> identifyClassicAuthCard(tag)
            CardTech.DESFIRE -> identifyDesfireAuthCard(tag)
            CardTech.UNSUPPORTED -> Unit
        }
    }

    private fun identifyDesfireAuthCard(tag: Tag) {
        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(message = "Идентификация авторизующей карты (DESFire)…") }
            val writer = DesfireCardWriter()
            val iso = writer.open(tag)
            if (iso == null) {
                _state.update { it.copy(message = "Не удалось открыть IsoDep авторизующей карты") }
                return@launch
            }
            val roles = try { identifyCardRoles(iso) } finally { writer.close(iso) }
            if (roles == null) {
                _state.update { it.copy(message = "Не удалось прочитать карту авторизации (ключ/identity)") }
                return@launch
            }
            val target = _state.value.cardType ?: return@launch
            if (CardActivationMatrix.canAuthorize(roles, target.role)) {
                _state.update {
                    it.copy(
                        operatorRoles = roles,
                        step = Step.TargetCard,
                        message = "Теперь приложите целевую карту"
                    )
                }
                // Tone отключён на auth-флоу — пользователь сказал что терминал пищит
                // периодически. Подтверждение через HapticFeedbackConstants в UI.
            } else {
                _state.update { it.copy(message = "Роль ${roles.joinToString()} не может активировать ${target.label}") }
            }
        }
    }

    /**
     * Classic-ветка авторизации (промпт 014 — fast path):
     * - перебирает ASOP ключи → auth sector 1 → читает 4 блока VCM1;
     * - без сканирования всех 16 секторов (было раньше через MifareClassicReader.read(),
     *   что давало ~6с чтения и многократные писки).
     */
    private fun identifyClassicAuthCard(tag: Tag) {
        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(message = "Идентификация авторизующей карты (MIFARE Classic)…") }
            val keys = terminalKeyDao.getActive(30)
            val candidates = keys.map { terminalKeyCryptor.decrypt(it.keyMaterialEnc) }

            val mfc = try { MifareClassic.get(tag) } catch (_: Exception) { null }
            if (mfc == null) {
                _state.update { it.copy(message = "Карта не MifareClassic") }
                return@launch
            }
            try { mfc.connect() } catch (e: Exception) {
                _state.update { it.copy(message = "Не удалось подключиться: ${e.message}") }
                return@launch
            }
            try {
                // Промпт 014: перебираем 24-байтные ASOP-ключи, auth sector 1 → VCM1.
                val sector = 1
                val base = mfc.sectorToBlock(sector)
                for (fullKey in candidates) {
                    if (fullKey.size < 12) continue
                    val keyA = fullKey.copyOfRange(0, 6)
                    val keyB = fullKey.copyOfRange(6, 12)
                    try { mfc.authenticateSectorWithKeyA(sector, keyA) } catch (_: Exception) { continue }
                    val blocks = (0 until 3).map { i ->
                        try { mfc.readBlock(base + i) } catch (_: Exception) { null }
                    }
                    if (blocks.any { it == null }) continue
                    val all = blocks.filterNotNull()
                    val raw = ByteArray(48)
                    var pos = 0
                    for (b in all) { b.copyInto(raw, pos); pos += b.size }
                    try {
                        val identity = ru.asop.terminal.activation.CardIdentityVcm1.decodeFromBytes(raw)
                        if (identity != null) {
                            val roles = ru.asop.terminal.activation.AsopCardType
                                .allRolesForBitmask(identity.bitmask).map { it.role }
                            if (roles.isNotEmpty()) {
                                finalizeAuth(roles, keyA, keyB, "Classic VCM1 (fast)")
                                return@launch
                            }
                        }
                    } catch (_: Exception) { /* не VCM1 */ }
                }

            _state.update {
                it.copy(message = "Карта авторизации не опознана — ни один АСОП-ключ не подошёл")
            }
            } finally {
                try { mfc.close() } catch (_: Exception) {}
            }
        }
    }
    
    /**
     * Промпт 008: единая точка финализации auth (вызывается из SAC1 / VCM1 веток).
     * Сохраняет operatorRoles + working keys + переходит в Step.TargetCard.
     */
    private fun finalizeAuth(roles: List<String>, keyA: ByteArray, keyB: ByteArray, source: String) {
        val target = _state.value.cardType
        if (target != null && !CardActivationMatrix.canAuthorize(roles, target.role)) {
            _state.update {
                it.copy(message = "Роль ${roles.joinToString()} не может активировать ${target.label}")
            }
            addReceipt("Авторизация отклонена: ${roles.joinToString()} не имеет прав на ${target.label}")
            TonePlayer.errorBeep()
            return
        }
        _state.update {
            it.copy(
                operatorRoles = roles,
                workingKeyClassicA = keyA,
                workingKeyClassicB = keyB,
                step = Step.TargetCard,
                message = "Теперь приложите целевую карту"
            )
        }
        addReceipt("Авторизация оператора: ${roles.joinToString()}")
        TonePlayer.readyBeep() // "приложите целевую"
    }

    /** Ищем первый ASOP-кандидат подходящего размера (24/12/6 байт). */
    private fun candidateForAuth(candidates: List<ByteArray>): ByteArray? =
        candidates.firstOrNull { it.size >= 12 } ?: candidates.firstOrNull { it.size == 6 }

    /**
     * Проходит уже прочитанные блоки секторов 1..15 из `ClassicInfo.allBlocks`
     * и пытается найти SAC1 payload (4-байтный magic + прочие поля).
     * Возвращает Pair<Sac1Payload, workingKey> если нашли, иначе null.
     * Без повторных transceive.
     */
    private fun tryFindSac1Identity(
        allBlocks: Map<Int, List<String>>,
        candidates: List<ByteArray> = emptyList()
    ): Pair<MifareClassicCardWriter.Sac1Payload, ByteArray>? {
        // Проходим секторы 1..15, конкатенируем data-блоки (всё кроме trailer)
        for (sector in 1..15) {
            val blocks = allBlocks[sector] ?: continue
            if (blocks.isEmpty() || blocks.contains("(read failed)")) continue
            val dataBlocks = blocks.dropLast(1)
            val raw = dataBlocks.flatMap { hex ->
                val cleaned = hex.replace(" ", "")
                (0 until cleaned.length / 2).map { i ->
                    cleaned.substring(i * 2, i * 2 + 2).toInt(16).toByte()
                }
            }.toByteArray()
val payload = MifareClassicCardWriter.parseSac1Payload(raw)
                    ?: continue
            if (isSac1Trusted(payload)) {
                // SAC1 валидна — возвращаем с dev-fallback-ключом
                return payload to (candidates.firstOrNull { it.size >= 12 }
                    ?: candidateForAuth(candidates) ?: ByteArray(12))
            }
        }
        return null
    }

    /** Читает роли карты: идентификация рабочим ключом → identity JSON roles. */
    private suspend fun identifyCardRoles(iso: IsoDep): List<String>? {
        val roles = identifyCard(iso)?.identity?.optJSONArray("roles")
        if (roles == null) return null
        return (0 until roles.length()).mapNotNull { i ->
            roles.optString(i).takeIf { it.isNotBlank() }
        }
    }

    private data class Identified(val key: ByteArray, val identity: JSONObject?)

    /**
     * Идентификация на уже открытом IsoDep: перебирает terminal_keys,
     * пробует auth, читает ASOP identity file 0.
     */
    private suspend fun identifyCard(iso: IsoDep): Identified? {
        val writer = DesfireCardWriter()
        val keys = terminalKeyDao.getActive(30)
        val candidates = buildList {
            keys.map { terminalKeyCryptor.decrypt(it.keyMaterialEnc) }
                .forEach { add(it.copyOf()) }
        }
        for (key in candidates) {
            if (!writer.tryAuthenticateMaster(iso, key)) continue
            if (key.contentEquals(ZERO_KEY)) return null
            val identity = try {
                if (writer.selectAsop(iso) && writer.authenticateAsop(iso, key)) {
                    writer.readStd(iso, 0)?.let { data ->
                        runCatching {
                            val p = ProtoCardIdentity.parseFrom(data)
                            CardIdentityCodec.toJson(p)
                        }.getOrNull()
                    }
                } else null
            } catch (e: Exception) { null }
            return Identified(key, identity)
        }
        return null
    }

    /** Целевая карта: диагностика пригодности → нулевой ключ → новая; иначе existing. */
    private fun processTargetCard(tag: Tag, tech: CardTech = CardTech.DESFIRE) {
        when (tech) {
            CardTech.CLASSIC -> processClassicTargetCard(tag)
            CardTech.DESFIRE -> processDesfireTargetCard(tag)
            CardTech.UNSUPPORTED -> Unit
        }
    }

    private fun processDesfireTargetCard(tag: Tag) {
        viewModelScope.launch(Dispatchers.IO) {
            heldTag = tag
            _state.update { it.copy(busy = true, message = "Определение состояния карты (DESFire)…") }
            val writer = DesfireCardWriter()
            val uid = tag.id.joinToString("") { String.format("%02X", it) }

            // === Шаг 1: SelectApplication(мастер PICC) через IsoDep ===
            val iso = IsoDep.get(tag)
            if (iso != null) {
                try {
                    iso.connect()
                    iso.timeout = 5000
                    val selResult = writer.selectApplicationDetailed(iso, byteArrayOf(0, 0, 0))
                    when (selResult) {
                        DesfireCardWriter.SelectResult.OK -> {
                            iso.timeout = 5000
                            processWithIsoDep(tag, iso, uid, writer)
                            return@launch
                        }
                        DesfireCardWriter.SelectResult.IO_ERROR -> {
                            Log.w("TARGET", "IsoDep SelectApplication IO_ERROR — clone?")
                            runCatching { iso.close() }
                            processViaNfcA(tag, uid, writer)
                            return@launch
                        }
                        DesfireCardWriter.SelectResult.UNSUPPORTED -> {
                            runCatching { iso.close() }
                            _state.update {
                                it.copy(busy = false,
                                    message = "Карта не поддерживает SelectApplication (0x1C). " +
                                        "Возможно, это неполноценный клон DESFire. " +
                                        "Используйте оригинальную карту NXP DESFire EV2/EV3.")
                            }
                            return@launch
                        }
                        DesfireCardWriter.SelectResult.ERROR_STATUS -> {
                            Log.w("TARGET", "IsoDep SelectApplication ERROR_STATUS")
                            runCatching { iso.close() }
                            processViaNfcA(tag, uid, writer)
                            return@launch
                        }
                    }
                } catch (e: Exception) {
                    Log.w("TARGET", "IsoDep failed: ${e.message}")
                    runCatching { iso.close() }
                    processViaNfcA(tag, uid, writer)
                    return@launch
                }
            }

            processViaNfcA(tag, uid, writer)
        }
    }

    /** Fallback через NfcA (Layer 2, native фрейминг). */
    private fun processViaNfcA(tag: Tag, uid: String, writer: DesfireCardWriter) {
        val nfcA = NfcA.get(tag)
        if (nfcA == null) {
            _state.update {
                it.copy(busy = false,
                    message = "Карта не поддерживает IsoDep или NfcA. " +
                        "Возможно, карта не DESFire.")
            }
            return
        }
        try {
            nfcA.connect()
            nfcA.timeout = 2000
            Log.d("TARGET", "Using NfcA (Layer 2) for card $uid")

            // GetVersion — 2 попытки (0x60, 0x60 0x00), без ретраев
            var verResp: ByteArray? = null
            for (cmd in listOf(byteArrayOf(0x60), byteArrayOf(0x60, 0x00))) {
                try { verResp = nfcA.transceive(cmd) } catch (_: Exception) {}
                if (verResp != null && verResp.isNotEmpty()) break
            }
            if (verResp == null || verResp.isEmpty()) {
                _state.update {
                    it.copy(busy = false,
                        message = "Карта не отвечает на GetVersion. " +
                            "Карта не является DESFire или неисправна.")
                }
                return
            }

            // SelectApplication через NfcA — 2 попытки
            var selResp: ByteArray? = null
            for (attempt in 1..2) {
                try {
                    selResp = nfcA.transceive(byteArrayOf(0x5A, 0, 0, 0))
                    if (selResp != null && selResp.isNotEmpty()) break
                } catch (_: Exception) { }
                if (attempt < 2) Thread.sleep(120)
            }
            if (selResp == null || selResp.isEmpty()) {
                _state.update {
                    it.copy(busy = false,
                        message = "Карта не поддерживает SelectApplication. " +
                            "Возможно, это неполноценный клон DESFire. " +
                            "Используйте оригинальную карту NXP DESFire EV2/EV3.")
                }
                return
            }

            val selStatus = selResp[0].toInt() and 0xFF
            if (selStatus != 0x00) {
                _state.update {
                    it.copy(busy = false,
                        message = "SelectApplication вернул ошибку 0x${String.format("%02X", selStatus)}. " +
                            "Карта не пригодна для активации.")
                }
                return
            }

            // Auth через NfcA
            if (authenticateZeroKeysNfcA(nfcA)) {
                _state.update {
                    it.copy(busy = false, targetCardUid = uid, targetCardMode = "new",
                        workingKeyForCard = null, message = "Карта новая (NfcA). Заполните поля")
                }
                loadReferenceData()
            } else {
                // SelectApplication прошла, но auth нулевым ключом не прошёл —
                // карта зарегистрированная, но NfcA не подходит для write-операций.
                _state.update {
                    it.copy(busy = false,
                        message = "Карта уже зарегистрирована (ключ не нулевой). " +
                            "NfcA не поддерживает write-операции — переприложите карту для IsoDep.")
                }
            }
        } catch (e: Exception) {
            Log.e("TARGET", "NfcA error: ${e.message}")
            _state.update {
                it.copy(busy = false,
                    message = "Ошибка связи (NfcA): ${e.message}. " +
                        "Карта не пригодна для активации.")
            }
        } finally {
            runCatching { nfcA.close() }
        }
    }

    private suspend fun processWithIsoDep(tag: Tag, iso: IsoDep, uid: String, writer: DesfireCardWriter) {
        // SelectApplication уже прошла — пробуем auth нулевым ключом (новая карта)
        if (writer.tryAuthenticateMaster(iso, ByteArray(24))) {
            _state.update {
                it.copy(busy = false, targetCardUid = uid, targetCardMode = "new",
                    workingKeyForCard = null, message = "Карта новая. Заполните поля")
            }
            loadReferenceData()
        } else {
            // Auth нулевым ключом не прошёл — карта зарегистрированная, ищем рабочий ключ
            runCatching { iso.close() }
            // Переоткрываем IsoDep для identifyCard
            val iso2 = writer.open(tag)
            if (iso2 == null) {
                _state.update { it.copy(busy = false, message = "Не удалось переоткрыть IsoDep для идентификации") }
                return
            }
            try {
                val identified = identifyCard(iso2)
                if (identified == null) {
                    _state.update {
                        it.copy(busy = false,
                            message = "Карта не идентифицирована. " +
                                "Возможно, она не зарегистрирована в системе или ключ устарел.")
                    }
                } else {
                    val identity = identified.identity
                    _state.update {
                        it.copy(busy = false, targetCardUid = uid, targetCardMode = "existing",
                            workingKeyForCard = identified.key.copyOf(),
                            previousIdentityJson = identity?.toString(),
                            previousRegionId = identity?.optString("regionId")?.takeIf { it.isNotBlank() },
                            previousOrganizerId = identity?.optString("organizerId")?.takeIf { it.isNotBlank() },
                            previousCarrierId = identity?.optString("carrierId")?.takeIf { it.isNotBlank() },
                            previousDistributorId = identity?.optString("cardsDistributorId")?.takeIf { it.isNotBlank() },
                            previousAuditServiceId = identity?.optString("auditServiceId")?.takeIf { it.isNotBlank() },
                            previousUserId = identity?.optString("userId")?.takeIf { it.isNotBlank() },
                            message = "Карта зарегистрирована. Обновите поля и нажмите «Активировать»")
                    }
                    loadReferenceData()
                }
            } finally {
                writer.close(iso2)
            }
        }
    }

    private fun authenticateZeroKeysNfcA(nfcA: NfcA): Boolean {
        // Пробуем AES zero (16 байт) через native transceive
        val aesCmd = byteArrayOf(0xAA.toByte(), 0x00)
        val aesResp = try { nfcA.transceive(aesCmd) } catch (e: Exception) { null }
        if (aesResp != null && aesResp.size > 1 && (aesResp[0].toInt() and 0xFF) == 0xAF) return true
        // Пробуем 3K3DES zero (24 байта)
        val desCmd = byteArrayOf(0x1A, 0x00)
        val desResp = try { nfcA.transceive(desCmd) } catch (e: Exception) { null }
        return desResp != null && desResp.size > 1 && (desResp[0].toInt() and 0xFF) == 0xAF
    }

    // ---------- Шаг 4: справочники ----------

    /** Память каскадных связей: (ключ → список регионов). */
    private var regionByCarrier: Map<String, String> = emptyMap()
    private var regionsOfOrganizer: Map<String, Set<String>> = emptyMap()
    private var regionsOfUser: Map<String, Set<String>> = emptyMap()
    private var carriersOfUser: Map<String, Set<String>> = emptyMap()

    fun loadReferenceData() {
        viewModelScope.launch(Dispatchers.IO) {
            val regions = readTable("asop_regions")
            val organizers = readTable("asop_organizers")
            val carriers = readTable("asop_carriers")
            val distributors = readTable("asop_cards_distributors")
            val auditServices = readTable("asop_audit_services")
            val users = readTable("asop_users")

            // Каскад: territoryId→regionId (territories), organizerId→territoryIds (organizer_territories).
            val territoryRegions = readRawMap("asop_territories") { it.optString("territoryId") to it.optString("regionId") }
            regionsOfOrganizer = readRawMap("asop_organizer_territories") {
                val o = it.optString("organizerId")
                val region = territoryRegions[it.optString("territoryId")]
                o to (region ?: "")
            }.filterValues { it.isNotBlank() }
                .entries.groupBy({ it.key }, { it.value })
                .mapValues { it.value.toSet() }

            // Каскад: user_regions → регионы пользователя.
            regionsOfUser = readRawMap("asop_user_regions") {
                it.optString("userId") to it.optString("regionId")
            }.filterValues { it.isNotBlank() }
                .entries.groupBy({ it.key }, { it.value })
                .mapValues { it.value.toSet() }

            // Промпт 009: фильтрация users по выбранному carrier (asop_user_carriers).
            carriersOfUser = readRawMap("asop_user_carriers") {
                it.optString("userId") to it.optString("carrierId")
            }.filterValues { it.isNotBlank() }
                .entries.groupBy({ it.key }, { it.value })
                .mapValues { it.value.toSet() }

            regionByCarrier = readRawMap("asop_carriers") { it.optString("carrierId") to it.optString("regionId") }

            // Промпт 014: автозаполнение региона/перевозчика из настроек терминала
            // (terminalCarrierId, terminalRegionId из SyncPreferences). Это позволяет
            // не выбирать их заново при активации: терминал уже зарегистрирован на перевозчика.
            val terminalRegionId = syncPreferences.regionId.first()
            val terminalCarrierId = syncPreferences.carrierId.first()

            val s = _state.value
            addReceipt("Загружены справочники: ${regions.size} регионов, ${carriers.size} перевозчиков, ${users.size} пользователей")
            _state.update {
                it.copy(
                    regions = regions, organizers = organizers,
                    carriers = carriers, distributors = distributors,
                    auditServices = auditServices, users = users,
                    step = Step.ReferenceForm,
                    selectedRegionId = s.previousRegionId ?: terminalRegionId ?: s.selectedRegionId,
                    selectedOrganizerId = s.previousOrganizerId ?: s.selectedOrganizerId,
                    selectedCarrierId = s.previousCarrierId ?: terminalCarrierId ?: s.selectedCarrierId,
                    selectedDistributorId = s.previousDistributorId ?: s.selectedDistributorId,
                    selectedAuditServiceId = s.previousAuditServiceId ?: s.selectedAuditServiceId,
                    // Промпт 014: если previousUserId==null (fresh card detect, не перевыбор),
                    // не оставляем старый (возможно невалидный) selectedUserId — заставляем
                    // оператора явно выбрать пользователя. Иначе сочетался с carrier-фильтром:
                    // фильтр прятал не-1403 пользователей, но selectedUserId оставался старым
                    // и кнопка «Активировать» была доступна — оператор жал её не поменяв user.
                    selectedUserId = s.previousUserId ?: null
                )
            }
        }
    }

    /** Читает таблицу в (id → RefOption). */
    private suspend fun readTable(table: String): List<RefOption> =
        referenceRowDao.getActiveByTable(table).mapNotNull { row ->
            val json = runCatching { JSONObject(row.payloadJson) }.getOrNull() ?: return@mapNotNull null
            val id = extractId(json, table) ?: return@mapNotNull null
            val label = extractLabel(json, table) ?: id
            RefOption(id, label)
        }

    /** Читает таблицу в map (id → pair), фильтруя невалидные. */
    private suspend fun readRawMap(table: String, pairOf: (JSONObject) -> Pair<String, String>): Map<String, String> =
        referenceRowDao.getActiveByTable(table).mapNotNull { row ->
            val json = runCatching { JSONObject(row.payloadJson) }.getOrNull() ?: return@mapNotNull null
            val (k, v) = pairOf(json)
            if (k.isBlank() || v.isBlank()) null else k to v
        }.toMap()

    private fun extractId(json: JSONObject, table: String): String? = when (table) {
        "asop_regions" -> json.optString("regionId")
        "asop_organizers" -> json.optString("organizerId")
        "asop_carriers" -> json.optString("carrierId")
        "asop_cards_distributors" -> json.optString("cardsDistributorId")
        "asop_audit_services" -> json.optString("auditServiceId")
        "asop_users" -> json.optString("userId")
        else -> null
    }.takeIf { it?.isNotBlank() == true }

    private fun extractLabel(json: JSONObject, table: String): String? = when (table) {
        "asop_regions" -> json.optString("municipalDivision")
            .ifBlank { json.optString("adminDivision") }
            .ifBlank { json.optString("federalDistrict") }
        "asop_organizers" -> json.optString("organizerName")
        "asop_carriers" -> json.optString("carrierName")
        "asop_cards_distributors" -> json.optString("distributorName")
        "asop_audit_services" -> json.optString("serviceName")
        "asop_users" -> buildUserLabel(json)
        else -> null
    }.takeIf { it?.isNotBlank() == true }

    private fun buildUserLabel(json: JSONObject): String {
        val first = json.optString("firstName")
        val last = json.optString("lastNameInitial")
        val patronymic = json.optString("patronymicInitial")
        return listOf(first, last, patronymic).filter { it.isNotBlank() }.joinToString(" ")
    }

    // Каскадные фильтры справочников по региону (п.9.3). Возвращают все при пустом регионе.
    fun filteredOrganizers(): List<RefOption> {
        val regionId = _state.value.selectedRegionId
        if (regionId.isNullOrBlank()) return _state.value.organizers
        return _state.value.organizers.filter { regionsOfOrganizer[it.id]?.contains(regionId) == true }
    }

    fun filteredCarriers(): List<RefOption> {
        val regionId = _state.value.selectedRegionId
        if (regionId.isNullOrBlank()) return _state.value.carriers
        return _state.value.carriers.filter { regionByCarrier[it.id] == regionId }
    }

    fun filteredDistributors(): List<RefOption> = _state.value.distributors
    fun filteredAuditServices(): List<RefOption> = _state.value.auditServices

    fun filteredUsers(): List<RefOption> {
        val st = _state.value
        val needsCarrier = st.cardType?.needsCarrier == true

        // Root-логин / SUPER_ADMIN операторская карта — без carrier-фильтра, но ТОЛЬКО
        // для ролей без привязки к перевозчику. needsCarrier-роли (водитель, админ/
        // диспетчер перевозчика) ВСЕГДА ограничены пользователями, привязанными к
        // перевозчику терминала в админке (asop_user_carriers): владелец carrier-карты
        // обязан быть привязан к этому перевозчику, иначе карта не откроет смену.
        if (!needsCarrier) {
            if (st.authorizedByRoot) return st.users
            if (st.operatorRoles.contains("SUPER_ADMIN")) return st.users
        }

        // Промпт 014: если роль требует перевозчика (needsCarrier=true), а перевозчик не выбран —
        // не показываем ВСЕХ пользователей (как было раньше), а пустой список с подсказкой.
        // Иначе оператор может выбрать «левого» пользователя, не привязанного к выбранному carrier,
        // и карта не сможет открыть смену на терминале этого перевозчика.
        val regionId = st.selectedRegionId
        val carrierId = st.selectedCarrierId
        if (needsCarrier && carrierId.isNullOrBlank()) return emptyList()

        // Промпт 009: каскадная фильтрация. Применяются ВСЕ активные фильтры (AND).
        // - regionId: user в ASOP_USER_REGIONS с этим регионом (если selected)
        // - carrierId: user в ASOP_USER_CARRIERS с этим carrier (если selected)
        // - Если ни один фильтр не задан — ВСЕ пользователи (для ролей без needsCarrier).
        if (regionId.isNullOrBlank() && carrierId.isNullOrBlank()) return st.users

        val result = st.users.filter { u ->
            // Промпт 014: если выбран carrier, проверку по региону пропускаем —
            // carrier уже implicitly привязан к региону. Иначе пользователи, которые
            // есть в asop_user_carriers (carrier 1403) но НЕ в asop_user_regions,
            // отфильтровываются — и оператор видит «Нет пользователей».
            val skipRegionCheck = !carrierId.isNullOrBlank()
            val matchesRegion = skipRegionCheck || regionId.isNullOrBlank() ||
                regionsOfUser[u.id]?.contains(regionId) == true
            val matchesCarrier = carrierId.isNullOrBlank() ||
                carriersOfUser[u.id]?.contains(carrierId) == true
            matchesRegion && matchesCarrier
        }
        if (result.isEmpty() && !carrierId.isNullOrBlank()) {
            Log.d("CardActivationVM", "filteredUsers empty: carrierId=$carrierId, " +
                "carriersOfUser.size=${carriersOfUser.size}, " +
                "users.size=${st.users.size}, " +
                "firstUC=" + carriersOfUser.entries.firstOrNull())
        }
        return result
    }

    /** Смена региона сбрасывает зависимые подчинённые выборы (промпт 009 cascade). */
    fun selectRegion(id: String) = _state.update {
        it.copy(
            selectedRegionId = id,
            selectedOrganizerId = null,
            selectedCarrierId = null,
            selectedDistributorId = null,
            selectedAuditServiceId = null,
            selectedUserId = null,
            userQuery = ""
        )
    }
    /** Смена организ. (зависят carrier/distributor/audit/user). */
    fun selectOrganizer(id: String) = _state.update {
        it.copy(
            selectedOrganizerId = id,
            selectedCarrierId = null,
            selectedDistributorId = null,
            selectedAuditServiceId = null,
            selectedUserId = null,
            userQuery = ""
        )
    }
    /** Смена carrier сбрасывает дистрибьютор, КРС, user. */
    fun selectCarrier(id: String) = _state.update {
        it.copy(
            selectedCarrierId = id,
            selectedDistributorId = null,
            selectedAuditServiceId = null,
            selectedUserId = null,
            userQuery = ""
        )
    }
    /** Смена дистрибьютора сбрасывает user. */
    fun selectDistributor(id: String) = _state.update {
        it.copy(
            selectedDistributorId = id,
            selectedUserId = null,
            userQuery = ""
        )
    }
    /** Смена КРС сбрасывает user. */
    fun selectAuditService(id: String) = _state.update {
        it.copy(
            selectedAuditServiceId = id,
            selectedUserId = null,
            userQuery = ""
        )
    }
    fun selectUser(id: String) = _state.update { it.copy(selectedUserId = id) }
    fun onQueryChanged(q: String) = _state.update { it.copy(userQuery = q) }

    /** Алиас для UI (search по ФИО). */
    fun onUserQueryChanged(q: String) = onQueryChanged(q)

    // ---------- Шаг 5: активация ----------

    /** Canonical JSON cardIdentity: фиксированный порядок ключей, без пробелов. */
    fun buildCanonicalIdentity(s: UiState): JSONObject {
        val type = s.cardType ?: throw IllegalStateException("тип карты не выбран")
        val prev = s.previousIdentityJson?.let { runCatching { JSONObject(it) }.getOrNull() }
        val uid = s.targetCardUid ?: throw IllegalStateException("UID карты не прочитан")
        val cardId = prev?.optString("cardId")?.takeIf { it.isNotBlank() }
            ?: UuidCreator.getTimeOrderedEpoch().toString()

        fun field(id: String?, prev: String?): String = (id ?: prev) ?: ""

        val roles = JSONArray().put(type.role)
        return JSONObject().apply {
            put("cardId", cardId)
            put("uid", uid)
            put("regionId", field(s.selectedRegionId, prev?.optString("regionId")))
            put("organizerId", field(s.selectedOrganizerId, prev?.optString("organizerId")))
            put("carrierId", field(s.selectedCarrierId, prev?.optString("carrierId")))
            put("cardsDistributorId", field(s.selectedDistributorId, prev?.optString("cardsDistributorId")))
            put("auditServiceId", field(s.selectedAuditServiceId, prev?.optString("auditServiceId")))
            put("userId", field(s.selectedUserId, prev?.optString("userId")))
            put("roles", roles)
        }
    }

    /** Компактный canonical string (ключи в фиксированном порядке, без пробелов). */
    fun canonicalString(json: JSONObject): String {
        val keys = listOf(
            "cardId", "uid", "regionId", "organizerId", "carrierId",
            "cardsDistributorId", "auditServiceId", "userId", "roles"
        )
        return keys.joinToString(",") { key ->
            if (key == "roles") {
                val arr = json.optJSONArray(key)
                val items = if (arr != null) {
                    (0 until arr.length()).joinToString(",") { "\"${escapeJson(arr.optString(it))}\"" }
                } else ""
                "\"roles\":[$items]"
            } else {
                val raw = json.optString(key)
                "\"$key\":\"${escapeJson(raw)}\""
            }
        }.let { "{$it}" }
    }

    private fun escapeJson(s: String): String = s
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")

    /** POST sign → activate → прошивка. */
    fun runActivation() {
        val s = _state.value
        if (s.busy || s.finalResult != null || heldTag == null) {
            if (heldTag == null) _state.update { it.copy(message = "Приложите целевую карту") }
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val s1 = _state.value
                val isClassic = (s1.pendingWriteTech == CardTech.CLASSIC)
                _state.update { it.copy(busy = true,
                    message = if (isClassic) "Регистрация VCM1 на сервере…" else "Формирование подписи…") }
                addReceipt("Отправка запроса на сервер")

                val identity = buildCanonicalIdentity(s1)
                val canonical = canonicalString(identity)

                val uid = identity.optString("uid")
                addReceipt("UID=$uid")
                val s2 = _state.value
                val roleEnum = s2.cardType
                    ?: throw IllegalStateException("тип карты не выбран")

                if (isClassic) {
                    // Промпт 008/009: VCM1-flow — без signature, server-master cardId.
                    // Терминал генерирует placeholder cardId (UUID v7), сервер возвращает
                    // свой masterCardId если UID уже зарегистрирован.
                    val clientCardId = UuidCreator.getTimeOrderedEpoch().toString()
                    // Промпт 009 clean-break: entityType всегда "userId" (или "none" для
                    // PASSENGER_ANONYMOUS). entityId = selectedUserId из cascade dropdown UI.
                    val entityType = if (roleEnum == ru.asop.terminal.activation.AsopCardType.PASSENGER_ANONYMOUS) "none" else "userId"
                    val entityId = if (roleEnum == ru.asop.terminal.activation.AsopCardType.PASSENGER_ANONYMOUS) null
                        else s2.selectedUserId
                    Log.i("CardActivationVM", "VCM1-activate: clientCardId=$clientCardId, uid=$uid, bitmask=0x${"0x"}${(1 shl roleEnum.ordinal).toString(16)}, entity=$entityType/$entityId")
                    val vcm1 = CardActivateClassicRequest(
                        technology = "CLASSIC",
                        cardId = clientCardId,
                        uid = uid,
                        bitmask = 1 shl roleEnum.ordinal,  // simplified: single-role; multi-role UI adds later
                        entityType = entityType,
                        entityId = entityId
                    )
                    val activateReq = CardActivateRequest(
                        cardIdentity = null,
                        identityJson = null,
                        identitySignature = null,
                        operatorRoles = s2.operatorRoles,
                        authorizedByRoot = s2.authorizedByRoot,
                        rootUserId = s2.rootUserId,
                        technology = "CLASSIC",
                        vcm1 = vcm1
                    )
                    val activateResp = syncApi.activateCardVcm1(activateReq)
                    if (!activateResp.isSuccessful) {
                        throw IllegalStateException("VCM1-activate HTTP ${activateResp.code()}")
                    }
                    val body = activateResp.body()
                        ?: throw IllegalStateException("VCM1-activate: пустой ответ")
                    val vcm1Data = body.vcm1
                        ?: throw IllegalStateException("VCM1-activate: response missing vcm1 subobject")
                    Log.i("CardActivationVM", "VCM1-activate OK: serverCardId=${body.cardId}, overridden=${vcm1Data.cardIdOverridden}")
                    addReceipt("Сервер ответил 200 OK (cardId=${body.cardId.take(8)}…, bitmask=0x${vcm1Data.bitmask.toString(16)})")

                    _state.update {
                        it.copy(serverRegistered = true, busy = false,
                            message = if (vcm1Data.cardIdOverridden)
                                "Сервер переназначил cardId. Приложите карту повторно для прошивки."
                            else "Карта зарегистрирована. Приложите карту повторно для прошивки.")
                    }
                    TonePlayer.readyBeep() // подсказка "приложите для прошивки"
                    addReceipt("Подсказка: приложите карту повторно для записи VCM1 на физический чип")
                    val entityRef = entityId?.let {
                        ru.asop.terminal.activation.EntityRef(
                            type = ru.asop.terminal.activation.EntityType.fromFieldName(entityType)!!,
                            id = java.util.UUID.fromString(it)
                        )
                    }
                    pendingWriteVcm1 = ru.asop.terminal.activation.CardIdentityVcm1(
                        cardId = java.util.UUID.fromString(body.cardId),
                        bitmask = vcm1Data.bitmask,
                        entity = entityRef
                    )
                    if (vcm1Data.cardIdOverridden) {
                        pendingServerCardIdOverride = body.cardId
                        Log.w("CardActivationVM",
                            "Server overrode cardId: clientCardId=$clientCardId → serverCardId=${body.cardId}. " +
                            "Terminal will re-write block 1 with serverCardId.")
                    }
                } else {
                    // DESFire-flow: signature + identityJson (legacy).
                    // Промпт: если UID уже зарегистрирован на сервере — server-master cardId
                    // переопределяет клиентский. canonical/подпись должны строиться ПОСЛЕ override,
                    // иначе на карту прошьётся подпись над устаревшим cardId.
                    val existingCardId = runCatching {
                        val resp = syncApi.getCardByUid(uid)
                        if (resp.isSuccessful) resp.body()?.cardId else null
                    }.getOrNull()
                    val finalIdentity: org.json.JSONObject
                    val finalCanonical: String
                    if (existingCardId != null) {
                        identity.put("cardId", existingCardId)
                        finalIdentity = identity
                        finalCanonical = canonicalString(finalIdentity)
                    } else {
                        finalIdentity = identity
                        finalCanonical = canonical
                    }

                    val signResp = syncApi.signCardIdentity(CardIdentitySignRequest(finalCanonical))
                    if (!signResp.isSuccessful) throw IllegalStateException("sign HTTP ${signResp.code()}")
                    val signatureBase64 = signResp.body()?.signatureBase64
                        ?: throw IllegalStateException("пустая подпись")

                    _state.update { it.copy(message = "Регистрация на сервере…") }
                    if (existingCardId == null) {
                        val role = roleEnum.role
                        val activateResp = syncApi.activateCard(
                            CardActivateRequest(
                                cardIdentity = CardIdentity(
                                    cardId = finalIdentity.optString("cardId"),
                                    uid = finalIdentity.optString("uid"),
                                    regionId = finalIdentity.optString("regionId").takeIf { it.isNotBlank() },
                                    organizerId = finalIdentity.optString("organizerId").takeIf { it.isNotBlank() },
                                    carrierId = finalIdentity.optString("carrierId").takeIf { it.isNotBlank() },
                                    cardsDistributorId = finalIdentity.optString("cardsDistributorId").takeIf { it.isNotBlank() },
                                    auditServiceId = finalIdentity.optString("auditServiceId").takeIf { it.isNotBlank() },
                                    userId = finalIdentity.optString("userId").takeIf { it.isNotBlank() },
                                    roles = listOf(role)
                                ),
                                identityJson = finalCanonical,
                                identitySignature = signatureBase64,
                                operatorRoles = s2.operatorRoles,
                                authorizedByRoot = s2.authorizedByRoot,
                                rootUserId = s2.rootUserId,
                                technology = "DESFIRE"
                            )
                        )
                        if (!activateResp.isSuccessful) throw IllegalStateException("activate HTTP ${activateResp.code()}")
                    }
                    _state.update { it.copy(serverRegistered = true, busy = false,
                        message = "Карта зарегистрирована. Приложите карту повторно для прошивки.") }

                    val identityJson = JSONObject(finalCanonical)
                    val identityProto = CardIdentityCodec.fromJson(identityJson)
                    pendingWriteProto = identityProto.toByteArray()
                    pendingWriteSignature = signatureBase64
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        step = Step.Error,
                        busy = false,
                        finalOk = false,
                        finalResult = "Ошибка активации: ${e.message}"
                    )
                }
                addReceipt("Ошибка активации на сервере: ${e.message}")
                TonePlayer.errorBeep()
            }
        }
    }

    /**
     * Convert role + UiState в (entityType, entityId) tuple для VCM1.
     * Single-slot: берём highest-bit role entity field. Для PASSENGER_ANONYMOUS — null.
     */
    private fun resolveEntityFields(
        role: ru.asop.terminal.activation.AsopCardType,
        identity: org.json.JSONObject
    ): Pair<String, String?> {
        // Промпт 009 clean-break: entity на КАРТЕ всегда = userId. Все *_Id/orgId/etc.
        // не хранятся на карте — routing делается через ASOP_USER_REGIONS / ASOP_USER_CARRIERS
        // на стороне запроса. Только PASSENGER_ANONYMOUS — entity=null.
        if (role == ru.asop.terminal.activation.AsopCardType.PASSENGER_ANONYMOUS) {
            return "none" to null
        }
        return "userId" to identity.optString("userId").takeIf { it.isNotBlank() }
    }

    /** Прошивка с повторно приложенной картой (свежий тег). Для VCM1: используем serverCardId если был override. */
    private fun runWrite(tag: Tag) {
        val vcm1 = pendingWriteVcm1 ?: return
        val tech = _state.value.pendingWriteTech ?: CardTech.DESFIRE
        Log.d("CardActivationVM", "runWrite: starting, tech=$tech, bitmask=0x${vcm1.bitmask.toString(16)}")
        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(busy = true, message = "Прошивка…") }
            val writeResult = try {
                withTimeout(120_000) {
                    when (tech) {
                        CardTech.CLASSIC -> provisionClassicCard(tag, vcm1)
                        CardTech.DESFIRE -> provisionDesfireCard(tag, vcm1)  // legacy
                        CardTech.UNSUPPORTED -> "технология карты не поддерживается этим flow"
                    }
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                Log.w("CardActivationVM", "runWrite: timeout 120s")
                "таймаут — карта не отвечает"
            } catch (e: Exception) {
                Log.w("CardActivationVM", "runWrite: exception ${e.javaClass.simpleName}: ${e.message}")
                "${e.javaClass.simpleName}: ${e.message}"
            } finally {
                Log.d("CardActivationVM", "runWrite: completed")
            }
            val ok = writeResult == null
            val role = _state.value.cardType?.role ?: ""
            val msg = when {
                writeResult == null -> "Карта $role успешно активирована"
                else -> "Карта зарегистрирована, но запись не удалась: $writeResult. Повторите прикладывание."
            }
pendingWriteVcm1 = null
            addReceipt(if (ok) "Карта успешно записана" else "Запись карты не удалась: $writeResult")
            // При успехе переходим в Step.Success — UI показывает зелёную галочку
            // и останавливает любые последующие операции (см. onTagDiscovered/processTargetCard).
            _state.update {
                it.copy(
                    busy = false,
                    cardWritten = ok,
                    finalOk = ok,
                    finalResult = msg,
                    step = if (ok) Step.Success else it.step,
                )
            }

            // Промпт 008 UX: финальный сигнал один раз, после всей работы с картой.
            if (ok) TonePlayer.successBeep() else TonePlayer.errorBeep()
        }
    }

    /** Legacy DESFire-flow: оставлено для совместимости с уже-активированными картами. */
    private suspend fun provisionDesfireCard(tag: Tag, vcm1: CardIdentityVcm1): String? {
        // DESFire не использует VCM1 — fallback на existing flow identityJson/signature.
        val writer = DesfireCardWriter()
        val keys = terminalKeyDao.getActive(5)
        val newestKey = keys.firstOrNull()?.let { terminalKeyCryptor.decrypt(it.keyMaterialEnc) }
            ?: return "нет 3DES-ключей в terminal_keys"
        val mode = _state.value.targetCardMode
        // Тут мы держим DEPRIORITIZED path: реальный current flow для DESFire — через
        // pendingWriteProto/pendingWriteSignature, не pendingWriteVcm1.
        return when (mode) {
            "existing" -> "DESFire-flow в VCM1-режиме пока не реализован — используйте ACTIVATE-LEGACY"
            else -> writer.writeIdentity(
                tag,
                ByteArray(0),  // unused — caller goes via legacy flow
                "",           // unused signature base64
                ZERO_KEY,
                newestKey
            ).takeUnless { it.ok }?.error
        }
    }

    /** Прошивка MIFARE Classic: VCM1 (промпт 008) — без signature, только sector 1. */
    private suspend fun provisionClassicCard(tag: Tag, vcm1: CardIdentityVcm1): String? {
        val writer = MifareClassicCardWriter()
        val keys = terminalKeyDao.getActive(5)
        val newestKey = keys.firstOrNull()?.let { terminalKeyCryptor.decrypt(it.keyMaterialEnc) }
            ?: return "нет АСОП-ключей в terminal_keys"
        if (newestKey.size < 12) return "АСОП-ключ короче 12 байт — для Classic нужно keyA(6)+keyB(6)"

        val keyA = newestKey.copyOfRange(0, 6)
        val keyB = newestKey.copyOfRange(6, 12)
        val isExisting = _state.value.targetCardMode == "existing"
        val oldKeyA = _state.value.workingKeyClassicA
        val oldKeyB = _state.value.workingKeyClassicB
        // Note: VCM1 пишет ТОЛЬКО sector 1 (3 data-блока + trailer если не existing).
        // Никаких signature, никаких sectors 2-15. Это и есть ключевое упрощение промпта 008:
        // карта читается даже с broken CRYPTO1 clone (только sector 1 = 3 blocks).
        return writer.writeVcm1(
            tag = tag,
            vcm1 = vcm1,
            keyA = keyA,
            keyB = keyB,
            isExisting = isExisting,
            workingKeyA = oldKeyA ?: keyA,
            workingKeyB = oldKeyB ?: keyB
        ).takeUnless { it.ok }?.error
    }

    /** Возврат к списку типов. */
    fun reset() {
        heldTag = null
        _state.value = UiState()
    }

    /**
     * Промпт 008: запись в receipt-timeline (для отображения на экране "Чек операции").
     * Используется между шагами, никаких промежуточных звуков.
     */
    private fun addReceipt(text: String) {
        _state.update {
            val stamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
            it.copy(
                receiptEntries = it.receiptEntries + ReceiptEntry(stamp, text)
            )
        }
    }

    /** Чек-операции: одна строка с временем. Рендерится в [ReceiptCard]. */
    data class ReceiptEntry(val time: String, val text: String)

    // ========== MIFARE Classic support (промпт 007) ==========

    /**
     * Классическая авторизация (вызывается через identifyAuthCard → identifyClassicAuthCard).
     * Прочитать SAC1 авторизующей карты, извлечь роли и рабочие ключи.
     */

    private fun parseSac1(payload: MifareClassicCardWriter.Sac1Payload): ProtoCardIdentity? {
        return runCatching { ProtoCardIdentity.parseFrom(payload.protoBytes) }.getOrNull()
    }

    private fun isSac1Trusted(@Suppress("UNUSED_PARAMETER") payload: MifareClassicCardWriter.Sac1Payload): Boolean {
        // В прототипе доверяем структуре; signatureVerification при идентификации auth
        // карт не строгая (на сервере уже делается повторная). Здесь — sanity check по magic/version,
        // который уже сделал parseSac1Payload в MifareClassicCardWriter.
        return true
    }

    /**
     * Целевая карта Classic: определяем state, читаем prev identity.
     */
    private fun processClassicTargetCard(tag: Tag) {
        viewModelScope.launch(Dispatchers.IO) {
            heldTag = tag
            _state.update { it.copy(busy = true, message = "Определение состояния карты (MIFARE Classic)…") }
            val uid = tag.id.joinToString("") { String.format("%02X", it) }
            val writer = MifareClassicCardWriter()

            val keys = terminalKeyDao.getActive(30)
            val candidates = keys.map { terminalKeyCryptor.decrypt(it.keyMaterialEnc) }

            val detect = writer.detectState(tag, candidates)
            Log.d("CardActivationVM", "processClassicTargetCard: detect=$detect")
            when (detect.state) {
                MifareClassicCardWriter.ClassicState.NEW -> {
                    _state.update {
                        it.copy(
                            busy = false,
                            targetCardUid = uid,
                            targetCardMode = "new",
                            workingKeyClassicA = null,
                            workingKeyClassicB = null,
                            pendingWriteTech = CardTech.CLASSIC,
                            message = "Карта новая (Classic). Заполните поля"
                        )
                    }
                    loadReferenceData()
                }
                MifareClassicCardWriter.ClassicState.EXISTING -> {
                    // Используем matchedKey от detectState (НЕ candidates.firstOrNull()).
                    // Если matchedKey = NULL (6 нулей — карта была записана с ZERO_KEY),
                    // используем candidates.first() AS OP для reflash (самая новая ASOP-версия).
                    val workingKey24: ByteArray = when {
                        detect.matchedKind == MifareClassicCardWriter.DetectResult.MatchKind.NULL_KEYA -> {
                            // Карта имеет KeyA=00 00 00 00 00 00 (от предыдущей активации с ZERO_KEY).
                            // Берём самый новый ASOP-ключ (он будет записан как НОВЫЙ keyA при reflash).
                            candidates.firstOrNull { it.size >= 12 } ?: byteArrayOf()
                        }
                        detect.matchedKind == MifareClassicCardWriter.DetectResult.MatchKind.ASOP_KEYA ||
                        detect.matchedKind == MifareClassicCardWriter.DetectResult.MatchKind.ASOP_KEYB -> {
                            // Карта имеет keyA/keyB от ASOP-ключа, который мы знаем.
                            detect.matchedKey ?: candidates.firstOrNull { it.size >= 12 } ?: byteArrayOf()
                        }
                        else -> candidates.firstOrNull { it.size >= 12 } ?: byteArrayOf()
                    }
                    val (keyA, keyB) = if (workingKey24.size >= 12) {
                        workingKey24.copyOfRange(0, 6) to workingKey24.copyOfRange(6, 12)
                    } else {
                        byteArrayOf() to byteArrayOf()
                    }

                    val vcm1Parsed = writer.readVcm1(tag, candidates)
                    if (vcm1Parsed == null) {
                        // VCM1 magic не нашёлся — возможно SAC1 legacy format, попробуем как фоллбэк
                        // через readStream для обратной совместимости. Но новой активации SAC1 карт
                        // больше не делаем — re-write в VCM1 нужен (промпт 008 clean-break).
                        val sac1 = writer.readStream(tag, candidates)
                        if (sac1 != null) {
                            Log.w("CardActivationVM", "processClassicTargetCard: found legacy SAC1 format — re-activation required (clean-break)")
                            _state.update {
                                it.copy(
                                    busy = false,
                                    targetCardUid = uid,
                                    message = "Карта в устаревшем формате SAC1. Требуется re-activation в VCM1."
                                )
                            }
                            return@launch
                        }
                        Log.w("CardActivationVM", "processClassicTargetCard: existing+null VCM1 — partial-write state, allowing reflash")
                        _state.update {
                            it.copy(
                                busy = false,
                                targetCardUid = uid,
                                targetCardMode = "existing",
                                workingKeyClassicA = keyA.takeIf { it.size == 6 },
                                workingKeyClassicB = keyB.takeIf { it.size == 6 },
                                previousIdentityJson = null,
                                previousRegionId = null,
                                previousOrganizerId = null,
                                previousCarrierId = null,
                                previousDistributorId = null,
                                previousAuditServiceId = null,
                                previousUserId = null,
                                pendingWriteTech = CardTech.CLASSIC,
                                message = "Карта частично прошита (Classic, matched=${detect.matchedKind}). Перезапишите VCM1 (обновите поля и нажмите «Активировать»)."
                            )
                        }
                        loadReferenceData()
                        return@launch
                    }
                    // VCM1 успешно прочитан. Достаём bitmask+entity для UI-prefill.
                    val primaryRole = AsopCardType.highestSetBitRole(vcm1Parsed.bitmask)
                    val entity = vcm1Parsed.entity
                    val prevJson = "{} ".let { _ ->
                        org.json.JSONObject().apply {
                            put("cardId", vcm1Parsed.cardId.toString())
                            put("uid", uid)
                            // Промпт 009: только USER или NONE на карте.
                            if (entity?.type == EntityType.USER) {
                                put("userId", entity.id.toString())
                            }
                            // PASSENGER_ANONYMOUS: entity=null → пустой JSON.
                            put("roles", org.json.JSONArray().apply {
                                AsopCardType.allRolesForBitmask(vcm1Parsed.bitmask).forEach { put(it.role) }
                            })
                        }.toString()
                    }
                    _state.update {
                        it.copy(
                            busy = false,
                            targetCardUid = uid,
                            targetCardMode = "existing",
                            workingKeyClassicA = keyA.takeIf { it.size == 6 },
                            workingKeyClassicB = keyB.takeIf { it.size == 6 },
                            previousIdentityJson = prevJson,
                            // Промпт 009: на карте остаётся только USER. Остальные *_ID
                            // восстанавливаются из ASOP_USER_* связей при чтении, если нужно.
                            previousUserId = if (entity?.type == EntityType.USER) entity.id.toString() else null,
                            pendingWriteTech = CardTech.CLASSIC,
                            message = "Карта VCM1 (primary=" + (primaryRole?.label ?: "n/a") + ", bitmask=0x" +
                                vcm1Parsed.bitmask.toString(16) + ")"
                        )
                    }
                    Log.i("CardActivationVM", "processClassicTargetCard: VCM1 read OK — primary=" +
                        (primaryRole?.label ?: "n/a") + ", entity=" + entity)
                    addReceipt("Карта прочитана: ${detect.state}, primary=${primaryRole?.label ?: "n/a"}, ${if (entity != null) "entity=$entity" else "entity=—"}")
                    loadReferenceData()
                }
                MifareClassicCardWriter.ClassicState.UNRECOGNIZED -> {
                    _state.update {
                        it.copy(busy = false,
                            message = "Карта не идентифицирована — factory/ASOP/NULL auth все fail. Возможно старая карта или ключи не подходят.")
                    }
                }
            }
        }
    }
}