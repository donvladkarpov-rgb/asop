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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ru.asop.terminal.cert.MtlsManager
import ru.asop.terminal.db.SyncPreferences
import ru.asop.terminal.network.GatewayApi
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

    fun isCertificateReady(): Boolean = mtlsManager.hasCertificate()

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

    fun registerTerminal(model: String?, number: String?) {
        viewModelScope.launch {
            _state.value = UiState.Registering
            try {
                val savedTerminalId = terminalId.value
                val registerResponse = gatewayApi.registerTerminal(
                    TerminalRegisterRequest(
                        terminalSerial = androidId,
                        terminalNumber = number,
                        terminalModel = model,
                        terminalId = savedTerminalId
                    )
                )
                val terminal = registerResponse.terminal
                syncPreferences.setTerminalId(terminal.id)
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

    private fun getAndroidId(application: Application): String =
        Settings.Secure.getString(application.contentResolver, Settings.Secure.ANDROID_ID)
            ?: throw IllegalStateException("ANDROID_ID не доступен")
}
