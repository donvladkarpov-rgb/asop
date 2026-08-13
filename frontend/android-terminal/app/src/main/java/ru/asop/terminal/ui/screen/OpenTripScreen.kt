package ru.asop.terminal.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
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
    val reg = obj.optString("registrationNumber", "—")
    val model = obj.optString("modelId", "")
    return id to if (model.isNotEmpty()) "$reg ($model)" else reg
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
