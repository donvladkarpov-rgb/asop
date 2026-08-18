package ru.asop.terminal.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import ru.asop.terminal.nfc.TonePlayer

/**
 * Промпт 011: «Открыть рейс». Driver tap auth → cascade (TID → Vehicle → Route → Path)
 * dropdowns из локальной Room `reference_rows`. Все отфильтрованы по carrierId
 * текущей открытой смены / авторизованной карты (front-end filter не строгий,
 * server-side validate через X-Carrier-Id / ownership).
 */
@Composable
fun OpenTripScreen(
    onConfirmed: () -> Unit,
    viewModel: SessionFlowViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { TonePlayer.readyBeep() }

    SessionFlowScreen(
        initialKind = SessionFlowViewModel.FlowKind.OPEN_TRIP,
        title = "Открыть рейс",
        confirmLabel = "Открыть рейс",
        viewModel = viewModel,
        onConfirmedNavigateBack = onConfirmed,
        onConfirm = { it.confirmOpenTrip() },
        extraChildren = { state ->
            TripCascadePicker(state = state, viewModel = viewModel)
        }
    )
}

/**
 * 4 dropdowns для выбора: TID (опц.) → Vehicle → Route → Path.
 * Каскад: после выбора Route — список Paths перезагружается по routeId.
 */
@Composable
fun TripCascadePicker(
    state: SessionFlowViewModel.State,
    viewModel: SessionFlowViewModel
) {
    val tids by viewModel.observeTids().collectAsState(initial = emptyList())
    val vehicles by viewModel.observeVehicles().collectAsState(initial = emptyList())
    val routes by viewModel.observeRoutes().collectAsState(initial = emptyList())
    val paths by viewModel.observePaths().collectAsState(initial = emptyList())

    val isPassengerMode = state.kind == SessionFlowViewModel.FlowKind.TAP_PASSENGER

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            if (isPassengerMode && state.openTrip != null) {
                // Промпт 014: режим ожидания пассажиров — большой круг + галочка/крест
                val now = System.currentTimeMillis()
                val showResult = state.validationResultTime > 0 &&
                    (now - state.validationResultTime) < 2500
                Box(
                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        if (showResult && state.validationResult != null) {
                            val isOk = state.validationResult!!
                            val color = if (isOk) Color(0xFF4CAF50) else Color(0xFFF44336)
                            val icon = if (isOk) "✓" else "✗"
                            Box(
                                modifier = Modifier
                                    .size(120.dp)
                                    .background(color, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(icon, color = Color.White, fontSize = 48.sp)
                            }
                            Text(
                                if (isOk) "Принято" else "Отказ",
                                color = color, fontWeight = FontWeight.Bold
                            )
                            state.validationDetail?.let { detail ->
                                Text(
                                    detail,
                                    style = MaterialTheme.typography.titleMedium,
                                    textAlign = TextAlign.Center
                                )
                            }
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(120.dp)
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("⟳", fontSize = 48.sp,
                                    color = MaterialTheme.colorScheme.primary)
                            }
                            Text("Ждите карту пассажира",
                                style = MaterialTheme.typography.titleMedium)
                            Text("или карту водителя для выхода",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.height(12.dp))
                        Text("Валидаций: ${state.tripPayments.size}",
                            style = MaterialTheme.typography.titleMedium)
                    }
                }
            } else {
                Text("Параметры рейса", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))

                // TID pool (опционально)
                DropdownRow(
                    label = "TID-пул (опц.)",
                    rows = tids,
                    payloadParser = ::parseTidPayload,
                    selectedId = state.tripTidId,
                    onPick = { id, label -> viewModel.setTripTid(id, label) }
                )

                // Vehicle (обязательно)
                DropdownRow(
                    label = "Транспортное средство *",
                    rows = vehicles,
                    payloadParser = ::parseVehiclePayload,
                    selectedId = state.tripVehicleId,
                    onPick = { id, label -> viewModel.setTripVehicle(id, label) }
                )

                // Route (обязательно — задаёт список Paths)
                DropdownRow(
                    label = "Маршрут *",
                    rows = routes,
                    payloadParser = ::parseRoutePayload,
                    selectedId = state.tripRouteId,
                    onPick = { id, label -> viewModel.setTripRoute(id, label) }
                )

            // Path (обязательно)
            DropdownRow(
                label = "Путь следования *",
                rows = paths,
                payloadParser = ::parsePathPayload,
                selectedId = state.tripPathId,
                onPick = { id, label -> viewModel.setTripPath(id, label) },
                disabled = state.tripRouteId == null
            )

            } // end else
            if (state.tripRouteId == null && routes.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Сначала выберите маршрут (он определяет список путей).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun DropdownRow(
    label: String,
    rows: List<String>,
    payloadParser: (String) -> Pair<String, String>,
    selectedId: String?,
    onPick: (String, String) -> Unit,
    disabled: Boolean = false
) {
    var expanded by remember { mutableStateOf(false) }
    val items: List<Pair<String, String>> = remember(rows) {
        rows.mapNotNull { json ->
            runCatching { payloadParser(json) }.getOrNull()
        }
    }
    val selectedLabel: String = items.firstOrNull { it.first == selectedId }?.second ?: "—"

    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        OutlinedButton(
            onClick = { if (!disabled) expanded = true },
            enabled = !disabled,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "$label: $selectedLabel",
                modifier = Modifier.weight(1f)
            )
            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.fillMaxWidth(0.85f)
        ) {
            if (items.isEmpty()) {
                DropdownMenuItem(text = { Text("Нет данных") }, onClick = { expanded = false })
            } else {
                items.forEach { (id, lbl) ->
                    DropdownMenuItem(
                        text = { Text(lbl) },
                        trailingIcon = if (id == selectedId) {
                            { Icon(Icons.Default.Check, contentDescription = null) }
                        } else null,
                        onClick = {
                            onPick(id, lbl)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

private fun parseTidPayload(json: String): Pair<String, String> {
    val obj = org.json.JSONObject(json)
    val id = obj.optString("tidId", obj.optString("id"))
    val value = obj.optString("tidValue", "—")
    val status = obj.optString("status", "")
    return id to "$value [$status]"
}

private fun parseVehiclePayload(json: String): Pair<String, String> {
    val obj = org.json.JSONObject(json)
    val id = obj.optString("vehicleId", obj.optString("id"))
    val number = obj.optString("vehicleNumber", "—")
    val name = obj.optString("vehicleName", "")
    return id to if (name.isNotEmpty()) "$number $name" else number
}

private fun parseRoutePayload(json: String): Pair<String, String> {
    val obj = org.json.JSONObject(json)
    val id = obj.optString("routeId", obj.optString("id"))
    val number = obj.optString("routeNumber", "—")
    val name = obj.optString("routeName", "")
    return id to if (name.isNotEmpty()) "$number — $name" else number
}

private fun parsePathPayload(json: String): Pair<String, String> {
    val obj = org.json.JSONObject(json)
    val id = obj.optString("pathId", obj.optString("id"))
    val name = obj.optString("pathName", "—")
    return id to name
}
