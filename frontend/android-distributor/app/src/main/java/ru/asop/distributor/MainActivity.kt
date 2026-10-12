package ru.asop.distributor

import android.app.Activity
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import ru.asop.distributor.core.TopUpViewModel
import ru.asop.distributor.sync.ProvisionSyncManager
import java.text.SimpleDateFormat
import java.util.Locale

data class SyncUi(
    val busy: Boolean = false,
    val certReady: Boolean = false,
    val distributorId: String? = null,
    val keysCount: Int = 0,
    val tariffsCount: Int = 0,
    val lastSyncAtMs: Long = 0L,
    val message: String = ""
)

class MainActivity : ComponentActivity(), NfcAdapter.ReaderCallback {

    private val graph get() = (application as DistributorApp).graph
    private lateinit var vm: TopUpViewModel
    private var nfc: NfcAdapter? = null

    var syncUi by mutableStateOf(SyncUi())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vm = TopUpViewModel(
            graph.keyProvider,
            graph.tariffProvider,
            graph.paymentClient,
            graph.fiscalizerProvider,
            graph.distributorSyncApi
        )
        nfc = NfcAdapter.getDefaultAdapter(this)

        setContent {
            MaterialTheme {
                Scaffold { padding ->
                    DistributorContent(
                        vm = vm,
                        syncUi = syncUi,
                        defaultFare = graph.tariffProvider.defaultFare,
                        onSync = { runBootstrap() },
                        modifier = Modifier.padding(padding)
                    )
                }
            }
        }

