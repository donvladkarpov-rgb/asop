package ru.asop.terminal.ui.screen

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.nfc.NfcAdapter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import ru.asop.terminal.activation.AsopCardType

/**
 * Экран активации карт АСОП (промпт 005, п.9).
 * Пошаговый flow: root-логин / авторизующая карта → целевая карта → поля →
 * sign+activate+прошивка.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CardActivationScreen(
    type: AsopCardType? = null,
    onBack: () -> Unit,
    viewModel: CardActivationViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val nfcAdapter = remember { viewModel.nfcAdapter }
    val context = LocalContext.current
    val activity = remember { context.findHostActivity() }

    // Reader mode держим включённым на всех шагах, где может понадобиться NFC:
    // авторизация картой, прикладывание целевой карты, прошивка.
    // В Step.Success — отключаем NFC-reader, чтобы любое новое прикладывание
    // карты не обрабатывалось (активация уже завершена).
    val needsNfc = state.step == CardActivationViewModel.Step.AuthForm ||
        state.step == CardActivationViewModel.Step.TargetCard ||
        state.step == CardActivationViewModel.Step.ReferenceForm ||
        state.step == CardActivationViewModel.Step.Busy
    val activeAdapter = nfcAdapter

    DisposableEffect(activeAdapter, activity, needsNfc) {
        if (activeAdapter != null && activity != null && activeAdapter.isEnabled && needsNfc) {
            activeAdapter.enableReaderMode(
                activity,
                { tag -> viewModel.onTagDiscovered(tag) },
                NfcAdapter.FLAG_READER_NFC_A or
                    NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK or
                    NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS,
                null
            )
        } else if (activeAdapter != null && activity != null && !needsNfc) {
            activeAdapter.disableReaderMode(activity)
        }
        onDispose {
            if (activeAdapter != null && activity != null) {
                activeAdapter.disableReaderMode(activity)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Активация карт") },
                navigationIcon = {
                    IconButton(onClick = {
                        viewModel.reset()
                        onBack()
                    }) {
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
            if (state.cardType == null) {
                TypeList(
                    onSelect = viewModel::onCardTypeSelected,
                    selected = type
                )
            } else {
                // Показываем выбранный тип и текущий шаг.
                when (state.step) {
                    CardActivationViewModel.Step.RootForm -> RootCallForm(state, viewModel)
                    CardActivationViewModel.Step.AuthForm,
                    CardActivationViewModel.Step.TargetCard,
                    CardActivationViewModel.Step.NetworkCheck -> NfcListeningCard(state, viewModel)
                    CardActivationViewModel.Step.ReferenceForm -> ReferenceForm(state, viewModel)
                    CardActivationViewModel.Step.Busy -> BusyCard(state)
                    CardActivationViewModel.Step.Error -> ErrorCard(state, viewModel)
                    CardActivationViewModel.Step.Done -> DoneCard(state, viewModel)
                    CardActivationViewModel.Step.Success -> SuccessCard(state, viewModel)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TypeList(
    selected: AsopCardType?,
    onSelect: (AsopCardType) -> Unit
) {
    Text(
        "Выберите тип карты",
        style = MaterialTheme.typography.headlineSmall,
        modifier = Modifier.padding(bottom = 12.dp)
    )
    Text("Персонал АСОП", style = MaterialTheme.typography.titleMedium)
    AsopCardType.STAFF.forEach { type ->
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .clickable { onSelect(type) }
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(type.label, style = MaterialTheme.typography.titleSmall)
                Text(type.role, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    Text("Пассажиры", style = MaterialTheme.typography.titleMedium)
    AsopCardType.PASSENGERS.forEach { type ->
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .clickable { onSelect(type) }
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(type.label, style = MaterialTheme.typography.titleSmall)
                Text(type.role, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    selected?.let {
        Spacer(Modifier.height(12.dp))
        Text(
            "Выбран: ${it.label}",
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun RootCallForm(
    state: CardActivationViewModel.UiState,
    viewModel: CardActivationViewModel
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Аутентификация root-администратора", style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(
            value = state.rootUsername,
            onValueChange = viewModel::onRootUsername,
            label = { Text("Логин") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = state.rootPassword,
            onValueChange = viewModel::onRootPassword,
            label = { Text("Пароль") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )
        state.validatedError?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Button(
            onClick = viewModel::rootLogin,
            modifier = Modifier.fillMaxWidth(),
            enabled = !state.busy
        ) {
            Text("Войти и продолжить")
        }
        state.message.takeIf { it.isNotBlank() }?.let {
            Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun NfcListeningCard(
    state: CardActivationViewModel.UiState,
    viewModel: CardActivationViewModel
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (state.step == CardActivationViewModel.Step.NetworkCheck) {
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                Text("Проверка сети…", style = MaterialTheme.typography.bodyMedium)
            } else {
                Icon(
                    Icons.Default.Nfc,
                    contentDescription = null,
                    modifier = Modifier.width(48.dp).height(48.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    when (state.step) {
                        CardActivationViewModel.Step.AuthForm -> "Приложите АВТОРИЗУЮЩУЮ карту"
                        else -> "Приложите ЦЕЛЕВУЮ карту"
                    },
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    state.message.ifBlank { "Ожидание Mifare DESFire…" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (state.authorizedByRoot) {
                    Spacer(Modifier.height(8.dp))
                    Text("Авторизация: root", style = MaterialTheme.typography.bodySmall)
                } else if (state.operatorRoles.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Роли оператора: ${state.operatorRoles.joinToString(", ")}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReferenceForm(
    state: CardActivationViewModel.UiState,
    viewModel: CardActivationViewModel
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "Карта UID ${state.targetCardUid ?: "?"}" +
                (if (state.targetCardMode == "existing") " (обновление)" else " (новая)"),
            style = MaterialTheme.typography.titleMedium
        )
        Dropdown(
            label = "Регион",
            options = state.regions,
            selectedId = state.selectedRegionId,
            onSelect = viewModel::selectRegion
        )
        if (state.cardType?.needsOrganizer == true) {
            Dropdown(
                "Организатор", viewModel.filteredOrganizers(), state.selectedOrganizerId,
                viewModel::selectOrganizer
            )
        }
        if (state.cardType?.needsCarrier == true) {
            Dropdown(
                "Перевозчик", viewModel.filteredCarriers(), state.selectedCarrierId,
                viewModel::selectCarrier
            )
        }
        if (state.cardType?.needsDistributor == true) {
            Dropdown(
                "Дистрибьютор", viewModel.filteredDistributors(), state.selectedDistributorId,
                viewModel::selectDistributor
            )
        }
        if (state.cardType?.needsAuditService == true) {
            Dropdown(
                "КРС", viewModel.filteredAuditServices(), state.selectedAuditServiceId,
                viewModel::selectAuditService
            )
        }
        if (state.cardType?.needsUser == true || state.cardType == AsopCardType.SUPER_ADMIN) {
            UserSearchField(state, viewModel)
        }
        state.message.takeIf { it.isNotBlank() }?.let {
            Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        // Промпт 009 AC2: блокировка "Активировать карту" если cascade пустой
        // (selectedUserId == null и роли требуют userId). PASSENGER_ANONYMOUS — исключение,
        // entity=null допустимо без user search.
        val isAnonymousPassenger = state.cardType == AsopCardType.PASSENGER_ANONYMOUS
        val requiresUserId = !isAnonymousPassenger
        val userSelected = !state.selectedUserId.isNullOrBlank()
        val canActivate = !state.busy && (isAnonymousPassenger || userSelected)
        Button(
            onClick = viewModel::runActivation,
            modifier = Modifier.fillMaxWidth(),
            enabled = canActivate
        ) {
            Text(if (state.targetCardMode == "existing") "Перезаписать карту" else "Активировать карту")
        }
        if (requiresUserId && !userSelected) {
            Spacer(Modifier.height(8.dp))
            Text(
                "⚠ Выберите сотрудника (userId) из списка выше — обязательное поле для роли ${state.cardType?.role ?: "?"}",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun UserSearchField(
    state: CardActivationViewModel.UiState,
    viewModel: CardActivationViewModel
) {
    val focusManager = LocalFocusManager.current
    val allUsers = viewModel.filteredUsers()
    val selected = allUsers.firstOrNull { it.id == state.selectedUserId }

    Column {
        if (selected != null) {
            // Визуальное подтверждение выбора — chip над полем поиска.
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AssistChip(
                    onClick = {},
                    label = { Text("👤 ${selected.label}") },
                    leadingIcon = { Icon(Icons.Default.Check, contentDescription = null) },
                    modifier = Modifier.weight(1f, fill = false)
                )
                IconButton(onClick = { viewModel.selectUser("") /* пустой id = сброс */ }) {
                    Icon(Icons.Default.Close, contentDescription = "Сбросить выбор")
                }
            }
        }
        OutlinedTextField(
            value = state.userQuery,
            onValueChange = viewModel::onUserQueryChanged,
            label = { Text("Поиск пользователя по ФИО") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        val filtered = allUsers.filter {
            state.userQuery.isBlank() || it.label.contains(state.userQuery, ignoreCase = true)
        }.take(15)
        if (filtered.isNotEmpty()) {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                items(filtered.size) { i ->
                    val user = filtered[i]
                    val isSelected = user.id == state.selectedUserId
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                viewModel.selectUser(user.id)
                                focusManager.clearFocus()
                            },
                        colors = if (isSelected) CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        ) else CardDefaults.cardColors()
                    ) {
                        ListItem(
                            headlineContent = { Text(user.label) },
                            trailingContent = if (isSelected) {
                                { Icon(Icons.Default.Check, contentDescription = "Выбран") }
                            } else null,
                            colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent)
                        )
                    }
                }
            }
        } else {
            Text(
                "Нет пользователей по запросу «${state.userQuery}»",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Dropdown(
    label: String,
    options: List<CardActivationViewModel.RefOption>,
    selectedId: String?,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = options.firstOrNull { it.id == selectedId }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected?.label ?: "",
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            singleLine = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(),
            enabled = options.isNotEmpty()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { opt ->
                DropdownMenuItem(
                    text = { Text(opt.label) },
                    onClick = {
                        onSelect(opt.id)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun BusyCard(state: CardActivationViewModel.UiState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            CircularProgressIndicator()
            Spacer(Modifier.height(16.dp))
            Text(state.message, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun DoneCard(state: CardActivationViewModel.UiState, viewModel: CardActivationViewModel) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                if (state.finalOk) "Готово" else "Проблема",
                style = MaterialTheme.typography.titleLarge,
                color = if (state.finalOk) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
            )
            Spacer(Modifier.height(8.dp))
            Text(
                state.finalResult ?: "",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )
            if (state.serverRegistered && !state.cardWritten) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Карта зарегистрирована на сервере, но не прошита. Приложите карту повторно.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(16.dp))
            Button(onClick = viewModel::reset) {
                Text("Активировать другую карту")
            }
        }
    }
}

@Composable
private fun ErrorCard(state: CardActivationViewModel.UiState, viewModel: CardActivationViewModel) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Ошибка", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(8.dp))
            // Промпт 009 fix: показываем конкретную причину из finalResult/lastReceiptEntry,
            // а не просто stuck busy-message типа "Регистрация VCM1 на сервере...".
            val cause: String = state.finalResult?.takeIf { it.isNotBlank() }
                ?: state.receiptEntries.lastOrNull()?.text?.takeIf { it.startsWith("Ошибка") }
                ?: state.message.takeIf { it.isNotBlank() }
                ?: "Неизвестная ошибка"
            Text(cause, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(16.dp))
            Button(onClick = viewModel::reset) {
                Text("Назад к выбору типа")
            }
        }
    }
}

