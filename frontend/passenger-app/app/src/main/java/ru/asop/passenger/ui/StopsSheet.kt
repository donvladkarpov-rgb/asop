package ru.asop.passenger.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ru.asop.passenger.network.Stop
import ru.asop.passenger.network.StopRoute

@Composable
fun StopsSheet(
    stop: Stop?,
    routes: List<StopRoute>,
    onRouteClick: (StopRoute) -> Unit,
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
                text = stop?.stopName ?: "Остановка",
                style = MaterialTheme.typography.titleMedium,
            )
            stop?.stopAddress?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Маршруты через остановку (${routes.size})",
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(modifier = Modifier.height(8.dp))
            LazyColumn(
                modifier = Modifier.heightIn(max = 200.dp),
            ) {
                items(routes) { route ->
                    ListItem(
                        headlineContent = { Text(route.routeName) },
                        supportingContent = { Text(route.routeNumber ?: "") },
                        modifier = Modifier.clickable { onRouteClick(route) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}
