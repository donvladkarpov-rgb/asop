package ru.asop.terminal.nfc

import android.nfc.Tag
import ru.asop.terminal.activation.CardIdentityVcm1

/**
 * Промпт 011/013: единый читатель ASOP-карт для всех flow (open shift / activate card / read probe).
 *
 * Реализация идентична процедуре "Прочитать карту" (CardReadViewModel):
 *   1. Тех-тег через MifareClassicReader.isMifareClassic()
 *   2. ASOP-keys из terminal_keys (фактические + factory-FF / 00 / A0 / D3F7)
 *   3. MifareClassicReader.read() → ReadResult с classicInfo.vcm1Identity (если VCM1-magic найден)
 *   4. Иначе — Result.error содержит причину (NOT_MIFARE / AUTH_FAIL / READ_FAILED)
 *
 * QuickVcm1Reader устранён (был менее проработан: единичный try-catch, плохая обработка
 * TagLostException — для clone-карт которые теряют CRYPTO1-сессию, она сыпалась "Исключение при чтении: null").
 *
 * Возвращает Vcm1AuthOutcome — общий тип для всех UM-флоу.
 */
object Vcm1CardAuth {

    sealed interface Outcome {
        data class Ok(
            val uidHex: String,
            val rawVcm1Bytes: ByteArray,
            val identity: CardIdentityVcm1
        ) : Outcome

        data class Failed(
            val uidHex: String,
            val status: Status,
            val details: String
        ) : Outcome

        enum class Status {
            NOT_MIFARE_CLASSIC,
            AUTH_FAILED,
            READ_FAILED,
            NOT_VCM1,           // прочитана mifare classic, но sector 1 не содержит VCM1-magic
        }
    }

    /**
     * Прочитать ASOP card-карту и вернуть (uid, vcm1Bytes, identity).
     * @return [Outcome.Ok] если VCM1-magic найден и карта активирована;
     *         [Outcome.Failed] иначе с description причины.
     */
    fun read(tag: Tag, asopKeyMaterial: List<ByteArray>): Outcome {
        val uidHex = tag.id.joinToString("") { "%02X".format(0xFF and it.toInt()) }
        if (!MifareClassicReader.isMifareClassic(tag)) {
            return Outcome.Failed(
                uidHex = uidHex,
                status = Outcome.Status.NOT_MIFARE_CLASSIC,
                details = "Карта не MifareClassic (теги: ${tag.techList.joinToString(",")}). " +
                    "Используйте MIFARE Classic 1K/4K. Либо DESFire ASOP — используйте «Активация карт»."
            )
        }

        val retryResult = readWithRetry(tag, asopKeyMaterial)
        if (retryResult.failureException != null) {
            // После retries всё равно IOException / TagLostException — обычно это
            // ситуация когда tag ушёл с NFC reader во время connect() (Feitian PICC
            // переключается между режимами, ASOP видит tag только ~250мс в одном проходе).
            return Outcome.Failed(
                uidHex = uidHex,
                status = Outcome.Status.READ_FAILED,
                details = "Исключение при чтении: ${retryResult.failureException.javaClass.simpleName} " +
                    "${retryResult.failureException.message ?: "(без сообщения)"}. " +
                    "Тапните карту ещё раз (Feitian PICC держит tag и поле ~250мс)."
            )
        }

        val readResult = retryResult.value!!

        if (readResult.error != null && readResult.classicInfo == null) {
            // Auth failed полностью (никто из ключей не подошёл) — обычно
            // это несохранённые ASOP-ключи или карта без активации.
            return Outcome.Failed(
                uidHex = uidHex,
                status = Outcome.Status.AUTH_FAILED,
                details = "${readResult.error}. " +
                    "Проверьте: (1) карта активирована через «Активация карт» в меню; " +
                    "(2) 'Загрузить справочники' → 'Полная выкачка' синкнула ASOP-ключи."
            )
        }

        val vcm1 = readResult.classicInfo?.vcm1Identity
        if (vcm1 != null) {
            // Сконструировать raw 48 байт (block0[16] + block1[16] + block2[16]) для downstream
            // потребителей (onCardTappedForAuth дешифрует из CardIdentityVcm1.decodeFromBytes).
            val raw = ByteArray(48).also { v ->
                val block0Hex = readResult.classicInfo!!.allBlocks[1]?.getOrNull(0)
                val block1Hex = readResult.classicInfo.allBlocks[1]?.getOrNull(1)
                val block2Hex = readResult.classicInfo.allBlocks[1]?.getOrNull(2)
                if (block0Hex != null) System.arraycopy(hexToBytes(block0Hex), 0, v, 0, 16)
                if (block1Hex != null) System.arraycopy(hexToBytes(block1Hex), 0, v, 16, 16)
                if (block2Hex != null) System.arraycopy(hexToBytes(block2Hex), 0, v, 32, 16)
            }
            return Outcome.Ok(uidHex = uidHex, rawVcm1Bytes = raw, identity = vcm1)
        }

        // Auth-OK (или частичный) но sector 1 не VCM1-формата
        val block0Hex = readResult.classicInfo?.allBlocks?.get(1)?.getOrNull(0)
        val ascii = block0Hex?.let { hex ->
            hexToBytes(hex).take(4).joinToString("") {
                if (it in 0x20.toByte()..0x7E.toByte()) it.toInt().toChar().toString() else "?"
            }
        } ?: "(нет данных)"

        return Outcome.Failed(
            uidHex = uidHex,
            status = Outcome.Status.NOT_VCM1,
            details = "Карта не VCM1-формата. Sector 1 block 0 magic='$ascii' " +
                "(ожидалось 'VCM1'). Карта активирована через «Активация карт» в меню?"
        )
    }

