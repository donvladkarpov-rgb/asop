package ru.asop.payment.core

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.ft.possystemapi.server.ServiceManager
import com.ft.possystemapi.server.application.IApplicationManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Работа с API-разрешениями FTSDK SysAPI (wirelist `com.ft.apipermission.provider`).
 *
 * `manageEmvclAppParameters(ADD)` в ядре защищён проверкой `checkApiPermission` —
 * вызывающему приложению нужно быть в белом списке. Здесь пробуем добавить себя
 * (`ru.asop.payment`) через `IApplicationManager.setAddAppWhitelist`.
 */
object AsopApiPermission {

    private const val TAG = "AsopApiPermission"
    private const val PKG = "ru.asop.payment"

    /** Bind к SysAPI-серверу и вернуть IApplicationManager (null — недоступен). */
    private fun appManager(context: Context): IApplicationManager? {
        return runCatching {
            val latch = CountDownLatch(1)
            val failCode = AtomicInteger(-1)
            val cb = object : ServiceManager.ServiceConnectCallback {
                override fun onSuccess() { latch.countDown() }
                override fun onFail(code: Int) { failCode.set(code); latch.countDown() }
            }
            ServiceManager.getInstance(context)
            ServiceManager.bindPosSysServerSync(context, cb)
            val ok = latch.await(15, TimeUnit.SECONDS)
            if (!ok || failCode.get() != -1) return null
            val binder = ServiceManager.getApplicationManager() ?: return null
            IApplicationManager.Stub.asInterface(binder)
        }.getOrElse { e ->
            Log.e(TAG, "appManager bind failed: ${e.message}")
            null
        }
    }

    /** Проверяет статус whitelist и пытается добавить себя. Возвращает диагностику JSON-строкой. */
    fun ensureWhitelisted(context: Context): String {
        val am = appManager(context) ?: return """{"error":"SysAPI IApplicationManager недоступен (bind?)"}"""

        val before = runCatching { am.getAppInWhitelist(PKG) }.getOrNull()
        val setRc = runCatching { am.setAddAppWhitelist(PKG, true) }.getOrNull()
        val after = runCatching { am.getAppInWhitelist(PKG) }.getOrNull()

        Log.i(TAG, "whitelist before=$before setAddAppWhitelist=$setRc after=$after")

        return """{"package":"$PKG","inWhitelistBefore":$before,"setAddAppWhitelist":$setRc,"inWhitelistAfter":$after}"""
    }

    /** Регистрирует подписочный сертификат приложения в FTSDK (API-permission gate). */
    fun registerSignCert(context: Context): String {
        val am = appManager(context) ?: return """{"error":"SysAPI IApplicationManager недоступен (bind?)"}"""

        val certBytes = readOwnSignCert(context)
        val certNameBefore = runCatching { am.getSignCertificateName() }.getOrNull()
        val addRc = if (certBytes == null) null
            else runCatching { am.addAppSignCertificate(certBytes) }.getOrNull()
        val certNameAfter = runCatching { am.getSignCertificateName() }.getOrNull()

        Log.i(TAG, "signCert certLen=${certBytes?.size} nameBefore=$certNameBefore addAppSignCertificate=$addRc nameAfter=$certNameAfter")

        return """{"package":"$PKG","certBytes":${certBytes?.size ?: 0},"nameBefore":${org.json.JSONObject.quote(certNameBefore)},"addAppSignCertificate":$addRc,"nameAfter":${org.json.JSONObject.quote(certNameAfter)}}"""
    }

    private fun readOwnSignCert(context: Context): ByteArray? = runCatching {
        val pm = context.packageManager
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pm.getPackageInfo(PKG, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(PKG, PackageManager.GET_SIGNATURES).signatures
        }
        signatures?.firstOrNull()?.toByteArray()
    }.getOrElse { e ->
        Log.e(TAG, "readOwnSignCert failed: ${e.message}")
        null
    }
}
