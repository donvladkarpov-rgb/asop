package ru.asop.terminal.ui.screen

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import ru.asop.terminal.db.SyncPreferences
import ru.asop.terminal.db.dao.PendingEventDao
import ru.asop.terminal.db.dao.SessionDao
import ru.asop.terminal.db.entity.SessionEntity
import ru.asop.terminal.worker.WorkScheduler
import javax.inject.Inject

@HiltViewModel
class SyncViewModel @Inject constructor(
    private val pendingEventDao: PendingEventDao,
    private val sessionDao: SessionDao,
    private val syncPreferences: SyncPreferences,
    private val workScheduler: WorkScheduler
) : ViewModel() {

    val pendingCount: StateFlow<Int> = pendingEventDao.observePendingCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val currentSession: StateFlow<SessionEntity?> = sessionDao.observeCurrentOpenShift()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val lastSyncTime: StateFlow<Long?> = syncPreferences.lastSyncTime
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val syncEnabled: StateFlow<Boolean> = syncPreferences.syncEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    fun triggerSync() {
        workScheduler.enqueueOneShotSync()
    }

    fun setSyncEnabled(enabled: Boolean) {
        viewModelScope.launch {
            syncPreferences.setSyncEnabled(enabled)
        }
    }
}