/**
 * Финальный success-state после успешной прошивки карты:
 *   1. Большая зелёная галочка (Check icon в successContainer)
 *   2. Краткий текст "Карта успешно активирована"
 *   3. Подробности (role + UID карты + кнопка "Активировать ещё одну" → reset())
 *
 * Здесь же выключается NFC-reader (`needsNfc = false` в CardActivationScreen)
 * и `onTagDiscovered` игнорирует тап — чтобы случайное прикладывание карты
 * не сбросило экран успеха.
 */
@Composable
private fun SuccessCard(state: CardActivationViewModel.UiState, viewModel: CardActivationViewModel) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = "Карта успешно прошита",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(80.dp)
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Карта успешно активирована",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(4.dp))
            state.targetCardUid?.let { uid ->
                Text(
                    "UID: $uid",
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                )
            }
            state.cardType?.let { type ->
                Text("Роль: ${type.role}", style = MaterialTheme.typography.bodyMedium)
            }

            // Чек операции (промпт 008 UX).
            if (state.receiptEntries.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                ReceiptCard(state.receiptEntries)
            }

            Spacer(Modifier.height(16.dp))
            Button(onClick = viewModel::reset) {
                Text("Активировать ещё одну карту")
            }
        }
    }
}

/**
 * Промпт 008 UX: чек операции — список шагов с таймстампами, которые терминал
 * прошёл во время активации. Рендерится на экране после успешной записи карты.
 * Никаких промежуточных звуков — только один success-тон в конце.
 */
@Composable
private fun ReceiptCard(entries: List<CardActivationViewModel.ReceiptEntry>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Text(
                "Чек операции",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(6.dp))
            entries.forEach { entry ->
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Text(
                        entry.time,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        entry.text,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

/** Ищем Activity для enableReaderMode (нет коллизии с CardReadScreen.findActivity). */
internal fun Context.findHostActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findHostActivity()
    else -> null
}