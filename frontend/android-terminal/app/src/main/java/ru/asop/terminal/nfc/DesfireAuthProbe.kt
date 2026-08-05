package ru.asop.terminal.nfc

import android.util.Log
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Проба криптографии DESFire дефолтным (заводским) ключом — 16 нулевых байт.
 *
 * Проверяем, что карта умеет реальную криптографию (AuthenticateAES 0xAA) и
 * поддерживает EV2/EV3 (GetCardCertificate 0x65). Оригинальная заводская карта
 * проходит AES-авторизацию дефолтным ключом; клон обычно либо не знает команду,
 * либо не завершает рукопожатие. Все команды идут по уже открытому IsoDep-каналу,
 * переключения RF-интерфейса нет.
 *
 * ВАЖНО: используются нативные опкоды DESFire (SelectApplication 0x5A,
 * GetKeySettings 0x45, AuthenticateAES 0xAA). Старая версия слала 0x6C/0x6F/0x1A
 * (GetValue/GetFileIDs/3K3DES) — из-за этого выводы были неверными.
 */
object DesfireAuthProbe {

    private const val TAG = "DesfireAuthProbe"
    private val DEFAULT_KEY = ByteArray(16)

    data class AuthResult(
        val selectedMasterApp: Boolean,
        val authSucceeded: Boolean,
        val legacyAuthSucceeded: Boolean,
        val aesKey1Auth: Boolean,
        val aesKey3Auth: Boolean,
        val keySettingsHex: String?,
        val keyVersion0: String?,
        val cardCertificateHex: String?,
        val readSignatureHex: String?,
        val aesKeyProbe: List<String>,
        val desKeyProbe: List<String>,
        val notes: List<String>
    )

    fun probe(transceive: (ByteArray) -> ByteArray): AuthResult {
        val notes = mutableListOf<String>()

        val selectResp = safe(transceive, byteArrayOf(0x5A.toByte(), 0, 0, 0), "SelectApplication(000000)", notes)
        val selected = selectResp != null && selectResp.size == 1 && (selectResp[0].toInt() and 0xFF) == 0x00
        if (!selected) {
            notes += "Master app не выбран: ${selectResp?.toHex() ?: "нет ответа"}"
        }

        // Пробуем GetKeySettings напрямую (без выбора приложения) и в ISO-7816 обёртке.
        val ksResp = safe(transceive, byteArrayOf(0x45.toByte()), "GetKeySettings", notes)
        val keySettings = ksResp
            ?.takeIf { it.isNotEmpty() && (it[0].toInt() and 0xFF) == 0x00 }
            ?.let { it.copyOfRange(1, it.size) }
        if (keySettings == null) {
            notes += "GetKeySettings: ${ksResp?.toHex() ?: "нет ответа"}"
        }
        val ksWrapped = safe(transceive, byteArrayOf(0x90.toByte(), 0x45.toByte(), 0, 0, 0), "GetKeySettings(wrapped)", notes)

        // GetKeyVersion(0) — EV1+-фича (opcode 0x64). EV0 ответил бы 0x1C.
        // Возвращает 0x00 + 1 байт версии ключа. Тип ключа (AES/3K3DES/DES) отсюда
        // не виден — только его версия, но сам факт ответа подтверждает EV1+.
        val keyVerResp = safe(transceive, byteArrayOf(0x64.toByte(), 0x00), "GetKeyVersion(0)", notes)
        val keyVersion0 = keyVerResp
            ?.takeIf { it.size >= 2 && (it[0].toInt() and 0xFF) == 0x00 }
            ?.let { "0x%02X".format(it[1].toInt() and 0xFF) }

        // Полные рукопожатия — каждое начинается с reselect, чтобы сбросить
        // промежуточное состояние auth (карта ждёт continuation после step1).
        val authSucceeded = reselectApp(transceive, notes) && authenticateAes(transceive, 0, notes)
        val aesKey1Ok = reselectApp(transceive, notes) && authenticateAes(transceive, 1, notes)
        val aesKey3Ok = reselectApp(transceive, notes) && authenticateAes(transceive, 3, notes)
        if (!authSucceeded) authenticateAesWrapped(transceive, notes)
        val legacyOk = reselectApp(transceive, notes) && authenticateLegacy3k3des(transceive, 0, notes)

        val cert = collectFrames(transceive, byteArrayOf(0x65.toByte()), "GetCardCertificate", notes)
        val sig = collectFrames(transceive, byteArrayOf(0x3C.toByte()), "ReadSignature(0x3C)", notes)

        // Расширенный зонд AES-ядра: реакция на AuthenticateAES(0xAA) для keyNo 0..3.
        // Reselect только если предыдущая проба вернула 0xAF (промежуточное состояние
        // auth — карта ждёт continuation). Финальные статусы (0xAE/0x1C/0x9D) не требуют
        // reselect. 0x1A-зонд не делаем — есть полный handshake на key0.
        val aesKeyProbe = probeKeyRange(transceive, 0xAA, "AuthAES", 0..3, notes)
        val desKeyProbe = emptyList<String>()

        return AuthResult(
            selectedMasterApp = selected,
            authSucceeded = authSucceeded,
            legacyAuthSucceeded = legacyOk,
            aesKey1Auth = aesKey1Ok,
            aesKey3Auth = aesKey3Ok,
            keySettingsHex = keySettings?.toHex(),
            keyVersion0 = keyVersion0,
            cardCertificateHex = cert?.toHex(),
            readSignatureHex = sig?.toHex(),
            aesKeyProbe = aesKeyProbe,
            desKeyProbe = desKeyProbe,
            notes = notes
        )
    }

