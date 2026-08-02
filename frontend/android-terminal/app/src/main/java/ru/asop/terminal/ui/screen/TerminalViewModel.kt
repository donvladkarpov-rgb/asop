package ru.asop.terminal.ui.screen

import android.app.Application
import android.provider.Settings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ru.asop.terminal.cert.MtlsManager
import ru.asop.terminal.db.SyncPreferences
import ru.asop.terminal.network.GatewayApi
import ru.asop.terminal.network.models.CarrierResponse
import ru.asop.terminal.network.models.RegionResponse
import ru.asop.terminal.network.models.TerminalCarrierAssignRequest
import ru.asop.terminal.network.models.TerminalRegisterRequest
import ru.asop.terminal.network.models.TerminalResponse
import ru.asop.terminal.service.CertificateService
import javax.inject.Inject

@HiltViewModel
class TerminalViewModel @Inject constructor(
    private val application: Application,
    private val mtlsManager: MtlsManager,
    private val certificateService: CertificateService,
    private val gatewayApi: GatewayApi,
    private val syncPreferences: SyncPreferences
) : ViewModel() {

    sealed class UiState {
        data object Idle : UiState()
        data object Provisioning : UiState()
        data object Registering : UiState()
        data object Ready : UiState()
        data class Registered(val terminal: TerminalResponse) : UiState()
        data class Error(val message: String) : UiState()
    }

    private val _state = MutableStateFlow<UiState>(UiState.Idle)
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _terminalInfo = MutableStateFlow<TerminalResponse?>(null)
    val terminalInfo: StateFlow<TerminalResponse?> = _terminalInfo.asStateFlow()

    val terminalId: StateFlow<String?> = syncPreferences.terminalId
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val androidId: String = getAndroidId(application)

    private val _regions = MutableStateFlow<List<RegionResponse>>(emptyList())
    val regions: StateFlow<List<RegionResponse>> = _regions.asStateFlow()

    private val _carriers = MutableStateFlow<List<CarrierResponse>>(emptyList())
    val carriers: StateFlow<List<CarrierResponse>> = _carriers.asStateFlow()

    fun isCertificateReady(): Boolean = mtlsManager.hasCertificate()

    suspend fun getStoredTerminalId(): String? = syncPreferences.terminalId.first()

    fun autoProvision() {
        viewModelScope.launch {
            _state.value = UiState.Provisioning
            try {
                certificateService.provision(androidId)
                _state.value = UiState.Ready
            } catch (e: Exception) {
                _state.value = UiState.Error(e.message ?: "Ошибка получения сертификата")
            }
        }
    }

    fun registerTerminal(
        regionId: String?,
        carrierId: String?,
        timezone: String,
        model: String?,
        number: String?
    ) {
        viewModelScope.launch {
            _state.value = UiState.Registering
            try {
                val savedTerminalId = terminalId.value
                val registerResponse = gatewayApi.registerTerminal(
                    TerminalRegisterRequest(
                        terminalSerial = androidId,
                        terminalNumber = number,
                        terminalModel = model,
                        carrierId = carrierId,
                        timezone = timezone,
                        terminalId = savedTerminalId
                    )
                )
                val terminal = registerResponse.terminal
                syncPreferences.setTerminalId(terminal.id)
                syncPreferences.setCarrierId(carrierId)
                syncPreferences.setRegionId(regionId)
                syncPreferences.setTimezone(timezone)
                _terminalInfo.value = terminal
                _state.value = UiState.Registered(terminal)
            } catch (e: Exception) {
                _state.value = UiState.Error(e.message ?: "Ошибка регистрации")
            }
        }
    }

    fun loadTerminal(id: String) {
        viewModelScope.launch {
            try {
                val response = gatewayApi.getTerminal(id)
                _terminalInfo.value = response
                _state.value = UiState.Registered(response)
            } catch (e: Exception) {
                _state.value = UiState.Error(e.message ?: "Ошибка загрузки данных")
            }
        }
    }

    fun loadReferenceData() {
        viewModelScope.launch {
            try {
                _regions.value = gatewayApi.listRegions()
            } catch (e: Exception) {
                _state.value = UiState.Error(e.message ?: "Ошибка загрузки регионов")
            }
        }
    }

    fun loadCarriersForRegion(regionId: String) {
        viewModelScope.launch {
            try {
                _carriers.value = gatewayApi.listCarriers(regionId)
            } catch (e: Exception) {
                _state.value = UiState.Error(e.message ?: "Ошибка загрузки перевозчиков")
            }
        }
    }

    fun assignCarrier(carrierId: String?) {
        viewModelScope.launch {
            try {
                val id = terminalId.value ?: return@launch
                val response = gatewayApi.assignCarrier(id, TerminalCarrierAssignRequest(carrierId))
                _terminalInfo.value = response
            } catch (e: Exception) {
                _state.value = UiState.Error(e.message ?: "Ошибка привязки перевозчика")
            }
        }
    }

    fun regenerateCert() {
        viewModelScope.launch {
            try {
                mtlsManager.resetKeyAndCert()
                _state.value = UiState.Provisioning
                certificateService.provision(androidId)
                _state.value = UiState.Ready
            } catch (e: Exception) {
                _state.value = UiState.Error(e.message ?: "Ошибка перевыпуска сертификата")
            }
        }
    }

    private fun getAndroidId(application: Application): String =
        Settings.Secure.getString(application.contentResolver, Settings.Secure.ANDROID_ID)
            ?: throw IllegalStateException("ANDROID_ID не доступен")
}
