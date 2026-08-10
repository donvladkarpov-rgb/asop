package ru.asop.terminal.nfc

import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.util.Log
import java.io.IOException
import java.security.SecureRandom
import java.util.zip.CRC32
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Программирование MIFARE DESFire (EV1/EV2/EV3) для активации карт АСОП.
 *
 * ОТЛИЧАЕТСЯ от read-only зондов (`DesfireCardReader`, `DesfireAuthProbe`):
 * этот модуль ИЗМЕНЯЕТ карту (ChangeKey / CreateApplication / CreateStdDataFile /
 * WriteData) и используется ТОЛЬКО в flow активации карт (промпт 005).
 *
 * Опкоды (native, Layer 4, IsoDep): AuthenticateISO=0x1A (3K3DES), ChangeKey=0xC4,
 * CreateApplication=0xCA, SelectApplication=0x5A, CreateStdDataFile=0x6D,
 * WriteData=0x8D, ReadData=0xBD, GetMoreFrames=0xAF.
 *
 * ВАЖНО (исправлены ошибки DeepSeek): CreateStdDataFile = 0x6D (не 0xCD),
 * WriteData = 0x8D (не 0x3D), AuthenticateISO = 0x1A (не 0xAA).
 */
class DesfireCardWriter {

    data class WriteResult(
        val ok: Boolean,
        val steps: List<String>,
        val error: String?
    )

    /**
     * Полный flow прошивки карты (после успешной серверной регистрации).
     *
     * @param identityJson UTF-8 canonical JSON cardIdentity (file 0)
     * @param signatureBase64 base64 RSA-PSS-SHA256 (file 1)
     * @param authKey текущий ключ слота 0 (нулевой для новой карты, рабочий при re-registration)
     * @param newKey новейший 3DES-ключ из terminal_keys — им заменяется слот 0
     */
    fun writeIdentity(
        tag: Tag,
        identityProto: ByteArray,
        signatureBase64: String,
        authKey: ByteArray,
        newKey: ByteArray
    ): WriteResult {
        val steps = mutableListOf<String>()
        val iso = IsoDep.get(tag)
        if (iso == null) return WriteResult(false, steps, "IsoDep недоступен (карта не DESFire?)")
        try {
            iso.connect()
            iso.timeout = 5000

            if (!selectApplication(iso, byteArrayOf(0, 0, 0))) {
                return fail(steps, "SelectApplication(мастер PICC) не прошёл")
            }
            steps += "master PICC выбран"
            if (!authenticateAndChangeKey(iso, authKey, newKey)) {
                return fail(steps, "auth+ChangeKey(slot0) не прошёл")
            }
            steps += "authOK + ChangeKey slot0 -> новый 3DES-ключ (encrypted)"

            if (createApplication(iso, ASOP_AID)) {
                steps += "ASOP-приложение создано"
            } else {
                steps += "ASOP-приложение уже существует (создание пропущено)"
            }

            if (!selectApplication(iso, aidBytes(ASOP_AID))) {
                return fail(steps, "SelectApplication(0xA05A01) не прошёл")
            }
            if (!authenticate3k3des(iso, newKey)) {
                return fail(steps, "AuthenticateISO(3K3DES, newKey) в ASOP-приложении не прошёл")
            }
            steps += "authOK ASOP-приложение (newKey)"

            // File 0: proto card identity
            if (createStdDataFile(iso, 0, PROTO_IDENTITY_FILE_SIZE)) {
                steps += "file 0 создан"
            } else {
                steps += "file 0 уже существует (создание пропущено)"
            }
            if (!writeData(iso, 0, identityProto)) {
                return fail(steps, "WriteData(file 0, identity proto) не прошёл")
            }
            steps += "identity proto записан"

            // File 1: signature (base64 of canonical JSON)
            if (createStdDataFile(iso, 1, SIGNATURE_FILE_SIZE)) {
                steps += "file 1 создан"
            } else {
                steps += "file 1 уже существует (создание пропущено)"
            }
            if (!writeData(iso, 1, signatureBase64.toByteArray(Charsets.UTF_8))) {
                return fail(steps, "WriteData(file 1, signature) не прошёл")
            }
            steps += "подпись записана"

            // Верификация чтением
            val readBack = readData(iso, 0)
            if (readBack == null || !readBack.contentEquals(identityProto)) {
                return fail(steps, "верификация identity (file 0) не сошлась")
            }
            steps += "верификация identity OK"
            val readBackSig = readData(iso, 1)
            if (readBackSig == null ||
                !readBackSig.contentEquals(signatureBase64.toByteArray(Charsets.UTF_8))
            ) {
                return fail(steps, "верификация подписи (file 1) не сошлась")
            }
            steps += "верификация подписи OK"

            return WriteResult(true, steps, null)
        } catch (e: IOException) {
            return fail(steps, "IOException: ${e.message}")
        } catch (e: Exception) {
            return fail(steps, "${e.javaClass.simpleName}: ${e.message}")
        } finally {
            runCatching { iso.close() }
        }
    }

