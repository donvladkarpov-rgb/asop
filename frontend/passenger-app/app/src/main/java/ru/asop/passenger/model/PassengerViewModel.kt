package ru.asop.passenger.model

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ru.asop.passenger.network.PassengerApi
import ru.asop.passenger.network.LiveVehicle
import ru.asop.passenger.network.Stop
import ru.asop.passenger.network.StopRoute
import ru.asop.passenger.network.TrackPoint
import javax.inject.Inject

enum class ScreenState {
    MAP_ONLY,
    STOP_SELECTED,
    ROUTE_SELECTED,
    VEHICLE_SELECTED,
}

@HiltViewModel
class PassengerViewModel @Inject constructor(
    private val api: PassengerApi,
) : ViewModel() {

    private val _screenState = MutableStateFlow(ScreenState.MAP_ONLY)
    val screenState: StateFlow<ScreenState> = _screenState.asStateFlow()

    private val _vehicles = MutableStateFlow<List<LiveVehicle>>(emptyList())
    val vehicles: StateFlow<List<LiveVehicle>> = _vehicles.asStateFlow()

    private val _stops = MutableStateFlow<List<Stop>>(emptyList())
    val stops: StateFlow<List<Stop>> = _stops.asStateFlow()

    private val _selectedStop = MutableStateFlow<Stop?>(null)
    val selectedStop: StateFlow<Stop?> = _selectedStop.asStateFlow()

    private val _stopRoutes = MutableStateFlow<List<StopRoute>>(emptyList())
    val stopRoutes: StateFlow<List<StopRoute>> = _stopRoutes.asStateFlow()

    private val _selectedRoute = MutableStateFlow<StopRoute?>(null)
    val selectedRoute: StateFlow<StopRoute?> = _selectedRoute.asStateFlow()

    private val _selectedVehicle = MutableStateFlow<LiveVehicle?>(null)
    val selectedVehicle: StateFlow<LiveVehicle?> = _selectedVehicle.asStateFlow()

    private val _vehicleTrack = MutableStateFlow<List<TrackPoint>>(emptyList())
    val vehicleTrack: StateFlow<List<TrackPoint>> = _vehicleTrack.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        startPolling()
    }

    private fun startPolling() {
        viewModelScope.launch {
            while (isActive) {
                try {
                    _vehicles.value = api.getLiveVehicles(freshSec = 120)
                    _error.value = null
                } catch (e: Exception) {
                    _error.value = e.message
                }
                delay(3_000)
            }
        }
    }

    fun loadStops(southWest: String, northEast: String) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                _stops.value = api.getStopsInBBox(southWest, northEast)
                _error.value = null
            } catch (e: Exception) {
                _error.value = e.message
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun selectStop(stop: Stop) {
        _selectedStop.value = stop
        _screenState.value = ScreenState.STOP_SELECTED
        viewModelScope.launch {
            try {
                _stopRoutes.value = api.getRoutesForStop(stop.stopId)
                _error.value = null
            } catch (e: Exception) {
                _error.value = e.message
            }
        }
    }

    fun selectRoute(route: StopRoute) {
        _selectedRoute.value = route
        _screenState.value = ScreenState.ROUTE_SELECTED
    }

    fun selectVehicle(vehicle: LiveVehicle) {
        _selectedVehicle.value = vehicle
        _screenState.value = ScreenState.VEHICLE_SELECTED
    }

    fun loadVehicleTrack(vehicleId: String) {
        viewModelScope.launch {
            try {
                _vehicleTrack.value = api.getVehicleTrack(vehicleId, minutes = 15)
                _error.value = null
            } catch (e: Exception) {
                _error.value = e.message
            }
        }
    }

    fun backToMap() {
        _selectedStop.value = null
        _selectedRoute.value = null
        _selectedVehicle.value = null
        _vehicleTrack.value = emptyList()
        _stopRoutes.value = emptyList()
        _screenState.value = ScreenState.MAP_ONLY
    }

    fun backToStop() {
        _selectedRoute.value = null
        _selectedVehicle.value = null
        _vehicleTrack.value = emptyList()
        _screenState.value = ScreenState.STOP_SELECTED
    }

    fun backToRoute() {
        _selectedVehicle.value = null
        _vehicleTrack.value = emptyList()
        _screenState.value = ScreenState.ROUTE_SELECTED
    }
}