    /** SelectApplication(000000) — сбрасывает промежуточное состояние auth. */
    private fun reselectApp(transceive: (ByteArray) -> ByteArray, notes: MutableList<String>): Boolean {
        val r = safe(transceive, byteArrayOf(0x5A.toByte(), 0, 0, 0), "ReselectApp", notes)
        return r != null && r.size == 1 && (r[0].toInt() and 0xFF) == 0x00
    }

    /**
     * Шлёт {opcode, keyNo} для keyNo в range и собирает hex-статусы.
     * Только step1 (без продолжения рукопожатия) — безопасно, не пишет на карту.
     * Reselect перед следующей пробой только если предыдущая вернула 0xAF
     * (промежуточное состояние auth). Финальные статусы (0xAE/0x1C/0x9D) не требуют
     * reselect. Возвращает список вида ["key0: AE", "key1: AF+16", ...].
     */
    private fun probeKeyRange(
        transceive: (ByteArray) -> ByteArray,
        opcode: Int,
        label: String,
        range: IntRange,
        notes: MutableList<String>
    ): List<String> {
        val out = mutableListOf<String>()
        var needReselect = false
        for (kn in range) {
            if (needReselect) reselectApp(transceive, notes)
            val r = safe(transceive, byteArrayOf(opcode.toByte(), kn.toByte()), "$label(k=$kn)", notes)
            val status = if (r != null && r.isNotEmpty()) {
                val b0 = r[0].toInt() and 0xFF
                needReselect = (b0 == 0xAF)
                if (b0 == 0xAF && r.size > 1) "0x%02X+%dб".format(b0, r.size - 1) else "0x%02X".format(b0)
            } else "—"
            out += "key$kn: $status"
        }
        return out
    }

    /** AuthenticateAES в ISO 7816-4 обёртке (CLA 0x90), как у некоторых карт. */
    private fun authenticateAesWrapped(
        transceive: (ByteArray) -> ByteArray,
        notes: MutableList<String>
    ): Boolean {
        val resp = safe(transceive, byteArrayOf(0x90.toByte(), 0xAA.toByte(), 0, 0, 0), "AuthenticateAES(wrapped)", notes)
        return if (resp != null && resp.size >= 16) {
            notes += "AuthenticateAES(wrapped): карта вернула ${resp.size} байт — обёртка понимается, но рукопожатие не проверено"
            true
        } else {
            notes += "AuthenticateAES(wrapped): ${resp?.toHex() ?: "нет ответа"}"
            false
        }
    }