    private fun fail(steps: MutableList<String>, msg: String): WriteResult {
        Log.w(TAG, msg)
        return WriteResult(false, steps, msg)
    }

    // ---------- команды (public: используются ViewModel'ом в flow активации) ----------

    /** Результат SelectApplication для диагностики клонов. */
    enum class SelectResult { OK, IO_ERROR, UNSUPPORTED, ERROR_STATUS }

    /**
     * SelectApplication с детальным результатом — для диагностики клонов.
     * Возвращает:
     * - OK — команда прошла (0x00)
     * - IO_ERROR — IOException (Transceive failed), связь оборвалась
     * - UNSUPPORTED — 0x1C (Illegal Command Code), команда не поддерживается
     * - ERROR_STATUS — другой статус (0xAE, 0x7E и т.д.)
     */
    fun selectApplicationDetailed(iso: IsoDep, aid: ByteArray): SelectResult {
        val cmd = ByteArray(4)
        cmd[0] = OP_SELECT_APP.toByte()
        System.arraycopy(aid, 0, cmd, 1, 3)
        var lastError: Exception? = null
        repeat(3) { attempt ->
            try {
                val resp = transceive(iso, cmd)
                if (resp != null && resp.isNotEmpty()) {
                    val status = resp[0].toInt() and 0xFF
                    Log.d(TAG, "selectApp ${cmd.toHex()} -> ${resp.toHex()}")
                    return when (status) {
                        STATUS_OK -> SelectResult.OK
                        0x1C -> SelectResult.UNSUPPORTED
                        else -> SelectResult.ERROR_STATUS
                    }
                }
            } catch (e: IOException) { lastError = e }
            if (attempt < 2) {
                try { Thread.sleep(120) } catch (_: InterruptedException) {}
            }
        }
        Log.w(TAG, "selectApp ${cmd.toHex()} failed after 3 attempts: ${lastError?.message}")
        return SelectResult.IO_ERROR
    }

    /**
     * Проверяет, подходит ли ключ карте через уже открытый IsoDep.
     * Сначала пробует AES нулевым ключом (16 байт), затем 3K3DES нулевым (24 байта),
     * затем переданным ключом.
     */
    fun tryAuthenticateMaster(iso: IsoDep, key: ByteArray): Boolean {
        if (!selectApplication(iso, byteArrayOf(0, 0, 0))) return false
        if (authenticateAes(iso, ByteArray(16))) return true
        if (!selectApplication(iso, byteArrayOf(0, 0, 0))) return false
        if (authenticate3k3des(iso, ByteArray(24))) return true
        if (key.size == 24) {
            if (!selectApplication(iso, byteArrayOf(0, 0, 0))) return false
            if (authenticate3k3des(iso, key)) return true
        }
        return false
    }

    /**
     * Открывает IsoDep и пробует ключи. Фолбэк для случаев, когда Tag ещё свежий.
     */
    fun tryAuthenticateMaster(tag: Tag, key: ByteArray): Boolean {
        val iso = open(tag) ?: return false
        return try {
            tryAuthenticateMaster(iso, key)
        } finally {
            close(iso)
        }
    }

