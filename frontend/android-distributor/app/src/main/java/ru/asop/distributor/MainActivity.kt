package ru.asop.distributor

import android.app.Activity
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import ru.asop.distributor.core.KeyProvider
import ru.asop.distributor.core.PaymentClient
import ru.asop.distributor.core.TariffProvider
import ru.asop.distributor.core.TopUpViewModel

class MainActivity : ComponentActivity(), NfcAdapter.ReaderCallback {

    private lateinit var vm: TopUpViewModel
    private var nfc: NfcAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vm = TopUpViewModel(KeyProvider(), TariffProvider(), PaymentClient())
        nfc = NfcAdapter.getDefaultAdapter(this)

        setContent {
            MaterialTheme {
                Scaffold { padding ->
                    DistributorContent(vm, Modifier.padding(padding))
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        enableReader()
    }

    override fun onPause() {
        disableReader()
        super.onPause()
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        // reader-mode delivers tags via onTagDiscovered; intent путь не используется
    }

    private fun enableReader() {
        val adapter = nfc ?: return
        if (!adapter.isEnabled) {
            Toast.makeText(this, "NFC выключен", Toast.LENGTH_SHORT).show()
            return
        }
        adapter.enableReaderMode(
            this,
            this,
            NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
            null
        )
    }

    private fun disableReader() {
        nfc?.disableReaderMode(this)
    }

    override fun onTagDiscovered(tag: Tag) {
        if (::vm.isInitialized) vm.onTag(tag)
    }
}

@Composable
private fun DistributorContent(vm: TopUpViewModel, modifier: Modifier = Modifier) {
    val state = vm.state
    var amount by remember { mutableStateOf("90") }
    val context = LocalContext.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Дистрибьютор АСОП", style = MaterialTheme.typography.headlineSmall)
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(state.message, style = MaterialTheme.typography.bodyLarge)
                if (state.operatorCardId != null) {
                    Text("Оператор: ${state.operatorCardId.take(8)}…")
                    Text("Роли: ${state.operatorRoles.joinToString()}")
                }
                if (state.passengerCardId != null) {
                    Text("Карта пассажира: ${state.passengerCardId.take(8)}… (UID ${state.passengerUid})")
                    if (state.passengerTrips != null) {
                        Text("Поездок на карте: ${state.passengerTrips}")
                    }
                }
                if (state.payStatus != null) {
                    Text("Платёж: ${state.payStatus}", color = MaterialTheme.colorScheme.primary)
                }
                if (state.payError != null) {
                    Text("Ошибка: ${state.payError}", color = MaterialTheme.colorScheme.error)
                }
                if (state.newTrips != null) {
                    Text("Новый остаток: ${state.newTrips}")
                }
            }
        }

        when (state.step) {
            TopUpViewModel.Step.IDLE -> {
                Text("Шаг 1: приложите карту дистрибьютора (DISTRIBUTOR_ADMIN / DISTRIBUTOR_DISPATCHER)")
            }
            TopUpViewModel.Step.DISTRIBUTOR_SCAN,
            TopUpViewModel.Step.PASSENGER_SCAN -> {
                Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(); Text("чтение...") }
            }
            TopUpViewModel.Step.DISTRIBUTOR_AUTHED -> {
                if (state.canTopUp) {
                    Button(onClick = { vm.beginPassengerScan() }) {
                        Text("Приложить карту пассажира")
                    }
                } else {
                    OutlinedButton(onClick = { vm.reset() }) { Text("Назад") }
                }
            }
            TopUpViewModel.Step.PASSENGER_READ -> {
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("Сумма пополнения, руб") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = {
                        val a = amount.toDoubleOrNull() ?: run {
                            Toast.makeText(context, "Неверная сумма", Toast.LENGTH_SHORT).show(); 0.0
                        }
                        if (a > 0) vm.pay(a)
                    }) { Text("Оплатить картой") }
                    OutlinedButton(onClick = {
                        val a = amount.toDoubleOrNull() ?: 0.0
                        if (a > 0) vm.cashIndex(a)
                    }) { Text("Наличные") }
                    OutlinedButton(onClick = { vm.reset() }) { Text("Отмена") }
                }
                if (state.passengerTrips != null) {
                    Text("Будет добавлено поездок: ${amount.toDoubleOrNull()?.let { (it / 30.0).toInt().coerceAtLeast(1) } ?: 1} (тариф 30 ₽)")
                }
            }
            TopUpViewModel.Step.PAYING -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator()
                    Text("Ожидание ответа эквайринга...")
                }
            }
            TopUpViewModel.Step.DONE -> {
                Button(onClick = { vm.reset() }) { Text("Новый клиент") }
            }
        }
    }
}