    private fun hexToBytes(hex: String): ByteArray {
        if (hex.isEmpty()) return ByteArray(0)
        val clean = hex.replace(" ", "").replace("\n", "")
        return ByteArray(clean.length / 2) { i ->
            val v = clean.substring(i * 2, i * 2 + 2).toInt(16)
            v.toByte()
        }
    }

    /**
     * Retry-обёртка для [MifareClassicReader.read]: на Feitian F20 PICC иногда
     * отзывает tag пока `mfc.connect()` ещё не успел проинициализировать ISO14443
     * сессию (типичная ситуация: Foreground dispatch + ReaderMode race в момент
     * tap, после enableForegroundDispatch tag виден ~250мс).
     *
     * Делает до 2 retry с 80мс задержкой; если последний attempt всё равно упал в
     * IOException / TagLostException, возвращает failureException — caller решает
     * какой mapping в Outcome.Failed сделать.
     */
    private data class RetryResult(
        val value: DesfireCardReader.ReadResult?,
        val failureException: Throwable?
    )

    private fun readWithRetry(
        tag: Tag,
        asopKeyMaterial: List<ByteArray>
    ): RetryResult {
        var lastException: Throwable? = null
        repeat(2) { attempt ->
            try {
                val v = MifareClassicReader.read(tag, asopKeyMaterial)
                return RetryResult(value = v, failureException = null)
            } catch (e: Exception) {
                lastException = e
                android.util.Log.w(
                    "Vcm1CardAuth",
                    "read attempt #$attempt failed: ${e.javaClass.simpleName} ${e.message ?: "(без сообщения)"}"
                )
                try {
                    kotlinx.coroutines.runBlocking {
                        kotlinx.coroutines.delay(80)
                    }
                } catch (_: Exception) { /* main-thread fallback: Thread.sleep */ }
            }
        }
        android.util.Log.e("Vcm1CardAuth", "readWithRetry gave up after 2 attempts: ${lastException?.message}")
        return RetryResult(value = null, failureException = lastException)
    }
}
