package ru.asop.payment

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.MainThread
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import ru.asop.payment.core.CardProbe
import ru.asop.payment.core.LocalPaymentServer
import ru.asop.payment.core.PayProcessor
import ru.asop.payment.core.PayRequest
import ru.asop.payment.core.PendingPaymentStore
import ru.asop.payment.handoff.PaymentHandoff

/**
 * Точка входа app-payment:
 * - ACTION_MAIN → экран статуса/демо (PoC);
 * - ACTION_PAY (handoff от терминала) → обрабатывает платеж, результат сохраняется
 *   в Room (терминал поллит локальный GET /status/{requestId} — warm-path §3.6).
 */
class MainActivity : ComponentActivity() {

    private val tag = "PaymentMain"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (intent?.action == PaymentHandoff.ACTION_PAY) {
            handleHandoff(intent ?: return, this)
            return
        }

        setContent {
            MaterialTheme {
                val vm = androidx.lifecycle.viewmodel.compose.viewModel<PoCViewModel>()
                StatusScreen(vm, applicationContext)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == PaymentHandoff.ACTION_PAY) {
            handleHandoff(intent, this)
        }
    }

    @MainThread
    private fun handleHandoff(intent: Intent, context: Context) {
        val request = PaymentHandoff.readRequest(intent)
        if (request == null) {
            Log.e(tag, "handoff without valid request")
            setResult(RESULT_CANCELED, Intent(intent))
            finish()
            return
        }
        Log.i(tag, "handoff pay: ${request.requestId} amount=${request.amount} type=${request.paymentType}")
        val processor = PayProcessor(context)
        val store = PendingPaymentStore.get(context)
        // В PoC-моде результат мгновенный (mock EMV). В боевом EMV здесь экран
        // «Приложите карту» → результат по завершении аппрува/таймаута.
        val response = runBlocking { processor.process(request) }
        runBlocking { store.persist(response, request.paymentType) }
        Log.i(tag, "handoff result: ${response.status} paymentId=${response.paymentId}")
        val result = PaymentHandoff.writeResult(Intent(intent), response)
        setResult(RESULT_OK, result)
        finish()
    }
}

/** PoCViewModel с привязкой applicationContext (демо-экран). */
class PoCViewModel : ViewModel() {
    private var appContext: Context? = null
    private val _state = MutableStateFlow(StatusUiState())
    val state: StateFlow<StatusUiState> = _state.asStateFlow()

    var amountText by mutableStateOf("30.00")
        private set

    fun attach(context: Context) {
        if (appContext == null) appContext = context.applicationContext
    }

    fun setAmount(v: String) { amountText = v }

    fun refresh() {
        val c = appContext ?: return
        viewModelScope.launch {
            val probe = CardProbe.get(c).checkNfc()
            _state.value = _state.value.copy(
                probeCode = probe.checkCode,
                probeExists = probe.isExist,
                probeError = probe.error,
                log = "NFC check: code=${probe.checkCode} exist=${probe.isExist} err=${probe.error}"
            )
        }
    }

    fun pay() {
        val c = appContext ?: return
        val amount = amountText.toDoubleOrNull() ?: return
        viewModelScope.launch {
            val request = PayRequest(
                requestId = com.github.f4b6a3.uuid.UuidCreator.getTimeOrderedEpoch().toString(),
                amount = amount,
                currency = "RUB",
                paymentType = "FARE",
                capture = true,
                sessionId = null,
                transactionId = null,
                message = "PoC demo"
            )
            val response = PayProcessor(c).process(request)
            PendingPaymentStore.get(c).persist(response)
            _state.value = _state.value.copy(log = response.toJson())
        }
    }
}

data class StatusUiState(
    val probeCode: Int = -1,
    val probeExists: Boolean = false,
    val probeError: String? = null,
    val log: String = "—"
)

@Composable
private fun StatusScreen(vm: PoCViewModel, context: Context) {
    androidx.compose.runtime.LaunchedEffect(Unit) { vm.attach(context) }
    Scaffold(modifier = Modifier.fillMaxSize()) { pad ->
        Column(
            modifier = Modifier
                .padding(pad)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("ASOP Payment ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Локальный сервер: 127.0.0.1:${LocalPaymentServer.PORT}  " +
                        "mock EMV=${BuildConfig.PO_C_MOCK_EMV}",
                style = MaterialTheme.typography.bodyMedium
            )
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("NFC (FTSDK NfcReader)", style = MaterialTheme.typography.titleMedium)
                    val s = vm.state.value
                    Text("checkNFCCardreader=${s.probeCode}  isExist=${s.probeExists}")
                    s.probeError?.let { Text("err: $it") }
                    Button(onClick = { vm.refresh() }) { Text("Проверить NFC") }
                }
            }
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Демо /pay (mock EMV)", style = MaterialTheme.typography.titleMedium)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = vm.amountText, onValueChange = { vm.setAmount(it) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Button(onClick = { vm.pay() }, modifier = Modifier.fillMaxWidth()) {
                        Text("Выполнить платёж")
                    }
                }
            }
            Text("Последний результат:", style = MaterialTheme.typography.labelLarge)
            Text(vm.state.value.log, style = MaterialTheme.typography.bodySmall)
        }
    }
}