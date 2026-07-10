package ru.asop.terminal.ui.screen

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ru.asop.terminal.cert.MtlsManager
import ru.asop.terminal.network.GatewayApi
import ru.asop.terminal.network.models.TerminalRegisterRequest
import ru.asop.terminal.network.models.TerminalResponse
import ru.asop.terminal.service.CertificateService
import javax.inject.Inject

@HiltViewModel
class TerminalViewModel @Inject constructor(
    private val mtlsManager: MtlsManager,
    private val certificateService: CertificateService,
    private val gatewayApi: GatewayApi
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

    fun isCertificateReady(): Boolean = mtlsManager.hasCertificate()

    fun autoProvision() {
        viewModelScope.launch {
            _state.value = UiState.Provisioning
            try {
                certificateService.provision("terminal-${System.currentTimeMillis()}")
                _state.value = UiState.Ready
            } catch (e: Exception) {
                _state.value = UiState.Error(e.message ?: "Ошибка получения сертификата")
            }
        }
    }

    fun registerTerminal(serial: String, model: String?, number: String?) {
        viewModelScope.launch {
            _state.value = UiState.Registering
            try {
                val response = gatewayApi.registerTerminal(
                    TerminalRegisterRequest(
                        terminalSerial = serial,
                        terminalNumber = number,
                        terminalModel = model
                    )
                )
                _terminalInfo.value = response
                _state.value = UiState.Registered(response)
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
}