        runBootstrap()
    }

    private fun runBootstrap() {
        if (syncUi.busy) return
        lifecycleScope.launch {
            syncUi = syncUi.copy(busy = true, message = "Синхронизация с сервером...")
            try {
                val res = graph.syncManager.registerAndSync()
                graph.keyProvider.refresh()
                graph.tariffProvider.refresh()
                syncUi = when (res) {
                    is ProvisionSyncManager.Result.Ok -> SyncUi(
                        busy = false,
                        certReady = graph.certManager.hasCertificate(),
                        distributorId = res.distributorTerminalId?.take(8),
                        keysCount = res.keysCount,
                        tariffsCount = res.tariffsCount,
                        lastSyncAtMs = System.currentTimeMillis(),
                        message = "OK"
                    )
                    is ProvisionSyncManager.Result.Fail -> SyncUi(
                        busy = false,
                        certReady = graph.certManager.hasCertificate(),
                        message = res.message
                    )
                }
            } catch (e: Exception) {
                syncUi = syncUi.copy(busy = false, message = "Ошибка: ${e.message ?: "неизвестная"}")
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
        if (!::vm.isInitialized) return
        // Промпт 017 §10: во время показа QR фискального чека (и ожидания оплаты)
        // тапы игнорируются — QR живёт ~3 с, чужой тап не должен его закрыть.
        if (vm.state.step == TopUpViewModel.Step.PAYING || vm.state.fiscalQr != null) return
        vm.onTag(tag)
    }
}

@Composable
private fun DistributorContent(
    vm: TopUpViewModel,
    syncUi: SyncUi,
    defaultFare: Double,
    onSync: () -> Unit,
    modifier: Modifier = Modifier
) {
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

        // --- Серверный контур ---
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Серверный контур", style = MaterialTheme.typography.titleMedium)
                Text("Сертификат mTLS: ${if (syncUi.certReady) "есть" else "нет"}")
                if (syncUi.distributorId != null) {
                    Text("Терминал дистрибьютора: ${syncUi.distributorId}…")
                }
                if (syncUi.keysCount > 0) {
                    Text("Ключей ASOP_KEYS: ${syncUi.keysCount}")
                }
                if (syncUi.tariffsCount > 0) {
                    Text("Тарифов: ${syncUi.tariffsCount}, поездка = ${defaultFare} ₽")
                }
                if (syncUi.lastSyncAtMs > 0) {
                    val ts = remember(syncUi.lastSyncAtMs) {
                        SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                            .format(syncUi.lastSyncAtMs)
                    }
                    Text("Синхронизировано в $ts")
                }
                if (syncUi.message.isNotEmpty()) {
                    Text(
                        syncUi.message,
                        color = if (syncUi.busy) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = onSync, enabled = !syncUi.busy) {
                        Text(if (syncUi.busy) "Синхронизация..." else "Синхронизировать")
                    }
                    if (syncUi.busy) CircularProgressIndicator(modifier = Modifier.padding(4.dp))
                }
            }
        }

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

        // Промпт 017 §10: QR фискального чека (после успешного пополнения, ~3 с).
        state.fiscalQr?.let { qr ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    Modifier.padding(12.dp).fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("Фискальный чек", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    FiscalQrImage(url = qr.url)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Чек: ${qr.receiptId.take(8)}…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        when (state.step) {
            TopUpViewModel.Step.IDLE -> {
                Text("Шаг 1: приложите карту дистрибьютора (DISTRIBUTOR_ADMIN / DISTRIBUTOR_DISPATCHER)")
            }
            TopUpViewModel.Step.DISTRIBUTOR_SCAN -> {
                Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(); Text("чтение...") }
            }
            TopUpViewModel.Step.DISTRIBUTOR_AUTHED -> {
                if (state.canTopUp) {
                    Button(onClick = { vm.beginMethod() }) {
                        Text("Продолжить (сумма и способ)")
                    }
                } else {
                    OutlinedButton(onClick = { vm.reset() }) { Text("Назад") }
                }
            }
            TopUpViewModel.Step.METHOD -> {
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("Сумма пополнения, руб") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true
                )
                val previewTrips = (amount.toDoubleOrNull() ?: 0.0)
                    .let { if (it > 0) (it / defaultFare).toInt().coerceAtLeast(1) else 0 }
                Text("Будет добавлено поездок: $previewTrips (тариф $defaultFare ₽ / поездка)")
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = {
                        val a = amount.toDoubleOrNull() ?: run {
                            Toast.makeText(context, "Неверная сумма", Toast.LENGTH_SHORT).show(); 0.0
                        }
                        if (a > 0) vm.payCard(a)
                    }) { Text("Оплатить картой") }
                    OutlinedButton(onClick = {
                        val a = amount.toDoubleOrNull() ?: 0.0
                        if (a > 0) vm.payCash(a)
                    }) { Text("Наличные") }
                    OutlinedButton(onClick = { vm.reset() }) { Text("Отмена") }
                }
            }
            TopUpViewModel.Step.BANK_CARD -> {
                Text("Приложите банковскую карту пассажира")
                if (state.payStatus == "APPROVED") {
                    Text("Оплачено — приложите MIFARE пассажира", color = MaterialTheme.colorScheme.primary)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = {
                        val a = state.amount ?: 0.0
                        if (a > 0) vm.payCash(a)
                    }) { Text("Переключить на наличные") }
                    OutlinedButton(onClick = { vm.reset() }) { Text("Отмена") }
                }
            }
            TopUpViewModel.Step.MIFARE_WRITE -> {
                Text("Приложите MIFARE пассажира для записи поездок")
                if (state.payStatus != null) {
                    Text("Платёж: ${state.payStatus}", color = MaterialTheme.colorScheme.primary)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { vm.reset() }) { Text("Отмена") }
                }
            }
            TopUpViewModel.Step.PAYING -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator()
                    Text("Ожидание...")
                }
            }
            TopUpViewModel.Step.DONE -> {
                Button(onClick = { vm.reset() }) { Text("Новый клиент") }
            }
        }
    }
}

/**
 * Промпт 017 §10: QR-код фискального чека. Картинка рендерится локально из URL через
 * ZXing (без сети) — fiscalUrl строится офлайн по receiptId.
 */
@Composable
private fun FiscalQrImage(url: String) {
    val bitmap = remember(url) {
        runCatching {
            val matrix = com.google.zxing.qrcode.QRCodeWriter().encode(
                url,
                com.google.zxing.BarcodeFormat.QR_CODE,
                512,
                512
            )
            val bmp = android.graphics.Bitmap.createBitmap(
                matrix.width,
                matrix.height,
                android.graphics.Bitmap.Config.ARGB_8888
            )
            val black = android.graphics.Color.BLACK
            val white = android.graphics.Color.WHITE
            for (x in 0 until matrix.width) {
                for (y in 0 until matrix.height) {
                    bmp.setPixel(x, y, if (matrix.get(x, y)) black else white)
                }
            }
            bmp
        }.getOrNull()
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "QR фискального чека",
            modifier = Modifier.size(220.dp)
        )
    } else {
        Text(
            url,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center
        )
    }
}