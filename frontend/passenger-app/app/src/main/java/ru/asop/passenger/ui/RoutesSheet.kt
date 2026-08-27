package ru.asop.passenger.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ru.asop.passenger.network.LiveVehicle
import ru.asop.passenger.network.StopRoute

@Composable
fun RoutesSheet(
    route: StopRoute?,
    vehicles: List<LiveVehicle>,
    onVehicleClick: (LiveVehicle) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = route?.routeName ?: "Маршрут",
                style = MaterialTheme.typography.titleMedium,
            )
            route?.pathName?.let {
                Text(
                    text = "Путь: $it",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "ТС на маршруте (${vehicles.size})",
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(modifier = Modifier.height(8.dp))
            if (vehicles.isEmpty()) {
                Text(
                    text = "Нет ТС в рейсе",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 200.dp),
                ) {
                    items(vehicles) { vehicle ->
                        ListItem(
                            headlineContent = { Text(vehicle.vehicleNumber) },
                            supportingContent = {
                                Text("${vehicle.vehicleType} • ${vehicle.speedKmh?.toInt() ?: 0} км/ч")
                            },
                            modifier = Modifier.clickable { onVehicleClick(vehicle) },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}