    /** Рукопожатие AuthenticateAES (0xAA) по спецификации DESFire EV1+. */
    private fun authenticateAes(
        transceive: (ByteArray) -> ByteArray,
        keyNo: Int,
        notes: MutableList<String>
    ): Boolean {
        // Step1: AA KeyNo → AF <16 bytes> = E_K(RndB).
        // Используем unwrapFrame (не readResponse!) — 0xAF здесь означает
        // «auth in progress, send step2», а НЕ «fetch more frames».
        val chResp = safe(transceive, byteArrayOf(0xAA.toByte(), keyNo.toByte()), "AuthenticateAES(k=$keyNo,step1)", notes)
            ?: return false.also { notes += "AuthenticateAES(k=$keyNo): нет ответа на 0xAA" }
        val challenge = unwrapFrame(chResp)
        if (challenge == null || challenge.size != 16) {
            notes += "AuthenticateAES(k=$keyNo): challenge ${chResp.toHex()}"
            return false
        }
        val rndB = try {
            aesEcbDecrypt(DEFAULT_KEY, challenge)
        } catch (e: Exception) {
            notes += "AuthenticateAES(k=$keyNo): ${e.message}"
            return false
        }
        // Step2: AF <32 bytes> = E_K(RndA ‖ RotL(RndB)), IV = challenge.
        val rndA = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val msg = ByteArray(32)
        System.arraycopy(rndA, 0, msg, 0, 16)
        System.arraycopy(rotateLeft(rndB), 0, msg, 16, 16)
        val enc = try {
            aesCbcEncrypt(DEFAULT_KEY, challenge, msg)
        } catch (e: Exception) {
            notes += "AuthenticateAES(k=$keyNo): ${e.message}"
            return false
        }
        val cmd2 = ByteArray(1 + enc.size)
        cmd2[0] = 0xAF.toByte()
        System.arraycopy(enc, 0, cmd2, 1, enc.size)
        val resp2 = safe(transceive, cmd2, "AuthenticateAES(k=$keyNo,step2)", notes)
            ?: return false.also { notes += "AuthenticateAES(k=$keyNo): нет ответа на step2" }
        val final = unwrapFrame(resp2)
        if (final == null || final.size != 16) {
            notes += "AuthenticateAES(k=$keyNo): ответ2 ${resp2.toHex()}"
            return false
        }
        // Card → E_K(RotL(RndA)), IV = последние 16 байт step2 ciphertext.
        val expected = rotateLeft(rndA)
        val dec2 = try {
            aesCbcDecrypt(DEFAULT_KEY, enc.copyOfRange(16, 32), final)
        } catch (e: Exception) {
            notes += "AuthenticateAES(k=$keyNo): ${e.message}"
            return false
        }
        val ok = dec2.contentEquals(expected)
        val keyHex = String.format("%02X", keyNo)
        notes += if (ok) {
            "AuthenticateAES(0xAA, key=$keyHex): рукопожатие с дефолтным нулевым ключом ПРОЙДЕНО"
        } else {
            "AuthenticateAES(0xAA, key=$keyHex): проверка RndA не сошлась (ключ не дефолтный?)"
        }
        return ok
    }