    /**
     * Обновление identity на уже зарегистрированной карте (перерегистрация):
     * карта была аутентифицирована новым ключом в ASOP-приложении, файлы существуют —
     * перезаписываем file 0/file 1 без создания заново.
     */
    fun reflashIdentity(
        iso: IsoDep,
        identityProto: ByteArray,
        signatureBase64: String
    ): Boolean {
        return writeData(iso, 0, identityProto) &&
            writeData(iso, 1, signatureBase64.toByteArray(Charsets.UTF_8))
    }

    /**
     * Полная перерегистрация существующей карты (промпт 005, п.9.2.3 g-i):
     * рабочий ключ найден → в мастер PICC меняем слот 0 на новейший ключ →
     * в ASOP-приложении auth новым ключом → перезаписываем file 0/file 1.
     */
    fun reflashComplete(
        tag: Tag,
        identityProto: ByteArray,
        signatureBase64: String,
        oldKey: ByteArray,
        newKey: ByteArray
    ): WriteResult {
        val steps = mutableListOf<String>()
        val iso = IsoDep.get(tag)
        if (iso == null) return WriteResult(false, steps, "IsoDep недоступен")
        try {
            iso.connect()
            iso.timeout = 5000

            if (!selectApplication(iso, byteArrayOf(0, 0, 0))) {
                return fail(steps, "SelectApplication(мастер PICC) не прошёл")
            }
            if (!authenticateAndChangeKey(iso, oldKey, newKey)) {
                return fail(steps, "auth+ChangeKey(мастер PICC) не прошёл")
            }
            steps += "authOK + ChangeKey master -> newKey (encrypted)"

            if (!selectApplication(iso, aidBytes(ASOP_AID))) {
                return fail(steps, "SelectApplication(0xA05A01) не прошёл")
            }
            if (!authenticate3k3des(iso, newKey)) {
                return fail(steps, "AuthenticateISO(newKey) в ASOP не прошёл")
            }
            steps += "authOK ASOP (newKey)"

            if (!writeData(iso, 0, identityProto)) {
                return fail(steps, "WriteData(file 0) не прошёл")
            }
            if (!writeData(iso, 1, signatureBase64.toByteArray(Charsets.UTF_8))) {
                return fail(steps, "WriteData(file 1) не прошёл")
            }
            steps += "identity proto и подпись перезаписаны"

            return WriteResult(true, steps, null)
        } catch (e: IOException) {
            return fail(steps, "IOException: ${e.message}")
        } catch (e: Exception) {
            return fail(steps, "${e.javaClass.simpleName}: ${e.message}")
        } finally {
            runCatching { iso.close() }
        }
    }

    /** Выбор ASOP-приложения (0x5A) на уже открытом канале. */
    fun selectAsop(iso: IsoDep): Boolean = selectApplication(iso, aidBytes(ASOP_AID))

    /** Auth 3K3DES новым ключом в выбранном (ASOP) приложении. */
    fun authenticateAsop(iso: IsoDep, key: ByteArray): Boolean = authenticate3k3des(iso, key)

    /** Выбор мастер PICC (0x5A 000000) на уже открытом канале. */
    fun selectMaster(iso: IsoDep): Boolean = selectApplication(iso, byteArrayOf(0, 0, 0))

    /** Чтение file из уже аутентифицированного ASOP-приложения. */
    fun readStd(iso: IsoDep, fileNo: Int): ByteArray? = readData(iso, fileNo)

    /**
     * Connect с таймаутом через отдельный поток.
     * На некоторых Samsung IsoDep.connect() на stale-теге блокируется навсегда.
     */
    fun connectIsoDep(iso: IsoDep, timeoutMs: Long): Boolean {
        val t = Thread { runCatching { iso.connect() } }
        t.isDaemon = true
        t.start()
        try { t.join(timeoutMs) } catch (_: InterruptedException) { return false }
        if (t.isAlive) {
            t.interrupt()
            runCatching { iso.close() }
            return false
        }
        return true
    }

