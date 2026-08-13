package ru.asop.terminal.ui.screen

import android.app.Application
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import ru.asop.terminal.cert.MtlsManager
import ru.asop.terminal.db.SyncPreferences
import ru.asop.terminal.service.CertificateService
import javax.inject.Inject

/**
 * Промпт 013: VM поверх MtlsManager — отслеживает cert expiry и поддерживает renew.
 */
@HiltViewModel
class CertExpiryViewModel @Inject constructor(
    application: Application,
    private val mtlsManager: MtlsManager,
    private val certificateService: CertificateService,
    private val syncPreferences: SyncPreferences
) : AndroidViewModel(application) {

    sealed interface Status {
        data object NotProvisioned : Status
        data class Ok(val daysLeft: Long) : Status
        data class Warning(val daysLeft: Long) : Status
        data class Critical(val daysLeft: Long, val expired: Boolean) : Status
    }

    private val _status = MutableStateFlow<Status>(Status.Ok(Long.MAX_VALUE))
    val status: StateFlow<Status> = _status.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        val days = mtlsManager.getDaysUntilExpiry()
        val status = when {
            days == null -> Status.NotProvisioned
            days <= 0L -> Status.Critical(days, expired = true)
            days < 7L -> Status.Critical(days, expired = false)
            days < 30L -> Status.Warning(days)
            else -> Status.Ok(days)
        }
        _status.value = status
    }

    /**
     * Промпт 013: ручной перевыпуск сертификата без регенерации ключа.
     * Шлёт существующий publicKey через /api/v1/terminals/cert-sign на сервер,
     * который подписывает новый X.509 с тем же pubkey. Старая ключевая пара
     * остаётся в AndroidKeyStore.
     */
    suspend fun renewCertificate(): Result<Unit> {
        return try {
            val terminalId = syncPreferences.terminalId.first() ?: return Result.failure(
                IllegalStateException("Не зарегистрирован terminalId")
            )
            if (!mtlsManager.hasKeyPair() || !mtlsManager.hasCertificate()) {
                return Result.failure(
                    IllegalStateException("Нет ECC keyPair/certificate — нужен provision()")
                )
            }
            val androidId = Settings.Secure.getString(
                getApplication<Application>().contentResolver,
                Settings.Secure.ANDROID_ID
            ) ?: return Result.failure(IllegalStateException("Не найден ANDROID_ID"))
            certificateService.refreshCertificate(
                terminalSerial = androidId,
                terminalId = terminalId
            )
            refresh()
            Result.success(Unit)
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }
}