    /**
     * Полное legacy-рукопожатие AuthenticateISO (0x1A, 3K3DES) нулевым ключом.
     *
     * Протокол (по NFCjLib authenticate() и TalkToYourDESFireCard):
     *   1) 1A KeyNo → AF <8> = E_K(RndB)
     *   2) RndB = 3DES-CBC-decr(K, IV=0, challenge); RndA = random(8)
     *   3) AF <E_K(RndA ‖ RotLeft(RndB))>, IV = challenge
     *   4) карта → E_K(RotLeft(RndA)), IV = последние 8 байт шага 3
     *   5) расшифровать и сравнить с RotLeft(RndA)
     * Если рукопожатие сошлось — карта делает настоящую 3DES-криптографию,
     * т.е. это настоящий DESFire (EV1 как минимум), а не клон-эмулятор.
     */
    private fun authenticateLegacy3k3des(
        transceive: (ByteArray) -> ByteArray,
        keyNo: Int,
        notes: MutableList<String>
    ): Boolean {
        val chResp = safe(transceive, byteArrayOf(0x1A.toByte(), keyNo.toByte()), "Legacy3K3DES(step1)", notes)
            ?: return false.also { notes += "Legacy3K3DES: нет ответа на 0x1A" }
        val challenge = unwrapFrame(chResp)
        if (challenge == null || challenge.size != 8) {
            notes += "Legacy3K3DES: challenge ${chResp.toHex()}"
            return false
        }
        val rndB = try {
            tripleDesCbcDecrypt(DEFAULT_KEY, ByteArray(8), challenge)
        } catch (e: Exception) {
            notes += "Legacy3K3DES: ${e.message}"
            return false
        }
        val rndA = ByteArray(8).also { SecureRandom().nextBytes(it) }
        val plaintext = ByteArray(16)
        System.arraycopy(rndA, 0, plaintext, 0, 8)
        System.arraycopy(rotateLeft(rndB), 0, plaintext, 8, 8)
        val ciphertext = try {
            tripleDesCbcEncrypt(DEFAULT_KEY, challenge, plaintext)
        } catch (e: Exception) {
            notes += "Legacy3K3DES: ${e.message}"
            return false
        }
        // континуация auth = опкод 0xAF (MORE) + данные
        val cmd2 = ByteArray(1 + ciphertext.size)
        cmd2[0] = 0xAF.toByte()
        System.arraycopy(ciphertext, 0, cmd2, 1, ciphertext.size)
        val resp2 = safe(transceive, cmd2, "Legacy3K3DES(step2)", notes)
            ?: return false.also { notes += "Legacy3K3DES: нет ответа на continuation" }
        val final = unwrapFrame(resp2)
        if (final == null || final.size != 8) {
            notes += "Legacy3K3DES: ответ2 ${resp2.toHex()}"
            return false
        }
        val iv2 = ciphertext.copyOfRange(8, 16)
        val dec = try {
            tripleDesCbcDecrypt(DEFAULT_KEY, iv2, final)
        } catch (e: Exception) {
            notes += "Legacy3K3DES: ${e.message}"
            return false
        }
        val ok = dec.contentEquals(rotateLeft(rndA))
        notes += if (ok) {
            "AuthenticateISO(0x1A): 3K3DES-рукопожатие нулевым ключом ПРОЙДЕНО → настоящий DESFire EV1"
        } else {
            "AuthenticateISO(0x1A): 3K3DES-рукопожатие не сошлось (ключ не дефолтный или клон)"
        }
        return ok
    }

    /** Снимает кадр ответа: 00 <data> → data, AF <data> → data (без досбора GetMoreFrames). */
    private fun unwrapFrame(resp: ByteArray): ByteArray? {
        if (resp.isEmpty()) return null
        return when (resp[0].toInt() and 0xFF) {
            0x00, 0xAF -> resp.copyOfRange(1, resp.size)
            else -> null
        }
    }

    /**
     * Читает ответ команды, дособирая многофреймовые ответы (статус 0xAF → GetMoreFrames 0xAF).
     * Возвращает данные без статус-байтов. Обрабатывает и сырой 16-байтный ciphertext
     * (без статуса, как в AuthAES), и кадрированный ответ.
     */
    private fun readResponse(
        transceive: (ByteArray) -> ByteArray,
        cmd: ByteArray,
        label: String,
        notes: MutableList<String>
    ): ByteArray? {
        var cur = try {
            transceive(cmd)
        } catch (e: Exception) {
            notes += "$label: ${e.message}"
            return null
        }
        if (cur.isEmpty()) return null
        Log.d(TAG, "$label -> ${cur.toHex()}")
        val b0 = cur[0].toInt() and 0xFF
        if (cur.size == 16 && b0 != 0xAF && b0 != 0x00) return cur
        if (b0 == 0x00 && cur.size > 1) return cur.copyOfRange(1, cur.size)
        if (b0 == 0xAF) {
            val out = java.io.ByteArrayOutputStream()
            var guard = 0
            while (cur.isNotEmpty() && (cur[0].toInt() and 0xFF) == 0xAF && guard++ < 16) {
                out.write(cur, 1, cur.size - 1)
                cur = try {
                    transceive(byteArrayOf(0xAF.toByte()))
                } catch (e: Exception) {
                    notes += "$label: ${e.message}"
                    break
                }
            }
            if (cur.isNotEmpty() && (cur[0].toInt() and 0xFF) == 0x00) {
                out.write(cur, 1, cur.size - 1)
            } else if (cur.isNotEmpty() && (cur[0].toInt() and 0xFF) != 0xAF) {
                out.write(cur, 0, cur.size)
            }
            return out.toByteArray()
        }
        notes += "$label: неожиданный ответ ${cur.toHex()}"
        return null
    }

