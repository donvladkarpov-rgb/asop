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
import ru.asop.proto.v1.CardIdentity as ProtoCardIdentity
import ru.asop.terminal.nfc.DesfireCardReader
import ru.asop.terminal.nfc.NfcReaderRefCount
import ru.asop.terminal.nfc.decodeSectorOne

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
            // Промпт 014: общий рефкаунт — поздний onDispose другого NFC-экрана
            // (SessionFlow/CardActivation) не должен убивать наш reader. Physical
            // disable только когда никто больше не держит reader.
            NfcReaderRefCount.acquire()
        }
        onDispose {
            if (nfcAdapter != null && activity != null && NfcReaderRefCount.releaseAndShouldDisable()) {
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
                "Ожидание чтения MIFARE…",
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
                    identity.signatureValid?.let { valid ->
                        InfoRow(
                            "Верификация",
                            if (valid) "OK — подпись верна" else "FAIL — подпись неверна"
                        )
                    }
                }

            } else if (result.isClassic) {
                val classic = result.classicInfo
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("MIFARE Classic", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.width(8.dp))
                    Text("•", color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text(classic?.typeLabel ?: "", style = MaterialTheme.typography.titleMedium)
                }
                Spacer(Modifier.height(12.dp))
                InfoRow("UID", result.uid)
                InfoRow("Секторов", "${classic?.sectorCount ?: "?"}")
                classic?.keyFoundFirstBytes?.let { InfoRow("Найден ключ (первые 2 байта)", it) }
                classic?.block0Content?.let {
                    Spacer(Modifier.height(8.dp))
                    Text("Блок 0 (производителя):", style = MaterialTheme.typography.bodySmall)
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(12.dp),
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                        )
                    }
                }
                // Полный дамп sectors 1..end с блоками data + trailer.
                // Sector 0 пропускается (manufacturer block, показан выше).
                classic?.allBlocks?.takeIf { it.isNotEmpty() }?.let { allBlocks ->
                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(8.dp))
                    Text("Полный дамп секторов (1..${allBlocks.size}):", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "Блок данных 16 байт / trailer (последний блок сектора). " +
                            "Если сектор пуст — auth не прошёл (factory/ASOP).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    allBlocks.entries.forEach { (sector, blocks) ->
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            colors = androidx.compose.material3.CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text(
                                    "Sector $sector ${classic.trailerKeyLabels[sector]?.let { "— ключ: $it" } ?: ""}",
                                    style = MaterialTheme.typography.titleSmall
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                if (blocks.isEmpty()) {
                                    Text(
                                        "auth не прошёл ни одним ключом",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                } else {
                                    blocks.forEachIndexed { i, hex ->
                                        val isTrailer = i == blocks.size - 1
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                String.format("  б%d:", i),
                                                style = MaterialTheme.typography.bodySmall,
                                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                                color = if (isTrailer)
                                                    MaterialTheme.colorScheme.primary
                                                else
                                                    MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            Spacer(Modifier.width(4.dp))
                                            Text(
                                                hex,
                                                style = MaterialTheme.typography.bodySmall,
                                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                                color = if (isTrailer)
                                                    MaterialTheme.colorScheme.primary
                                                else
                                                    MaterialTheme.colorScheme.onSurface
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Человекочитаемая расшифровка сектора 1 (промпт 008): magic + bitmask + UUIDs.
                // Показываем всегда, когда sector 1 прочитан — даже если VCM1/SAC1 магия
                // не совпала. Это критично для debug клон-карт и понимания содержимого.
                classic?.let { c ->
                    val decode = decodeSectorOne(c.allBlocks ?: emptyMap())
                    if (decode != null) {
                        Spacer(Modifier.height(12.dp))
                        HorizontalDivider()
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Sector 1 — расшифровка:",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.height(6.dp))
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                val magicColor = when (decode.magic) {
                                    "VCM1" -> MaterialTheme.colorScheme.primary
                                    "SAC1" -> MaterialTheme.colorScheme.error
                                    "BLANK", "FACTORY_FF" -> MaterialTheme.colorScheme.onSurfaceVariant
                                    else -> MaterialTheme.colorScheme.error
                                }
                                Text(
                                    "Magic 4 байта блока 0:  ${decode.magic}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = magicColor
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "Bitmask (байты 4-5, LE): ${decode.bitmaskHex} = ${decode.bitmaskValue} dec",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                )
                                if (decode.roles.isEmpty()) {
                                    Text(
                                        "  Роли: (нет битов)",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                } else {
                                    Text(
                                        "  Роли (${decode.roles.size}):",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    decode.roles.forEach { role ->
                                        Text(
                                            "  • $role",
                                            style = MaterialTheme.typography.bodySmall,
                                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                        )
                                    }
                                }
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "cardId (UUID v7, 16 байт блока 1):",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Text(
                                    if (decode.cardIdPresent) decode.cardIdFormatted
                                    else "(blank — 00..00)",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                    color = if (decode.cardIdPresent)
                                        MaterialTheme.colorScheme.primary
                                    else
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "entityUuid (16 байт блока 2):",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Text(
                                    decode.entityFormatted,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                    color = if (decode.entityPresent)
                                        MaterialTheme.colorScheme.primary
                                    else
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (decode.magic != "VCM1") {
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        "⚠ Карта в формате ${decode.magic} — нужен Drawer → Активация карт " +
                                            "для пере-прошивки в VCM1.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                } else if (decode.roles.isEmpty()) {
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        "ℹ VCM1-magic есть, но битовая маска пуста (rare — обычно FOREMAN ID)" +
                                            " Drawer → Активация карт, чтобы заполнить роли.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }

                // ASOP cardIdentity (если SAC1 был прочитан в дампе).
                classic?.sac1Identity?.let { identity ->
                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "ASOP cardIdentity (SAC1, из sectors 1..end)",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(6.dp))
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = identity.identityJson.takeIf { it.isNotBlank() }
                                ?: "(raw proto bytes: ${identity.protoBytes?.size ?: "?"} bytes)",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(12.dp),
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    InfoRow("Подпись (RSA-PSS-SHA256, raw)", "${identity.signatureBase64.take(40)}…")
                    identity.signatureValid?.let { valid ->
                        InfoRow(
                            "Верификация",
                            if (valid) "OK — подпись верна" else "FAIL — подпись неверна"
                        )
                        if (!valid) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Совет: перезаписать карту (Drawer → Активация карт).",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
                result.notes.forEach { note ->
                    Spacer(Modifier.height(2.dp))
                    Text(
                        note,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
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