    /** Открывает IsoDep-канал (для многошаговых процедур без повторного connect). */
    fun open(tag: Tag): IsoDep? {
        val iso = IsoDep.get(tag) ?: return null
        return try {
            iso.connect()
            iso.timeout = 3000
            iso
        } catch (e: IOException) {
            Log.w(TAG, "open: ${e.message}")
            null
        }
    }

    fun close(iso: IsoDep) {
        runCatching { iso.close() }
    }

    /** SelectApplication (0x5A): 3-байтовый AID, 000000 = мастер PICC. */
    private fun selectApplication(iso: IsoDep, aid: ByteArray): Boolean {
        val cmd = ByteArray(4)
        cmd[0] = OP_SELECT_APP.toByte()
        System.arraycopy(aid, 0, cmd, 1, 3)
        val resp = transceive(iso, cmd)
        return resp != null && resp.size == 1 && (resp[0].toInt() and 0xFF) == STATUS_OK
    }

    /** AuthenticateISO (0x1A, 3K3DES) — полное рукопожатие, ключ 24 байта. */
    private fun authenticate3k3des(iso: IsoDep, key: ByteArray): Boolean {
        // Step1: 1A KeyNo=0 -> AF <8> = E_K(RndB)
        val chResp = transceive(iso, byteArrayOf(OP_AUTH_3KDES.toByte(), 0x00))
            ?: return false
        val challenge = unwrapFrame(chResp) ?: return false
        if (challenge.size != 8) return false
        val rndB = try {
            tripleDesCbcDecrypt(key, ByteArray(8), challenge)
        } catch (e: Exception) {
            Log.w(TAG, "3K3DES step1 decrypt: ${e.message}")
            return false
        }
        val rndA = ByteArray(8).also { SecureRandom().nextBytes(it) }
        val plaintext = ByteArray(16)
        System.arraycopy(rndA, 0, plaintext, 0, 8)
        System.arraycopy(rotateLeft(rndB), 0, plaintext, 8, 8)
        val ciphertext = try {
            tripleDesCbcEncrypt(key, challenge, plaintext)
        } catch (e: Exception) {
            Log.w(TAG, "3K3DES step2 encrypt: ${e.message}")
            return false
        }
        // Step2: AF <E_K(RndA ‖ RotLeft(RndB))>, IV = challenge
        val cmd2 = ByteArray(1 + ciphertext.size)
        cmd2[0] = OP_GET_MORE
        System.arraycopy(ciphertext, 0, cmd2, 1, ciphertext.size)
        val resp2 = transceive(iso, cmd2) ?: return false
        val final = unwrapFrame(resp2) ?: return false
        if (final.size != 8) return false
        val iv2 = ciphertext.copyOfRange(8, 16)
        val dec = try {
            tripleDesCbcDecrypt(key, iv2, final)
        } catch (e: Exception) {
            Log.w(TAG, "3K3DES step3 decrypt: ${e.message}")
            return false
        }
        return dec.contentEquals(rotateLeft(rndA))
    }

