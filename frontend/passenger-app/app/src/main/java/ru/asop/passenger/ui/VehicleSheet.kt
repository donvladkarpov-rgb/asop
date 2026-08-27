package ru.asop.passenger.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ru.asop.passenger.network.LiveVehicle
import ru.asop.passenger.network.TrackPoint
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun VehicleSheet(
    vehicle: LiveVehicle?,
    track: List<TrackPoint>,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            vehicle?.let {
                Text(
                    text = it.vehicleNumber,
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    text = it.vehicleType,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(8.dp))

                it.pathName?.let { path ->
                    InfoRow("Маршрут", path)
                }
                it.speedKmh?.let { speed ->
                    InfoRow("Скорость", "${speed.toInt()} км/ч")
                }
                InfoRow(
                    "Обновлено",
                    try {
                        Instant.parse(it.recordedAt)
                            .atZone(ZoneId.systemDefault())
                            .format(DateTimeFormatter.ofPattern("HH:mm:ss"))
                    } catch (_: Exception) {
                        it.recordedAt
                    },
                )
                InfoRow("Точек в следе", "${track.size}")
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
