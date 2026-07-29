package ru.asop.terminal.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import ru.asop.terminal.network.models.CarrierResponse
import ru.asop.terminal.network.models.RegionResponse
import java.util.TimeZone

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssignCarrierScreen(
    onSaved: () -> Unit,
    viewModel: TerminalViewModel = hiltViewModel()
) {
    val regions by viewModel.regions.collectAsState()
    val carriers by viewModel.carriers.collectAsState()
    val terminal by viewModel.terminalInfo.collectAsState()

    var selectedRegion by remember { mutableStateOf<RegionResponse?>(null) }
    var selectedCarrier by remember { mutableStateOf<CarrierResponse?>(null) }
    var saved by remember { mutableStateOf(false) }

    var regionExpanded by remember { mutableStateOf(false) }
    var carrierExpanded by remember { mutableStateOf(false) }

    val timezone = remember { TimeZone.getDefault().id }

    LaunchedEffect(Unit) {
        viewModel.loadReferenceData()
    }

    LaunchedEffect(terminal) {
        if (saved) {
            onSaved()
        }
    }

    LaunchedEffect(terminal, carriers) {
        terminal?.carrierId?.let { currentCarrierId ->
            carriers.find { it.id == currentCarrierId }?.let {
                selectedCarrier = it
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Привязка перевозчика",
            style = MaterialTheme.typography.headlineSmall
        )

        Spacer(Modifier.height(24.dp))

        ExposedDropdownMenuBox(
            expanded = regionExpanded,
            onExpandedChange = { regionExpanded = it }
        ) {
            OutlinedTextField(
                value = selectedRegion?.municipalDivision ?: "",
                onValueChange = {},
                readOnly = true,
                label = { Text("Регион") },
                singleLine = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = regionExpanded) },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor()
            )
            ExposedDropdownMenu(
                expanded = regionExpanded,
                onDismissRequest = { regionExpanded = false }
            ) {
                regions.forEach { region ->
                    DropdownMenuItem(
                        text = { Text(region.municipalDivision) },
                        onClick = {
                            selectedRegion = region
                            regionExpanded = false
                            selectedCarrier = null
                            viewModel.loadCarriersForRegion(region.id)
                        }
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        ExposedDropdownMenuBox(
            expanded = carrierExpanded,
            onExpandedChange = { carrierExpanded = it }
        ) {
            OutlinedTextField(
                value = selectedCarrier?.carrierName ?: "",
                onValueChange = {},
                readOnly = true,
                label = { Text("Перевозчик") },
                singleLine = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = carrierExpanded) },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(),
                enabled = selectedRegion != null
            )
            ExposedDropdownMenu(
                expanded = carrierExpanded,
                onDismissRequest = { carrierExpanded = false }
            ) {
                carriers.forEach { carrier ->
                    DropdownMenuItem(
                        text = { Text(carrier.carrierName) },
                        onClick = {
                            selectedCarrier = carrier
                            carrierExpanded = false
                        }
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = timezone,
            onValueChange = {},
            readOnly = true,
            label = { Text("Часовой пояс устройства") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(8.dp))

        terminal?.carrierId?.let { currentCarrierId ->
            val currentCarrier = carriers.find { it.id == currentCarrierId }
            if (currentCarrier != null) {
                Text(
                    text = "Текущий перевозчик: ${currentCarrier.carrierName}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
            }
        }

        Spacer(Modifier.height(24.dp))

        Button(
            onClick = {
                saved = true
                viewModel.assignCarrier(selectedCarrier?.id)
            },
            enabled = selectedCarrier != null,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Сохранить")
        }
    }
}