    /**
     * 3K3DES handshake + encrypted ChangeKey (0xC4) в одном методе.
     * После успешной auth шифрует новый ключ session key и шлёт C4.
     */
    private fun authenticateAndChangeKey(iso: IsoDep, oldKey: ByteArray, newKey: ByteArray): Boolean {
        val chResp = transceive(iso, byteArrayOf(OP_AUTH_3KDES.toByte(), 0x00)) ?: return false
        val challenge = unwrapFrame(chResp) ?: return false
        if (challenge.size != 8) return false
        val rndB = try {
            tripleDesCbcDecrypt(oldKey, ByteArray(8), challenge)
        } catch (e: Exception) {
            Log.w(TAG, "step1 decrypt: ${e.message}"); return false
        }
        val rndA = ByteArray(8).also { SecureRandom().nextBytes(it) }
        val plaintext = ByteArray(16)
        System.arraycopy(rndA, 0, plaintext, 0, 8)
        System.arraycopy(rotateLeft(rndB), 0, plaintext, 8, 8)
        val ciphertext = try {
            tripleDesCbcEncrypt(oldKey, challenge, plaintext)
        } catch (e: Exception) {
            Log.w(TAG, "step2 encrypt: ${e.message}"); return false
        }
        val cmd2 = ByteArray(1 + ciphertext.size)
        cmd2[0] = OP_GET_MORE
        System.arraycopy(ciphertext, 0, cmd2, 1, ciphertext.size)
        val resp2 = transceive(iso, cmd2) ?: return false
        val finalResp = unwrapFrame(resp2) ?: return false
        if (finalResp.size != 8) return false
        val iv2 = ciphertext.copyOfRange(8, 16)
        val dec = try {
            tripleDesCbcDecrypt(oldKey, iv2, finalResp)
        } catch (e: Exception) {
            Log.w(TAG, "step3 decrypt: ${e.message}"); return false
        }
        if (!dec.contentEquals(rotateLeft(rndA))) return false

        // Session key (3K3DES, 24 bytes):
        // enc(K, IV=0, RndA‖RotL(RndB))[0:16] ‖ RndA[0:8]
        // ВАЖНО: IV=0 (не challenge!), отдельное шифрование от step2.
        val sessionKeyData = try {
            tripleDesCbcEncrypt(oldKey, ByteArray(8), plaintext)
        } catch (e: Exception) {
            Log.w(TAG, "session key encrypt: ${e.message}"); return false
        }
        val sessionKey = ByteArray(24)
        System.arraycopy(sessionKeyData, 0, sessionKey, 0, 16)
        System.arraycopy(rndA, 0, sessionKey, 16, 8)

        // CRC32(newKey) → 4 bytes LE
        val crc = CRC32()
        crc.update(newKey, 0, newKey.size)
        val crcValue = crc.value.toInt()
        val crcBytes = ByteArray(4) { (crcValue shr (it * 8) and 0xFF).toByte() }

        // Plaintext = newKey(24) + CRC32(4) + 0x00(4) = 32 bytes
        val changePlaintext = ByteArray(32)
        System.arraycopy(newKey, 0, changePlaintext, 0, 24)
        System.arraycopy(crcBytes, 0, changePlaintext, 24, 4)

        // Encrypt with session key, IV=0
        val changeCiphertext = try {
            tripleDesCbcEncrypt(sessionKey, ByteArray(8), changePlaintext)
        } catch (e: Exception) {
            Log.w(TAG, "ChangeKey encrypt: ${e.message}"); return false
        }

        // C4 KeyNo <ciphertext> — для 3K3DES→3K3DES НЕТ KeyVersion байта.
        // KeyVersion нужен только при смене на AES. Лишний байт → 0x7E (Length Error).
        val cmd = ByteArray(2 + changeCiphertext.size)
        cmd[0] = OP_CHANGE_KEY.toByte()
        cmd[1] = 0x00  // KeyNo
        System.arraycopy(changeCiphertext, 0, cmd, 2, changeCiphertext.size)
        val resp = transceive(iso, cmd)
        return resp != null && resp.isNotEmpty() && (resp[0].toInt() and 0xFF) == STATUS_OK
    }

