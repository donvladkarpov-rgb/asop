package ru.asop.terminal

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import ru.asop.terminal.service.NetworkMonitor
import ru.asop.terminal.worker.WorkScheduler
import javax.inject.Inject

@HiltAndroidApp
class AsopTerminalApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var workScheduler: WorkScheduler
    @Inject lateinit var networkMonitor: NetworkMonitor

    override fun onCreate() {
        super.onCreate()
        workScheduler.schedulePeriodicSync()
        networkMonitor.register()
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