    /** Собирает ответ с учётом кадров 0xAF (GetMoreFrames). Возвращает данные без статус-байтов. */
    private fun collectFrames(
        transceive: (ByteArray) -> ByteArray,
        cmd: ByteArray,
        label: String,
        notes: MutableList<String>
    ): ByteArray? {
        val first = try {
            transceive(cmd)
        } catch (e: Exception) {
            notes += "$label: ${e.message}"
            return null
        }
        if (first.isEmpty()) return null
        val firstByte = first[0].toInt() and 0xFF
        if (firstByte != 0xAF && firstByte != 0x00) {
            notes += "$label: статус ${String.format("0x%02X", firstByte)}"
            Log.d(TAG, "$label -> ${first.toHex()}")
            return null
        }
        val out = java.io.ByteArrayOutputStream()
        var resp = first
        var guard = 0
        while (resp.isNotEmpty() && (resp[0].toInt() and 0xFF) == 0xAF && guard++ < 32) {
            out.write(resp, 1, resp.size - 1)
            resp = try {
                transceive(byteArrayOf(0xAF.toByte()))
            } catch (e: Exception) {
                notes += "$label: ${e.message}"
                break
            }
        }
        if (resp.isNotEmpty() && (resp[0].toInt() and 0xFF) == 0x00) {
            out.write(resp, 1, resp.size - 1)
        }
        val data = out.toByteArray()
        Log.d(TAG, "$label -> ${data.size} bytes: ${data.toHex()}")
        return data
    }

    private fun safe(
        transceive: (ByteArray) -> ByteArray,
        cmd: ByteArray,
        label: String,
        notes: MutableList<String>
    ): ByteArray? = try {
        val resp = transceive(cmd)
        Log.d(TAG, "$label ${cmd.toHex()} -> ${resp.toHex()}")
        resp
    } catch (e: Exception) {
        Log.w(TAG, "$label ${cmd.toHex()} fail: ${e.message}")
        notes += "$label: ${e.message}"
        null
    }

    // ---------- AES ----------

    private fun aesEcbDecrypt(key: ByteArray, data: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/ECB/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
        return c.doFinal(data)
    }

    private fun aesCbcEncrypt(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/CBC/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return c.doFinal(data)
    }

    private fun aesCbcDecrypt(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/CBC/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return c.doFinal(data)
    }

    // ---------- 3DES ----------

    private fun tripleDesCbcEncrypt(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray {
        val c = Cipher.getInstance("DESede/CBC/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "DESede"), IvParameterSpec(iv))
        return c.doFinal(data)
    }

    private fun tripleDesCbcDecrypt(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray {
        val c = Cipher.getInstance("DESede/CBC/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "DESede"), IvParameterSpec(iv))
        return c.doFinal(data)
    }

    /** Циклический сдвиг влево на 1 байт (по спецификации DESFire). */
    private fun rotateLeft(b: ByteArray): ByteArray {
        val out = ByteArray(b.size)
        for (i in b.indices) out[i] = b[(i + 1) % b.size]
        return out
    }

    fun hex(bytes: ByteArray): String = bytes.joinToString(" ") { String.format("%02X", it) }
}

private fun ByteArray.toHex(): String = joinToString(" ") { String.format("%02X", it) }