    /** AuthenticateAES (0xAA) — полное рукопожатие, ключ 16 байт. */
    private fun authenticateAes(iso: IsoDep, key: ByteArray): Boolean {
        val chResp = transceive(iso, byteArrayOf(0xAA.toByte(), 0x00)) ?: return false
        val challenge = unwrapFrame(chResp) ?: return false
        if (challenge.size != 16) return false
        val rndB = try {
            val c = Cipher.getInstance("AES/ECB/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
            c.doFinal(challenge)
        } catch (e: Exception) { Log.w(TAG, "AES step1 decrypt: ${e.message}"); return false }
        val rndA = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val pt = ByteArray(32)
        System.arraycopy(rndA, 0, pt, 0, 16)
        System.arraycopy(rotateLeft(rndB), 0, pt, 16, 16)
        val ct = try {
            val c = Cipher.getInstance("AES/CBC/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(challenge))
            c.doFinal(pt)
        } catch (e: Exception) { Log.w(TAG, "AES step2 encrypt: ${e.message}"); return false }
        val cmd2 = ByteArray(1 + ct.size)
        cmd2[0] = OP_GET_MORE
        System.arraycopy(ct, 0, cmd2, 1, ct.size)
        val resp2 = transceive(iso, cmd2) ?: return false
        val final = unwrapFrame(resp2) ?: return false
        if (final.size != 16) return false
        val iv2 = ct.copyOfRange(16, 32)
        val dec = try {
            val c = Cipher.getInstance("AES/CBC/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv2))
            c.doFinal(final)
        } catch (e: Exception) { Log.w(TAG, "AES step3 decrypt: ${e.message}"); return false }
        return dec.contentEquals(rotateLeft(rndA))
    }

    /**
     * CreateApplication (0xCA): AID(3) KeySettings(1) NumKeys(1).
     * KeySettings: 0x0F — смена ключей/конфигурация/приложение свободно изменяемо
     * (changeKey=true, configChangeable=true, createDeleteable=true), NumKeys=1.
     */
    private fun createApplication(iso: IsoDep, aid: Int): Boolean {
        val a = aidBytes(aid)
        val cmd = byteArrayOf(
            OP_CREATE_APP.toByte(),
            a[0], a[1], a[2],
            0x0F, // key settings
            0x01  // numKeys
        )
        val resp = transceive(iso, cmd)
        return resp != null && resp.size == 1 && (resp[0].toInt() and 0xFF) == STATUS_OK
    }

    /**
     * CreateStdDataFile (0x6D): FileNo(1) CommsSettings(1) AccessRights(2) FileSize(3).
     * CommsSettings: 0x00 — plain (без MAC/encryption); AccessRights 0x00 0x00 —
     * чтение и запись ключом 0 (auth-required).
     */
    private fun createStdDataFile(iso: IsoDep, fileNo: Int, size: Int): Boolean {
        val cmd = ByteArray(8)
        cmd[0] = OP_CREATE_STD_FILE.toByte()
        cmd[1] = fileNo.toByte()
        cmd[2] = 0x00 // comm settings (plain)
        cmd[3] = 0x00 // access: read key0
        cmd[4] = 0x00 // access: write key0
        cmd[5] = (size ushr 16).toByte()
        cmd[6] = (size ushr 8).toByte()
        cmd[7] = size.toByte()
        val resp = transceive(iso, cmd)
        return resp != null && resp.size == 1 && (resp[0].toInt() and 0xFF) == STATUS_OK
    }

    /** WriteData (0x8D): FileNo(1) Offset(3) + данные. */
    private fun writeData(iso: IsoDep, fileNo: Int, data: ByteArray): Boolean {
        // DESFire WriteData: 8D FileNo <offset 3 байта> <данные>. Для длинных данных
        // шлём блоками по 32 байта (WriteData поддерживает до 32 байт за раз в EV1).
        var offset = 0
        val chunkSize = 32
        while (offset < data.size) {
            val len = minOf(chunkSize, data.size - offset)
            val cmd = ByteArray(1 + 1 + 3 + len)
            cmd[0] = OP_WRITE_DATA.toByte()
            cmd[1] = fileNo.toByte()
            cmd[2] = (offset ushr 16).toByte()
            cmd[3] = (offset ushr 8).toByte()
            cmd[4] = offset.toByte()
            System.arraycopy(data, offset, cmd, 5, len)
            val resp = transceive(iso, cmd)
            if (resp == null || resp.isEmpty() || (resp[0].toInt() and 0xFF) != STATUS_OK) {
                Log.w(TAG, "WriteData(file $fileNo, offset $offset) -> ${resp?.toHex() ?: "нет ответа"}")
                return false
            }
            offset += len
        }
        return true
    }

    /** ReadData (0xBD): FileNo(1) Offset(3) Length(3). */
    private fun readData(iso: IsoDep, fileNo: Int): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        var offset = 0
        val chunkSize = 32
        while (out.size() < FILE_READ_LIMIT) {
            val cmd = ByteArray(8)
            cmd[0] = OP_READ_DATA.toByte()
            cmd[1] = fileNo.toByte()
            cmd[2] = (offset ushr 16).toByte()
            cmd[3] = (offset ushr 8).toByte()
            cmd[4] = offset.toByte()
            cmd[5] = 0x00
            cmd[6] = 0x00
            cmd[7] = chunkSize.toByte()
            val resp = transceive(iso, cmd)
            if (resp == null || resp.isEmpty()) return null
            val status = resp[0].toInt() and 0xFF
            if (status == 0x00) {
                out.write(resp, 1, resp.size - 1)
                if (resp.size - 1 < chunkSize) break
                offset += resp.size - 1
            } else if (status == 0xAF) {
                out.write(resp, 1, resp.size - 1)
                offset += resp.size - 1
            } else if (status == 0xCE || status == 0x1C) {
                // OutOfBoundary / FileNotFound — конец данных
                break
            } else {
                return null
            }
        }
        return out.toByteArray()
    }

    /** Шлёт native-команду, возвращает полный ответ (статус-байты включены). */
    private fun transceive(iso: IsoDep, cmd: ByteArray): ByteArray? = try {
        val resp = iso.transceive(cmd)
        Log.d(TAG, "${cmd.toHex()} -> ${resp.toHex()}")
        resp
    } catch (e: IOException) {
        Log.w(TAG, "${cmd.toHex()} fail: ${e.message}")
        null
    }

    /**
     * transceive с аппаратным таймаутом через отдельный поток.
     * Используется в writeIdentity/reflashComplete где retry невозможен.
     * При таймауте НЕ закрывает IsoDep (чтобы не сломать retry-циклы в selectApplicationDetailed).
     */
    private fun transceiveSafe(iso: IsoDep, cmd: ByteArray): ByteArray? {
        val result = arrayOfNulls<ByteArray?>(1)
        val error = arrayOfNulls<Exception?>(1)
        val thread = Thread {
            try { result[0] = iso.transceive(cmd) } catch (e: Exception) { error[0] = e }
        }
        thread.isDaemon = true
        thread.start()
        try { thread.join(7000) } catch (_: InterruptedException) { return null }
        if (thread.isAlive) {
            thread.interrupt()
            Log.w(TAG, "${cmd.toHex()} timeout — thread blocked")
            return null
        }
        if (error[0] != null) {
            Log.w(TAG, "${cmd.toHex()} fail: ${error[0]?.message}")
            return null
        }
        val resp = result[0]
        if (resp != null) Log.d(TAG, "${cmd.toHex()} -> ${resp.toHex()}")
        return resp
    }

    /** Снимает кадр ответа: 00/0xAF <data> -> data (без досбора GetMoreFrames). */
    private fun unwrapFrame(resp: ByteArray): ByteArray? {
        if (resp.isEmpty()) return null
        return when (resp[0].toInt() and 0xFF) {
            STATUS_OK.toInt() and 0xFF, OP_GET_MORE.toInt() and 0xFF -> resp.copyOfRange(1, resp.size)
            else -> null
        }
    }

    // ---------- криптография ----------

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

    private fun aidBytes(aid: Int): ByteArray = byteArrayOf(
        ((aid ushr 16) and 0xFF).toByte(),
        ((aid ushr 8) and 0xFF).toByte(),
        (aid and 0xFF).toByte()
    )

    companion object {
        private const val TAG = "DesfireCardWriter"
        const val ASOP_AID = 0xA05A01
        const val PROTO_IDENTITY_FILE_SIZE = 1024
        const val SIGNATURE_FILE_SIZE = 1024
        const val FILE_READ_LIMIT = 4096

        private const val OP_AUTH_3KDES = 0x1A
        private const val OP_CHANGE_KEY = 0xC4
        private const val OP_CREATE_APP = 0xCA
        private const val OP_SELECT_APP = 0x5A
        private const val OP_CREATE_STD_FILE = 0x6D
        private const val OP_WRITE_DATA = 0x8D
        private const val OP_READ_DATA = 0xBD
        private const val OP_GET_MORE = 0xAF.toByte()
        private const val STATUS_OK = 0x00
    }
}

private fun ByteArray.toHex(): String = joinToString(" ") { String.format("%02X", it) }