package ru.asop.terminal.ui.screen

import android.app.Application
import android.content.Context
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import ru.asop.terminal.activation.AsopCardType
import ru.asop.terminal.activation.CardActivationMatrix
import ru.asop.terminal.db.TerminalKeyCryptor
import ru.asop.terminal.db.dao.ReferenceRowDao
import ru.asop.terminal.db.dao.TerminalKeyDao
import ru.asop.terminal.nfc.DesfireCardReader
import ru.asop.terminal.nfc.DesfireCardWriter
import ru.asop.terminal.nfc.TonePlayer
import ru.asop.terminal.network.SyncApi
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
    private val referenceRowDao: ReferenceRowDao
) : ViewModel() {

    companion object {
        // Master PICC ключ новой карты (фабричный) — 24 байта нулей (3K3DES).
        private val ZERO_KEY = ByteArray(24)
    }

    data class RefOption(val id: String, val label: String)

    enum class Step { NetworkCheck, RootForm, AuthForm, TargetCard, ReferenceForm, Busy, Done, Error }

    data class UiState(
        val cardType: AsopCardType? = null,
        val step: Step = Step.NetworkCheck,
        val message: String = "",
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

    val nfcAvailable: Boolean get() = nfcAdapter != null && (nfcAdapter?.isEnabled ?: false)

    private val humanTagsByStep = mapOf(
        Step.AuthForm to "Приложите авторизующую карту (свою)",
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
                    type.requiresRoot -> "Введите логин/пароль root-администратора"
                    else -> "Приложите авторизующую карту"
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
        _state.update { it.copy(busy = true, message = "Проверка root-учётной записи…") }
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
                            message = "Root авторизован. Приложите целевую КАРТУ"
                        )
                    }
                },
                onFailure = { e ->
                    _state.update { it.copy(busy = false, validatedError = "Root-логин не прошёл: ${e.message}") }
                }
            )
        }
    }

    // ---------- Шаг 3: NFC ----------

    fun onTagDiscovered(tag: Tag) {
        val s = _state.value
        if (s.busy || s.finalResult != null) return
        when (s.step) {
            Step.AuthForm -> identifyAuthCard(tag)
            Step.TargetCard -> processTargetCard(tag)
            else -> Unit
        }
    }

    /** Авторизующая карта: идентификация по ключам (9.5) → роли → role-check. */
    private fun identifyAuthCard(tag: Tag) {
        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(message = "Идентификация авторизующей карты…") }
            val writer = DesfireCardWriter()
            val iso = writer.open(tag)
            if (iso == null) {
                _state.update { it.copy(message = "Не удалось открыть IsoDep авторизующей карты") }
                return@launch
            }
            val roles = try { identifyCardRoles(iso) } finally { writer.close(iso) }
            if (roles == null) {
                _state.update { it.copy(message = "Не удалось прочитать авторизующую карту (ключ/identity)") }
                return@launch
            }
            val target = _state.value.cardType ?: return@launch
            if (CardActivationMatrix.canAuthorize(roles, target.role)) {
                _state.update {
                    it.copy(
                        operatorRoles = roles,
                        step = Step.TargetCard,
                        message = "Авторизация OK (${roles.joinToString()}). Приложите целевую КАРТУ"
                    )
                }
                TonePlayer.softBeep()
            } else {
                _state.update { it.copy(message = "Роль ${roles.joinToString()} не может активировать ${target.label}") }
            }
        }
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
                        runCatching { JSONObject(String(data, Charsets.UTF_8)) }.getOrNull()
                    }
                } else null
            } catch (e: Exception) { null }
            return Identified(key, identity)
        }
        return null
    }

    /** Целевая карта: диагностика пригодности → нулевой ключ → новая; иначе existing. */
    private fun processTargetCard(tag: Tag) {
        viewModelScope.launch(Dispatchers.IO) {
            heldTag = tag
            _state.update { it.copy(busy = true, message = "Определение состояния карты…") }
            val writer = DesfireCardWriter()
            val uid = tag.id.joinToString("") { String.format("%02X", it) }

            // === Шаг 1: проверка что карта — DESFire (GetVersion через IsoDep) ===
            val iso = IsoDep.get(tag)
            if (iso != null) {
                try {
                    iso.connect()
                    iso.timeout = 5000

                    // GetVersion (0x60) — базовая PICC-команда, должна отвечать на любой DESFire
                    val verResp = try { iso.transceive(byteArrayOf(0x60)) } catch (e: Exception) { null }
                    if (verResp == null || verResp.isEmpty()) {
                        // GetVersion не ответил — попробуем NfcA fallback
                        Log.w("TARGET", "IsoDep GetVersion failed, trying NfcA")
                        runCatching { iso.close() }
                        processViaNfcA(tag, uid, writer)
                        return@launch
                    }

                    // === Шаг 2: SelectApplication(мастер PICC) — проверка пригодности ===
                    val selResult = writer.selectApplicationDetailed(iso, byteArrayOf(0, 0, 0))
                    when (selResult) {
                        DesfireCardWriter.SelectResult.OK -> {
                            // SelectApplication прошла — карта функциональная DESFire
                            processWithIsoDep(tag, iso, uid, writer)
                            return@launch
                        }
                        DesfireCardWriter.SelectResult.IO_ERROR -> {
                            // Transceive failed — возможно клон без SelectApplication
                            Log.w("TARGET", "IsoDep SelectApplication IO_ERROR — clone?")
                            runCatching { iso.close() }
                            // Пробуем NfcA fallback
                            processViaNfcA(tag, uid, writer)
                            return@launch
                        }
                        DesfireCardWriter.SelectResult.UNSUPPORTED -> {
                            // 0x1C — команда не поддерживается
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
                            // Другой статус (0xAE, 0x7E и т.д.) — карта знает команду, но ошибка
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

            // IsoDep нет в techList — пробуем NfcA
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
            nfcA.timeout = 5000
            Log.d("TARGET", "Using NfcA (Layer 2) for card $uid")

            // GetVersion через NfcA
            val verResp = try { nfcA.transceive(byteArrayOf(0x60)) } catch (e: Exception) { null }
            if (verResp == null || verResp.isEmpty()) {
                _state.update {
                    it.copy(busy = false,
                        message = "Карта не отвечает на GetVersion. " +
                            "Карта не является DESFire или неисправна.")
                }
                return
            }

            // SelectApplication через NfcA
            val selResp = try { nfcA.transceive(byteArrayOf(0x5A, 0, 0, 0)) } catch (e: Exception) { null }
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

            regionByCarrier = readRawMap("asop_carriers") { it.optString("carrierId") to it.optString("regionId") }

            val s = _state.value
            _state.update {
                it.copy(
                    regions = regions, organizers = organizers,
                    carriers = carriers, distributors = distributors,
                    auditServices = auditServices, users = users,
                    step = Step.ReferenceForm,
                    selectedRegionId = s.previousRegionId ?: s.selectedRegionId,
                    selectedOrganizerId = s.previousOrganizerId ?: s.selectedOrganizerId,
                    selectedCarrierId = s.previousCarrierId ?: s.selectedCarrierId,
                    selectedDistributorId = s.previousDistributorId ?: s.selectedDistributorId,
                    selectedAuditServiceId = s.previousAuditServiceId ?: s.selectedAuditServiceId,
                    selectedUserId = s.previousUserId ?: s.selectedUserId
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
        val regionId = _state.value.selectedRegionId
        if (regionId.isNullOrBlank()) return _state.value.users
        return _state.value.users.filter { regionsOfUser[it.id]?.contains(regionId) == true }
    }

    /** Смена региона сбрасывает зависимые подчинённые выборы. */
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
    fun selectOrganizer(id: String) = _state.update { it.copy(selectedOrganizerId = id) }
    fun selectCarrier(id: String) = _state.update { it.copy(selectedCarrierId = id) }
    fun selectDistributor(id: String) = _state.update { it.copy(selectedDistributorId = id) }
    fun selectAuditService(id: String) = _state.update { it.copy(selectedAuditServiceId = id) }
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
                _state.update { it.copy(busy = true, message = "Формирование подписи…") }
                val identity = buildCanonicalIdentity(_state.value)
                val canonical = canonicalString(identity)

                // 1. Подпись crypto.
                val signResp = syncApi.signCardIdentity(CardIdentitySignRequest(canonical))
                if (!signResp.isSuccessful) throw IllegalStateException("sign HTTP ${signResp.code()}")
                val signatureBase64 = signResp.body()?.signatureBase64
                    ?: throw IllegalStateException("пустая подпись")

                // 2. Регистрация на сервере.
                _state.update { it.copy(message = "Регистрация на сервере…") }
                val s2 = _state.value
                val role = s2.cardType?.role ?: throw IllegalStateException("роль не определена")
                val activateResp = syncApi.activateCard(
                    CardActivateRequest(
                        cardIdentity = CardIdentity(
                            cardId = identity.optString("cardId"),
                            uid = identity.optString("uid"),
                            regionId = identity.optString("regionId").takeIf { it.isNotBlank() },
                            organizerId = identity.optString("organizerId").takeIf { it.isNotBlank() },
                            carrierId = identity.optString("carrierId").takeIf { it.isNotBlank() },
                            cardsDistributorId = identity.optString("cardsDistributorId").takeIf { it.isNotBlank() },
                            auditServiceId = identity.optString("auditServiceId").takeIf { it.isNotBlank() },
                            userId = identity.optString("userId").takeIf { it.isNotBlank() },
                            roles = listOf(role)
                        ),
                        identityJson = canonical,
                        identitySignature = signatureBase64,
                        operatorRoles = s2.operatorRoles,
                        authorizedByRoot = s2.authorizedByRoot,
                        rootUserId = s2.rootUserId
                    )
                )
                if (!activateResp.isSuccessful) throw IllegalStateException("activate HTTP ${activateResp.code()}")
                _state.update { it.copy(serverRegistered = true, message = "Карта зарегистрирована на сервере. Прошивка…") }

                // 3. Прошивка карты.
                val writeResult = provisionCard(canonical.toByteArray(Charsets.UTF_8), signatureBase64)
                val ok = writeResult == null
                val msg = when {
                    writeResult == null -> "Карта ${role} успешно активирована"
                    else -> "Карта зарегистрирована на сервере, но запись не удалась: $writeResult. Повторите прикладывание."
                }
                _state.update {
                    it.copy(
                        busy = false,
                        cardWritten = ok,
                        finalOk = ok,
                        finalResult = msg
                    )
                }
                if (ok) TonePlayer.softBeep()
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        busy = false,
                        finalOk = false,
                        finalResult = "Ошибка активации: ${e.message}"
                    )
                }
            }
        }
    }

    /** Возвращает null при успехе прошивки, иначе текст ошибки. */
    private suspend fun provisionCard(identityJson: ByteArray, signatureBase64: String): String? {
        val tag = heldTag ?: return "карта убрана с поля NFC"
        val writer = DesfireCardWriter()
        val keys = terminalKeyDao.getActive(5)
        val newestKey = keys.firstOrNull()?.let { terminalKeyCryptor.decrypt(it.keyMaterialEnc) }
            ?: return "нет 3DES-ключей в terminal_keys (загрузите справочники)"
        val mode = _state.value.targetCardMode

        return if (mode == "existing") {
            val oldKey = _state.value.workingKeyForCard ?: return "рабочий ключ не найден"
            writer.reflashComplete(tag, identityJson, signatureBase64, oldKey, newestKey)
                .takeUnless { it.ok }?.error
        } else {
            writer.writeIdentity(tag, identityJson, signatureBase64, ZERO_KEY, newestKey)
                .takeUnless { it.ok }?.error
        }
    }

    /** Возврат к списку типов. */
    fun reset() {
        heldTag = null
        _state.value = UiState()
    }
}