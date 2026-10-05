package ru.asop.terminal.service

import android.content.Context
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.room.withTransaction
import androidx.work.Operation
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import ru.asop.terminal.db.AppDatabase
import ru.asop.terminal.db.dao.DeltaSyncJobDao
import ru.asop.terminal.db.dao.PendingEventDao
import ru.asop.terminal.db.dao.SessionDao
import ru.asop.terminal.db.dao.SyncMetaDao
import ru.asop.terminal.db.dao.TerminalKeyDao
import ru.asop.terminal.db.dao.TransactionDao
import ru.asop.terminal.db.dao.TripPaymentDao
import ru.asop.terminal.worker.PendingEventPoller
import ru.asop.terminal.worker.WorkScheduler
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/** Что было снесено — только для лога и показа в UI. */
data class LocalResetReport(
    val pendingEvents: Int,
    val sessions: Int,
    val deltaJobs: Int
)

/**
 * Полный сброс локального состояния терминала при повторной регистрации.
 *
 * Зачем: после пересоздания БД на сервере (`down -v`) терминал хранит watermark
 * впереди сервера (sync_meta.lastVersion > asop_delta_version_seq), поэтому дельта
 * вечно возвращает 0 чанков. Плюс копится очередь неотправленных событий (SENDING/FAILED
 * по 10+ тыс. штук), из-за чего FullDumpDownloadWorker не доходит до опроса события.
 *
 * Порядок важен: сперва гасим воркеры (иначе успеют записать данные после очистки),
 * затем чистим БД одной транзакцией, затем поднимаем цепочки заново — с watermark = null,
 * то есть следующая дельта забирает все справочники с нуля.
 */
@Singleton
class LocalStateResetter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: AppDatabase,
    private val pendingEventDao: PendingEventDao,
    private val sessionDao: SessionDao,
    private val transactionDao: TransactionDao,
    private val tripPaymentDao: TripPaymentDao,
    private val deltaSyncJobDao: DeltaSyncJobDao,
    private val terminalKeyDao: TerminalKeyDao,
    private val syncMetaDao: SyncMetaDao,
    private val workScheduler: WorkScheduler,
    private val pendingEventPoller: PendingEventPoller
) {
    private val tag = "LocalStateReset"

    suspend fun reset(): LocalResetReport {
        val report = LocalResetReport(
            pendingEvents = pendingEventDao.count(),
            sessions = sessionDao.count(),
            deltaJobs = deltaSyncJobDao.count()
        )

        // 1. Гасим всё, что что-то делает: воркеры WorkManager (включая текущие),
        //    фоновый poller и GPS-сервис (иначе он продолжит писать точки по смене, которой нет).
        awaitCancellation(WorkManager.getInstance(context).cancelAllWork())
        pendingEventPoller.stop()
        GpsTrackingService.stop(context)

        // 2. Чистим БД одной транзакцией (sync_meta без строки → watermark 0).
        database.withTransaction {
            pendingEventDao.clearAll()
            sessionDao.clearAll()
            transactionDao.clearAll()
            tripPaymentDao.clearAll()
            deltaSyncJobDao.clearAll()
            terminalKeyDao.clearAll()
            syncMetaDao.clearAll()
        }

        Log.w(
            tag,
            "local state wiped: pendingEvents=${report.pendingEvents}, sessions=${report.sessions}, " +
                "deltaJobs=${report.deltaJobs}; workers restarted"
        )

        // 3. Поднимаем цепочки заново: дельта уйдёт сразу (lastVersion=null → полный набор).
        pendingEventPoller.start()
        workScheduler.startWorkers()

        return report
    }

    /** Дожидаемся фактической отмены воркеров, чтобы никто не записал данные после очистки. */
    private suspend fun awaitCancellation(operation: Operation) {
        runCatching {
            suspendCancellableCoroutine { cont ->
                operation.result.addListener(
                    { cont.resume(Unit) },
                    ContextCompat.getMainExecutor(context)
                )
            }
        }
    }
}