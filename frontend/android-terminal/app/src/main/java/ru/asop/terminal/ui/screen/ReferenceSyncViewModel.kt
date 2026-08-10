package ru.asop.terminal.ui.screen

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ru.asop.terminal.db.DeltaProgressTracker
import ru.asop.terminal.db.SyncPreferences
import ru.asop.terminal.db.dao.DeltaSyncJobDao
import ru.asop.terminal.db.dao.ReferenceRowDao
import ru.asop.terminal.db.dao.SyncMetaDao
import ru.asop.terminal.db.entity.DeltaSyncJobEntity
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
    private val referenceRowDao: ReferenceRowDao,
    private val syncMetaDao: SyncMetaDao,
    private val deltaProgressTracker: DeltaProgressTracker
) : ViewModel() {

    private companion object {
        const val TAG = "ReferenceSyncViewModel"
    }

    val pendingDeltaCount: StateFlow<Int> = deltaSyncJobDao.observePending()
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val activeReferenceCount: StateFlow<Int> = referenceRowDao.observeActiveCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val deltaProgress: StateFlow<DeltaProgressTracker.DeltaProgress?> = deltaProgressTracker.progress
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val deltaJobsEnabled: StateFlow<Boolean> = syncPreferences.deltaJobsEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    /** Переключатель дельта-джоб (остановить/запустить). */
    fun toggleDeltaJobs() {
        viewModelScope.launch {
            val enabled = syncPreferences.deltaJobsEnabled.first()
            syncPreferences.setDeltaJobsEnabled(!enabled)
            if (enabled) {
                workScheduler.stopDeltaJobs()
            } else {
                workScheduler.startDeltaJobs()
            }
        }
    }

    /** Дельта сейчас (ручной триггер). Идёт всегда, независимо от флага deltaJobsEnabled. */
    fun requestDeltaSync() {
        viewModelScope.launch {
            val terminalId = syncPreferences.terminalId.first() ?: return@launch
            if (terminalId.isBlank()) return@launch
            val carrierId = syncPreferences.carrierId.first()
            val regionId = syncPreferences.regionId.first()
            val lastVersion = syncMetaDao.get()?.lastVersion
            try {
                val response = gatewayApi.deltaSync(
                    DeltaSyncRequest(terminalId, carrierId, regionId, lastVersion)
                )
                if (!response.isSuccessful || response.body() == null) {
                    Log.w(TAG, "DeltaSync rejected: HTTP ${response.code()}")
                    workScheduler.enqueueOneShotDelta(terminalId, carrierId, regionId, lastVersion)
                    return@launch
                }
                val eventId = response.body()!!.eventId
                deltaSyncJobDao.insert(
                    DeltaSyncJobEntity(
                        eventId = eventId,
                        status = "PENDING",
                        requestedAt = System.currentTimeMillis()
                    )
                )
                workScheduler.enqueueForcedDeltaChunkPoll()
            } catch (_: Exception) {
                // сеть недоступна — повторит через один-shot DeltaSyncWorker при восстановлении связи
                workScheduler.enqueueOneShotDelta(terminalId, carrierId, regionId, lastVersion)
            }
        }
    }

    /** Полная выкачка: запрос → worker скачает ZIP и накатит. */
    fun requestFullSync() {
        viewModelScope.launch {
            val terminalId = syncPreferences.terminalId.first() ?: return@launch
            if (terminalId.isBlank()) return@launch
            try {
                val carrierId = syncPreferences.carrierId.first()
                val regionId = syncPreferences.regionId.first()
                val response = gatewayApi.fullSync(FullSyncRequest(terminalId, carrierId, regionId))
                val eventId = response.body()?.eventId ?: return@launch
                deltaSyncJobDao.insert(
                    DeltaSyncJobEntity(
                        eventId = eventId,
                        status = "PENDING",
                        requestedAt = System.currentTimeMillis()
                    )
                )
                workScheduler.enqueueFullDump(eventId)
            } catch (_: Exception) {
                // сеть недоступна — worker сам перезапросит позже
            }
        }
    }
}
