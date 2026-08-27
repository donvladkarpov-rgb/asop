package ru.asop.passenger.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import ru.asop.passenger.model.PassengerViewModel
import ru.asop.passenger.model.ScreenState
import ru.asop.passenger.network.LiveVehicle
import ru.asop.passenger.network.TrackPoint

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    viewModel: PassengerViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val vehicles by viewModel.vehicles.collectAsState()
    val stops by viewModel.stops.collectAsState()
    val selectedStop by viewModel.selectedStop.collectAsState()
    val stopRoutes by viewModel.stopRoutes.collectAsState()
    val selectedRoute by viewModel.selectedRoute.collectAsState()
    val selectedVehicle by viewModel.selectedVehicle.collectAsState()
    val vehicleTrack by viewModel.vehicleTrack.collectAsState()
    val screenState by viewModel.screenState.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()

    var mapView by remember { mutableStateOf<MapView?>(null) }
    val initialCameraSet = remember { mutableStateOf(false) }

    // Stable marker storage — plain HashMap, no Compose state
    val vehicleMarkerMap = remember { HashMap<String, Marker>() }
    val stopMarkerMap = remember { HashMap<String, Marker>() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        Configuration.getInstance().load(context, context.getSharedPreferences("osmdroid", 0))
        Configuration.getInstance().userAgentValue = context.packageName
    }

    // Filter to vehicles that have a snapped position (on a known route)
    val displayVehicles = remember(vehicles) {
        vehicles.filter { it.snappedLatitude != null && it.snappedLongitude != null }
    }

    // Update vehicle markers: add new, update positions, remove stale
    LaunchedEffect(displayVehicles) {
        val map = mapView ?: return@LaunchedEffect
        val seen = HashSet<String>()

        for (v in displayVehicles) {
            val lat = v.snappedLatitude ?: v.latitude
            val lon = v.snappedLongitude ?: v.longitude
            seen.add(v.vehicleId)
            val existing = vehicleMarkerMap[v.vehicleId]
            if (existing != null) {
                // Smoothly animate from current position to new one
                val current = existing.position
                existing.position = GeoPoint(lat, lon)
                if (current != null && (current.latitude != lat || current.longitude != lon)) {
                    val from = GeoPoint(current)
                    val to = GeoPoint(lat, lon)
                    scope.launch {
                        val durationMs = 4500L
                        val steps = 30
                        for (i in 1..steps) {
                            if (!isActive) break
                            val t = i.toFloat() / steps
                            val aLat = from.latitude + (to.latitude - from.latitude) * t
                            val aLon = from.longitude + (to.longitude - from.longitude) * t
                            existing.position = GeoPoint(aLat, aLon)
                            map.invalidate()
                            delay(durationMs / steps)
                        }
                        existing.position = to
                        map.invalidate()
                    }
                }
            } else {
                val m = Marker(map)
                m.position = GeoPoint(lat, lon)
                m.title = "${v.vehicleNumber} (${v.vehicleType})"
                m.snippet = buildString {
                    appendLine(v.vehicleName)
                    v.pathName?.let { appendLine("Маршрут: $it") }
                    v.speedKmh?.let { appendLine("Скорость: ${it.toInt()} км/ч") }
                }
                m.setOnMarkerClickListener { _, _ ->
                    viewModel.selectVehicle(v)
                    viewModel.loadVehicleTrack(v.vehicleId)
                    true
                }
                map.overlays.add(m)
                vehicleMarkerMap[v.vehicleId] = m
            }
        }

        val toRemove = vehicleMarkerMap.keys.filter { it !in seen }
        for (id in toRemove) {
            vehicleMarkerMap[id]?.let { map.overlays.remove(it) }
            vehicleMarkerMap.remove(id)
        }

        // First camera fit
        if (!initialCameraSet.value && displayVehicles.isNotEmpty()) {
            val pts = displayVehicles.map { GeoPoint(it.snappedLatitude ?: it.latitude, it.snappedLongitude ?: it.longitude) }
            if (pts.isNotEmpty()) {
                val bbox = BoundingBox.fromGeoPoints(pts)
                map.zoomToBoundingBox(bbox.increaseByScale(2.0f), true)
                initialCameraSet.value = true
            }
        }

        map.invalidate()
    }

    // Update track polyline
    LaunchedEffect(vehicleTrack) {
        val map = mapView ?: return@LaunchedEffect
        map.overlays.removeAll { it is Polyline }
        if (vehicleTrack.isNotEmpty()) {
            val pl = Polyline()
            pl.setPoints(vehicleTrack.map {
                GeoPoint(it.snappedLatitude ?: it.latitude, it.snappedLongitude ?: it.longitude)
            })
            pl.outlinePaint.color = android.graphics.Color.BLUE
            pl.outlinePaint.strokeWidth = 6f
            map.overlays.add(pl)
            map.invalidate()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (screenState) {
                            ScreenState.MAP_ONLY -> "Карта ТС"
                            ScreenState.STOP_SELECTED -> selectedStop?.stopName ?: "Остановка"
                            ScreenState.ROUTE_SELECTED -> selectedRoute?.routeName ?: "Маршрут"
                            ScreenState.VEHICLE_SELECTED -> selectedVehicle?.vehicleNumber ?: "ТС"
                        }
                    )
                },
                navigationIcon = {
                    if (screenState != ScreenState.MAP_ONLY) {
                        IconButton(onClick = {
                            when (screenState) {
                                ScreenState.VEHICLE_SELECTED -> viewModel.backToRoute()
                                ScreenState.ROUTE_SELECTED -> viewModel.backToStop()
                                ScreenState.STOP_SELECTED -> viewModel.backToMap()
                                else -> {}
                            }
                        }) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Назад")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            AndroidView(
                factory = { ctx ->
                    MapView(ctx).also {
                        mapView = it
                        it.setTileSource(TileSourceFactory.MAPNIK)
                        it.setMultiTouchControls(true)
                        it.controller.setZoom(15.0)
                        it.controller.setCenter(GeoPoint(44.95, 34.11))
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )

            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                )
            }

            error?.let { msg ->
                Snackbar(
                    modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                    action = {
                        TextButton(onClick = { viewModel.backToMap() }) {
                            Text("Закрыть")
                        }
                    },
                ) {
                    Text(msg)
                }
            }

            when (screenState) {
                ScreenState.STOP_SELECTED -> {
                    StopsSheet(
                        stop = selectedStop,
                        routes = stopRoutes,
                        onRouteClick = { viewModel.selectRoute(it) },
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
                ScreenState.ROUTE_SELECTED -> {
                    RoutesSheet(
                        route = selectedRoute,
                        vehicles = vehicles.filter { it.pathId == selectedRoute?.pathId },
                        onVehicleClick = {
                            viewModel.selectVehicle(it)
                            viewModel.loadVehicleTrack(it.vehicleId)
                        },
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
                ScreenState.VEHICLE_SELECTED -> {
                    VehicleSheet(
                        vehicle = selectedVehicle,
                        track = vehicleTrack,
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
                else -> {
                    FloatingActionButton(
                        onClick = {
                            val map = mapView ?: return@FloatingActionButton
                            val bbox = map.boundingBox
                            viewModel.loadStops(
                                southWest = "${bbox.latSouth},${bbox.lonWest}",
                                northEast = "${bbox.latNorth},${bbox.lonEast}",
                            )
                        },
                        modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                    ) {
                        Icon(Icons.Default.LocationOn, contentDescription = "Остановки")
                    }
                }
            }
        }
    }
}
