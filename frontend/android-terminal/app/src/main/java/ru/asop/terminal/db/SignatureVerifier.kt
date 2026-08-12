package ru.asop.terminal.db

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import ru.asop.proto.v1.CardIdentity as ProtoCardIdentity
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.MGF1ParameterSpec
import java.security.spec.PSSParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Верификация RSA-PSS-SHA256 подписи cardIdentity.
 * Использует публичный ключ сервера, сохранённый при регистрации терминала.
 */
@Singleton
class SignatureVerifier @Inject constructor(
    private val syncPreferences: SyncPreferences
) {
    private companion object {
        private const val TAG = "SignatureVerifier"
    }

    /** Загружает сохранённый публичный ключ. */
    private suspend fun loadPublicKey(): PublicKey? {
        val pem = syncPreferences.serverPublicKey.first() ?: return null
        return try {
            val b64 = pem
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replace("\n", "")
                .replace("\r", "")
                .trim()
            val der = Base64.decode(b64, Base64.DEFAULT)
            val spec = X509EncodedKeySpec(der)
            KeyFactory.getInstance("RSA").generatePublic(spec)
        } catch (e: Exception) {
            Log.w(TAG, "loadPublicKey: ${e.message}")
            null
        }
    }

    /** Экранирует спецсимволы в JSON-строке (копия escapeJson из ViewModel). */
    private fun escapeJson(s: String): String = s
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")

    /** Строит canonical JSON строку из proto CardIdentity (тот же порядок ключей, что у сервера). */
    fun buildCanonicalString(proto: ProtoCardIdentity): String {
        val keys = listOf(
            "cardId", "uid", "regionId", "organizerId", "carrierId",
            "cardsDistributorId", "auditServiceId", "userId", "roles"
        )
        return keys.joinToString(",") { key ->
            if (key == "roles") {
                val items = proto.rolesList.joinToString(",") { "\"${escapeJson(it)}\"" }
                "\"roles\":[$items]"
            } else {
                val raw = when (key) {
                    "cardId" -> proto.cardId
                    "uid" -> proto.uid
                    "regionId" -> proto.regionId
                    "organizerId" -> proto.organizerId
                    "carrierId" -> proto.carrierId
                    "cardsDistributorId" -> proto.cardsDistributorId
                    "auditServiceId" -> proto.auditServiceId
                    "userId" -> proto.userId
                    else -> ""
                }
                "\"$key\":\"${escapeJson(raw)}\""
            }
        }.let { "{$it}" }
    }

    /**
     * Верифицирует подпись cardIdentity.
     * @param protoBytes содержимое File 0 (proto binary)
     * @param signatureBase64 содержимое File 1 (base64 RSA-PSS-SHA256 подпись canonical JSON)
     * @return true если подпись верна, false если нет или ключ не загружен
     */
    suspend fun verify(protoBytes: ByteArray, signatureBase64: String): Boolean {
        val publicKey = loadPublicKey() ?: return false
        val proto = try {
            ProtoCardIdentity.parseFrom(protoBytes)
        } catch (e: Exception) {
            Log.w(TAG, "verify: не удалось распарсить proto")
            return false
        }
        val canonicalBytes = buildCanonicalString(proto).toByteArray(Charsets.UTF_8)
        val sigBytes = try {
            Base64.decode(signatureBase64, Base64.DEFAULT)
        } catch (e: Exception) {
            Log.w(TAG, "verify: не удалось декодировать подпись")
            return false
        }
        return verifyRaw(canonicalBytes, sigBytes, publicKey)
    }

    /**
     * Sync-вариант: подпись в RAW-байтах (без base64) — используется для MIFARE Classic,
     * где подпись хранится на карте как 256 байт RSA-PSS-SHA256, без base64-кодирования.
     * Публичный ключ передаётся снаружи (предзагружен из SyncPreferences / CryptoService).
     * @param canonicalBytes байты canonical JSON (построен тем же `buildCanonicalString`, что и для DESFire)
     * @param signatureRaw 256-байтная RSA-PSS-SHA256 подпись в бинарном виде
     * @param publicKey заранее загруженный RSA-публичный ключ сервера
     */
    fun verifyRaw(canonicalBytes: ByteArray, signatureRaw: ByteArray, publicKey: PublicKey): Boolean {
        return try {
            val signature = Signature.getInstance("RSASSA-PSS")
            val spec = PSSParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, 32, 1)
            signature.setParameter(spec)
            signature.initVerify(publicKey)
            signature.update(canonicalBytes)
            signature.verify(signatureRaw)
        } catch (e: Exception) {
            Log.w(TAG, "verifyRaw: ${e.message}")
            false
        }
    }

    /** Удобный suspend-фасад: загружает ключ сам и делегирует в sync-вариант. */
    suspend fun verifyRaw(canonicalBytes: ByteArray, signatureRaw: ByteArray): Boolean {
        val publicKey = loadPublicKey() ?: return false
        return verifyRaw(canonicalBytes, signatureRaw, publicKey)
    }
}
