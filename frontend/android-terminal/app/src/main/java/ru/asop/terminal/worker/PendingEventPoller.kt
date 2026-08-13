package ru.asop.terminal.worker

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import ru.asop.terminal.db.SyncPreferences
import ru.asop.terminal.db.dao.PendingEventDao
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Промпт 014: фоновый poller раз в 500мс при наличии интернета.
 *
 * Не зависит от WorkManager периодических задач (minimum 15 мин).
 * Запускается при старте приложения и живёт пока приложение живо.
 * При обнаружении PENDING событий — немедленно запускает SyncWorker.
 */
@Singleton
class PendingEventPoller @Inject constructor(
    @ApplicationContext private val context: Context,
    private val pendingEventDao: PendingEventDao,
    private val syncPreferences: SyncPreferences
) {
    private val tag = "PendingPoller"
    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        job = CoroutineScope(Dispatchers.IO).launch {
            while (true) {
                try {
                    val enabled = syncPreferences.syncEnabled.first()
                    if (!enabled) {
                        delay(5000)
                        continue
                    }
                    val pendingCount = pendingEventDao.countPending()
                    if (pendingCount > 0) {
                        Log.d(tag, "found $pendingCount pending, enqueueing one-shot sync")
                        val constraints = Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build()
                        val request = OneTimeWorkRequestBuilder<SyncWorker>()
                            .setConstraints(constraints)
                            .build()
                        WorkManager.getInstance(context).enqueue(request)
                    }
                } catch (e: Exception) {
                    Log.w(tag, "poll error: ${e.message}")
                }
                delay(500)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}