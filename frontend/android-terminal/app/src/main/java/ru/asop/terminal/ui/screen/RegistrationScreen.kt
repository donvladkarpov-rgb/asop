package ru.asop.terminal.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import java.util.TimeZone

private val COMMON_TIMEZONES = listOf(
    "Europe/Kaliningrad",
    "Europe/Moscow",
    "Europe/Simferopol",
    "Europe/Kirov",
    "Europe/Volgograd",
    "Europe/Astrakhan",
    "Europe/Samara",
    "Europe/Saratov",
    "Europe/Ulyanovsk",
    "Asia/Yekaterinburg",
    "Asia/Omsk",
    "Asia/Barnaul",
    "Asia/Novosibirsk",
    "Asia/Tomsk",
    "Asia/Krasnoyarsk",
    "Asia/Novokuznetsk",
    "Asia/Irkutsk",
    "Asia/Ulan-Ude",
    "Asia/Chita",
    "Asia/Yakutsk",
    "Asia/Vladivostok",
    "Asia/Khabarovsk",
    "Asia/Sakhalin",
    "Asia/Magadan",
    "Asia/Srednekolymsk",
    "Asia/Kamchatka",
    "Asia/Anadyr",
    "Etc/UTC",
    "Europe/London",
    "Europe/Berlin",
    "Europe/Paris",
    "Europe/Helsinki",
    "Asia/Almaty",
    "Asia/Tashkent",
    "Asia/Baku",
    "Asia/Yerevan",
    "Asia/Tbilisi",
    "Asia/Dubai",
    "Asia/Shanghai",
    "America/New_York"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RegistrationScreen(
    onRegistered: () -> Unit,
    viewModel: TerminalViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val regions by viewModel.regions.collectAsState()
    val carriers by viewModel.carriers.collectAsState()

    var model by remember { mutableStateOf("") }
    var number by remember { mutableStateOf("") }
    var selectedRegionId by remember { mutableStateOf<String?>(null) }
    var selectedCarrierId by remember { mutableStateOf<String?>(null) }
    var regionExpanded by remember { mutableStateOf(false) }
    var carrierExpanded by remember { mutableStateOf(false) }
    var timezoneExpanded by remember { mutableStateOf(false) }
    var selectedTimezone by remember { mutableStateOf(TimeZone.getDefault().id) }

    LaunchedEffect(Unit) {
        viewModel.loadReferenceData()
    }

    LaunchedEffect(selectedRegionId) {
        if (selectedRegionId != null) {
            selectedCarrierId = null
            viewModel.loadCarriersForRegion(selectedRegionId!!)
        }
    }

    LaunchedEffect(state) {
        if (state is TerminalViewModel.UiState.Registered) {
            onRegistered()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Регистрация терминала",
            style = MaterialTheme.typography.headlineSmall
        )
        Spacer(Modifier.height(24.dp))

        OutlinedTextField(
            value = viewModel.androidId,
            onValueChange = {},
            label = { Text("ANDROID ID (серийный номер)") },
            readOnly = true,
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))

        ExposedDropdownMenuBox(
            expanded = regionExpanded,
            onExpandedChange = { regionExpanded = it }
        ) {
            OutlinedTextField(
                value = regions.find { it.id == selectedRegionId }?.municipalDivision ?: "",
                onValueChange = {},
                readOnly = true,
                label = { Text("Регион") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = regionExpanded) },
                modifier = Modifier.fillMaxWidth().menuAnchor()
            )
            ExposedDropdownMenu(
                expanded = regionExpanded,
                onDismissRequest = { regionExpanded = false }
            ) {
                regions.forEach { region ->
                    DropdownMenuItem(
                        text = { Text(region.municipalDivision) },
                        onClick = {
                            selectedRegionId = region.id
                            regionExpanded = false
                        }
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        ExposedDropdownMenuBox(
            expanded = carrierExpanded,
            onExpandedChange = { if (selectedRegionId != null) carrierExpanded = it }
        ) {
            OutlinedTextField(
                value = carriers.find { it.id == selectedCarrierId }?.carrierName ?: "",
                onValueChange = {},
                readOnly = true,
                label = { Text("Перевозчик") },
                enabled = selectedRegionId != null,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = carrierExpanded) },
                modifier = Modifier.fillMaxWidth().menuAnchor()
            )
            ExposedDropdownMenu(
                expanded = carrierExpanded,
                onDismissRequest = { carrierExpanded = false }
            ) {
                carriers.forEach { carrier ->
                    DropdownMenuItem(
                        text = { Text(carrier.carrierName) },
                        onClick = {
                            selectedCarrierId = carrier.id
                            carrierExpanded = false
                        }
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        ExposedDropdownMenuBox(
            expanded = timezoneExpanded,
            onExpandedChange = { timezoneExpanded = it }
        ) {
            OutlinedTextField(
                value = selectedTimezone,
                onValueChange = {},
                readOnly = true,
                label = { Text("Часовой пояс") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = timezoneExpanded) },
                modifier = Modifier.fillMaxWidth().menuAnchor()
            )
            ExposedDropdownMenu(
                expanded = timezoneExpanded,
                onDismissRequest = { timezoneExpanded = false }
            ) {
                COMMON_TIMEZONES.forEach { tz ->
                    DropdownMenuItem(
                        text = { Text(tz) },
                        onClick = {
                            selectedTimezone = tz
                            timezoneExpanded = false
                        }
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = model,
            onValueChange = { model = it },
            label = { Text("Модель") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = number,
            onValueChange = { number = it },
            label = { Text("Инвентарный номер") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(24.dp))

        Button(
            onClick = {
                viewModel.registerTerminal(
                    regionId = selectedRegionId,
                    carrierId = selectedCarrierId,
                    timezone = selectedTimezone,
                    model = model.ifBlank { null },
                    number = number.ifBlank { null }
                )
            },
            enabled = number.isNotBlank() && selectedRegionId != null && selectedCarrierId != null
                    && state !is TerminalViewModel.UiState.Registering,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (state is TerminalViewModel.UiState.Registering) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp
                )
                Spacer(Modifier.width(8.dp))
            }
            Text("Зарегистрировать")
        }

        if (state is TerminalViewModel.UiState.Error) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = (state as TerminalViewModel.UiState.Error).message,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}
