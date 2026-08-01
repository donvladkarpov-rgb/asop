package ru.asop.terminal.ui.screen

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ru.asop.terminal.db.SyncPreferences
import ru.asop.terminal.db.dao.DeltaSyncJobDao
import ru.asop.terminal.db.dao.ReferenceRowDao
import ru.asop.terminal.network.GatewayApi
import ru.asop.terminal.network.models.DeltaSyncRequest
import ru.asop.terminal.network.models.FullSyncRequest
import ru.asop.terminal.worker.WorkScheduler
import javax.inject.Inject

@HiltViewModel
class ReferenceSyncViewModel @Inject constructor(
    private val syncPreferences: SyncPreferences,
    private val gatewayApi: GatewayApi,
    private val workScheduler: WorkScheduler,
    private val deltaSyncJobDao: DeltaSyncJobDao,
    private val referenceRowDao: ReferenceRowDao
) : ViewModel() {

    val pendingDeltaCount: StateFlow<Int> = deltaSyncJobDao.observePending()
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val activeReferenceCount: StateFlow<Int> = referenceRowDao.observeActiveCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    /** Дельта сейчас (ручной триггер). */
    fun requestDeltaSync() {
        viewModelScope.launch {
            workScheduler.enqueueOneShotDelta()
        }
    }

    /** Полная выкачка: запрос → worker скачает ZIP и накатит. */
    fun requestFullSync() {
        viewModelScope.launch {
            val terminalId = syncPreferences.terminalId.first() ?: return@launch
            if (terminalId.isBlank()) return@launch
            try {
                val response = gatewayApi.fullSync(FullSyncRequest(terminalId))
                val eventId = response.body()?.eventId ?: return@launch
                workScheduler.enqueueFullDump(eventId)
            } catch (_: Exception) {
                // сеть недоступна — worker сам перезапросит позже
            }
        }
    }
}
