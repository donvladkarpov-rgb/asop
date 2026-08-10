package ru.asop.terminal.ui.screen

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.nfc.NfcAdapter
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import ru.asop.terminal.nfc.DesfireCardReader

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CardReadScreen(
    onBack: () -> Unit,
    viewModel: CardReadViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val nfcAdapter = viewModel.nfcAdapter

    // Activity запоминаем один раз — LocalContext может пересоздаваться при
    // перекомпозициях Compose, что постоянно сбрасывало DisposableEffect и
    // мигало ReaderMode (enable → disable → enable → ... → off), из-за чего
    // карта не перехватывалась приложением.
    val activity = remember { context.findActivity() }
    DisposableEffect(nfcAdapter, activity) {
        if (nfcAdapter != null && activity != null && nfcAdapter.isEnabled) {
            nfcAdapter.enableReaderMode(
                activity,
                { tag -> viewModel.onTagDiscovered(tag) },
                NfcAdapter.FLAG_READER_NFC_A or
                    NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK or
                    NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS,
                null
            )
            viewModel.onReadingStarted()
        }
        onDispose {
            if (nfcAdapter != null && activity != null) {
                nfcAdapter.disableReaderMode(activity)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Прочитать карту") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            when {
                !state.supported -> StatusCard(
                    title = "NFC не поддерживается",
                    subtitle = "На этом устройстве нет NFC-модуля."
                )

                !state.enabled -> StatusCard(
                    title = "NFC выключен",
                    subtitle = "Включите NFC в настройках устройства."
                )

                state.result != null -> ResultCard(state.result!!, onReread = viewModel::reset)
                else -> ListeningCard()
            }
        }
    }
}

@Composable
private fun StatusCard(title: String, subtitle: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ListeningCard() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                Icons.Default.Nfc,
                contentDescription = null,
                modifier = Modifier.width(48.dp).height(48.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(16.dp))
            CircularProgressIndicator()
            Spacer(Modifier.height(16.dp))
            Text("Приложите карту к NFC-модулю", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "Ожидание чтения Mifare DESFire…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun NonGenuineWarning(reasons: List<String>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Карта негенуинная — клон, а не оригинал NXP",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(Modifier.height(6.dp))
            reasons.forEach { reason ->
                Text(
                    "• $reason",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                Spacer(Modifier.height(2.dp))
            }
        }
    }
}

@Composable
private fun ResultCard(result: DesfireCardReader.ReadResult, onReread: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .padding(16.dp)
        ) {
            val version = result.version
            if (version != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Карта прочитана", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.width(8.dp))
                    Text("•", color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text(version.generation, style = MaterialTheme.typography.titleMedium)
                }
                Spacer(Modifier.height(12.dp))
                if (result.nonGenuineReasons.isNotEmpty()) {
                    NonGenuineWarning(result.nonGenuineReasons)
                    Spacer(Modifier.height(12.dp))
                }
                InfoRow("UID", result.uid)
                version.storageLabel.let { InfoRow("Память", it) }
                version.hwVendorLabel.let { InfoRow("Производитель", "$it (HW/набор ${String.format("%02X", version.hwType)}/${String.format("%02X", version.hwSubtype)})") }
                InfoRow("HW версия", "${version.hwMajor}.${version.hwMinor}")
                version.swVendorLabel.let { InfoRow("SW производитель", it) }
                InfoRow("SW версия", "${version.swMajor}.${version.swMinor}")
                InfoRow("Серийный №", version.batchHex)
                val d = version.prodDate
                InfoRow("Дата выпуска", "неделя ${d[1]}, ${d[0]} г.")
                val cryptoText = when {
                    result.nonGenuineReasons.isNotEmpty() ->
                        "нельзя доверять — карта негенуинная (клон)"
                    result.ev2Plus == true -> "доступна (генерация ключей, подпись)"
                    result.ev2Plus == false -> "недоступна — только EV1 (симметричная AES/DES)"
                    else -> null
                }
                cryptoText?.let { InfoRow("On-card криптография", it) }
                result.notes.filter { it.startsWith("GetCardUID") }.forEach { note ->
                    Text(
                        note,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                result.auth?.let { auth ->
                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(12.dp))
                    Text("Аутентификация дефолтным ключом", style = MaterialTheme.typography.titleSmall)
                    InfoRow(
                        "Master app (000000)",
                        if (auth.selectedMasterApp) "выбран" else "не выбран"
                    )
                    InfoRow(
                        "AuthenticateAES (нулевой ключ)",
                        when {
                            !auth.selectedMasterApp -> "не пробовалась"
                            auth.authSucceeded -> "успешно (key0)"
                            else -> "не удалась (key0)"
                        }
                    )
                    InfoRow(
                        "AES handshake key1",
                        when {
                            auth.aesKey1Auth -> "ПРОЙДЕН — key1 AES с дефолтным ключом!"
                            else -> "не пройден"
                        }
                    )
                    InfoRow(
                        "AES handshake key3",
                        when {
                            auth.aesKey3Auth -> "ПРОЙДЕН — key3 AES с дефолтным ключом!"
                            else -> "не пройден"
                        }
                    )
                    auth.keySettingsHex?.let {
                        InfoRow("Key settings", it)
                    }
                    auth.keyVersion0?.let {
                        InfoRow("Key version 0", it)
                    }
                    if (auth.cardCertificateHex != null) {
                        InfoRow("NXP-сертификат", "${auth.cardCertificateHex.length / 3} байт")
                    }
                    InfoRow(
                        "3K3DES-рукопожатие (0x1A)",
                        if (auth.legacyAuthSucceeded) "пройдено → настоящий DESFire" else "не сошлось"
                    )
                    if (auth.readSignatureHex != null) {
                        InfoRow("Read_Sig (0x3C)", "${auth.readSignatureHex.length / 3} байт — оригинальная NXP-подпись")
                    }
                    if (auth.aesKeyProbe.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text("Зонд AES-ядра (0xAA по keyNo):", style = MaterialTheme.typography.bodySmall)
                        auth.aesKeyProbe.forEach { InfoRow("  ", it) }
                    }
                    if (auth.desKeyProbe.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text("Зонд 3K3DES (0x1A по keyNo):", style = MaterialTheme.typography.bodySmall)
                        auth.desKeyProbe.forEach { InfoRow("  ", it) }
                    }
                    auth.notes.forEach { note ->
                        Text(
                            note,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // ASOP card identity
                result.identity?.let { identity ->
                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "ASOP cardIdentity",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(6.dp))
                    Card(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = identity.identityJson,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(12.dp),
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    InfoRow("Подпись (RSA-PSS)", "${identity.signatureBase64.take(40)}...")
                }

            } else {
                Text("Карта не DESFire", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                result.notes.forEach { note ->
                    Text(
                        note,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                }
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))
            Text("Технологии", style = MaterialTheme.typography.titleSmall)
            InfoRow("techList", result.techs.joinToString(", "))
            result.atqa?.let { InfoRow("ATQA", it) }
            result.sak?.let { InfoRow("SAK", it) }
            result.atsHistorical?.let { InfoRow("ATS (историч.)", it) }
            result.atsHiLayer?.let { InfoRow("ATS (HiLayer)", it) }
            result.freeMemory?.let { InfoRow("Свободная память", "$it байт") }
            if (result.applications.isNotEmpty()) {
                InfoRow("Приложения", result.applications.joinToString(", "))
            }

            Spacer(Modifier.height(16.dp))
            Button(
                onClick = onReread,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Прочитать ещё раз")
            }
        }
    }
}

private fun Context.findActivity(): Activity? {
    var context = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return null
}
