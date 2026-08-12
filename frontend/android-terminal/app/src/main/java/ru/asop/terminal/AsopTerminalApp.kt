package ru.asop.terminal

import android.app.Application
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import ru.asop.terminal.db.SyncPreferences
import ru.asop.terminal.db.dao.TerminalKeyDao
import ru.asop.terminal.service.NetworkMonitor
import ru.asop.terminal.worker.WorkScheduler
import javax.inject.Inject

@HiltAndroidApp
class AsopTerminalApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var workScheduler: WorkScheduler
    @Inject lateinit var networkMonitor: NetworkMonitor
    @Inject lateinit var syncPreferences: SyncPreferences
    @Inject lateinit var terminalKeyDao: TerminalKeyDao

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        workScheduler.schedulePeriodicSync()
        networkMonitor.register()
        triggerInitialDeltaIfReady()
    }

    /**
     * Промпт 008 — fallback auto-delta:
     * Если приложение запускается с уже подписанным сертификатом (terminalId задан),
     * но terminal_keys пуст (например, "down -v" снёс том ИЛИ дельту ещё не качали и
     * пользователь ушёл с экрана сразу после sign — а не после успешной sync), —
     * сразу записываем одноразовую дельту через WorkManager.
     * Иначе: условия AuthSector1 fail на Mifare Classic → "Карта не идентифицирована".
     */
    private fun triggerInitialDeltaIfReady() {
        appScope.launch {
            try {
                val terminalId = syncPreferences.terminalId.first()
                if (terminalId.isNullOrBlank()) return@launch
                val keysCount = terminalKeyDao.count()
                if (keysCount > 0) {
                    Log.i("AsopTerminalApp", "terminal_keys уже заполнены (rows=$keysCount) — пропускаем")
                    return@launch
                }
                Log.w("AsopTerminalApp", "terminal_keys пуст + terminalId=$terminalId → enqueueOneShotDelta")
                workScheduler.enqueueOneShotDelta(
                    terminalId = terminalId,
                    carrierId = syncPreferences.carrierId.first(),
                    regionId = syncPreferences.regionId.first(),
                    lastVersion = null
                )
            } catch (e: Exception) {
                Log.w("AsopTerminalApp", "triggerInitialDeltaIfReady exception: ${e.message}")
            }
        }
    }

    override fun onTerminate() {
        networkMonitor.unregister()
        super.onTerminate()
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()
}
