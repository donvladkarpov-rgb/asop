package ru.asop.terminal.ui.screen

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.nfc.NfcAdapter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import ru.asop.terminal.nfc.NfcReaderRefCount

/**
 * Промпт 014: «Пополнить карту».
 * 1-й tap — карта-ключ (дистрибьютор/админ), 2-й tap — пассажирская карта,
 * далее ввод числа поездок и запись на карту.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TopUpScreen(
    onBack: () -> Unit,
    viewModel: TopUpViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val activity = remember { findTopUpActivity(context) }
    val nfcAdapter = remember { viewModel.nfcAdapter }

    DisposableEffect(nfcAdapter, activity) {
        // Ревью-фикс: claim NfcTagBus на время ВИДИМОСТИ экрана, не жизни ViewModel.
        // Drawer-навигация оставляет entry (и VM) в backstack — claim из init висел бы
        // и dormant-loop съедал таги SessionFlow.
        viewModel.onScreenEnter()
        val needsNfc = state.step == TopUpViewModel.Step.AUTH ||
            state.step == TopUpViewModel.Step.TARGET_CARD ||
            state.step == TopUpViewModel.Step.AMOUNT ||
            state.step == TopUpViewModel.Step.WRITE_CARD
        android.util.Log.i("TopUpScreen",
            "arm: step=${state.step} nfc=${nfcAdapter?.isEnabled} act=${activity?.javaClass?.simpleName} needsNfc=$needsNfc")
        var acquired = false
        if (nfcAdapter != null && activity != null && nfcAdapter.isEnabled && needsNfc) {
            nfcAdapter.enableReaderMode(
                activity,
                { tag ->
                    android.util.Log.i("TopUpScreen", "reader callback TAG: ${tag.id.joinToString("") { "%02X".format(it) }}")
                    // Ревью: единый вход VM — роутинг по живому step, не по замкнутому Compose-state.
                    viewModel.onTagDiscovered(tag)
                },
                NfcAdapter.FLAG_READER_NFC_A or
                    NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK or
                    NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS,
                null
            )
            // Промпт 014: Feitian F20 fallback — foreground dispatch → MainActivity.onNewIntent → NfcTagBus,
            // откуда TopUpViewModel читает tag (на случай если ReaderMode binder не зарегистрирован).
            try {
                val intent = android.content.Intent(activity, activity.javaClass).apply {
                    addFlags(android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                val pendingIntent = android.app.PendingIntent.getActivity(
                    activity, 0, intent,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                        android.app.PendingIntent.FLAG_MUTABLE
                )
                val filters = arrayOf(
                    android.content.IntentFilter(android.nfc.NfcAdapter.ACTION_TAG_DISCOVERED),
                    android.content.IntentFilter(android.nfc.NfcAdapter.ACTION_TECH_DISCOVERED)
                )
                val techLists = arrayOf(
                    arrayOf("android.nfc.tech.MifareClassic"),
                    arrayOf("android.nfc.tech.IsoDep")
                )
                nfcAdapter.enableForegroundDispatch(activity, pendingIntent, filters, techLists)
            } catch (e: Exception) {
                android.util.Log.w("TopUpScreen", "enableForegroundDispatch failed: ${e.message}")
            }
            NfcReaderRefCount.acquire()
            acquired = true
        }
        onDispose {
            viewModel.onScreenExit()
            // Ревью-фикс: release только если этот инстанс эффекта acquire'ил (симметрия).
            if (acquired && nfcAdapter != null && activity != null && NfcReaderRefCount.releaseAndShouldDisable()) {
                nfcAdapter.disableReaderMode(activity)
                try { nfcAdapter.disableForegroundDispatch(activity) } catch (_: Exception) {}
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Пополнить карту") },
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
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(state.message, style = MaterialTheme.typography.bodyLarge)

            when (state.step) {
                TopUpViewModel.Step.AUTH -> Card { Text("Поднесите карту дистрибьютора или администратора", modifier = Modifier.padding(16.dp)) }

                TopUpViewModel.Step.TARGET_CARD -> Card {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Авторизация OK. Поднесите карту для пополнения")
                        state.error?.let { err ->
                            Spacer(Modifier.height(8.dp))
                            Text(err, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }

                TopUpViewModel.Step.AMOUNT -> {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Текущий остаток: ${state.targetTripsLeft}", fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = state.entered,
                                onValueChange = viewModel::onAmountChanged,
                                label = { Text("Количество поездок") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(12.dp))
                            Button(
                                onClick = viewModel::onTopUp,
                                enabled = state.entered.isNotEmpty(),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Продолжить")
                            }
                            state.error?.let { err ->
                                Spacer(Modifier.height(8.dp))
                                Text(err, color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }

                TopUpViewModel.Step.WRITE_CARD -> Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "Приложите ту же карту для записи",
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(8.dp))
                        Text("Будет добавлено: ${state.topUpAmount} поездок (остаток ${state.targetTripsLeft} → ${state.targetTripsLeft + state.topUpAmount})")
                        if (state.busy) {
                            Spacer(Modifier.height(8.dp))
                            Text("Запись...")
                        }
                        state.error?.let { err ->
                            Spacer(Modifier.height(8.dp))
                            Text(err, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }

                TopUpViewModel.Step.DONE -> Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("✓ ${state.message}", fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = viewModel::reset, modifier = Modifier.fillMaxWidth()) {
                            Text("Пополнить ещё одну карту")
                        }
                    }
                }

                TopUpViewModel.Step.ERROR -> {
                    state.error?.let { err ->
                        Text(err, color = MaterialTheme.colorScheme.error)
                    }
                    OutlinedButton(onClick = viewModel::reset, modifier = Modifier.fillMaxWidth()) {
                        Text("Сбросить")
                    }
                }
            }
        }
    }
}

private fun findTopUpActivity(ctx: Context): Activity? {
    var c: Context? = ctx
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}
