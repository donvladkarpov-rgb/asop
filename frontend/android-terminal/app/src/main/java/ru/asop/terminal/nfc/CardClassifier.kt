package ru.asop.terminal.nfc

import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.nfc.tech.MifareClassic
import android.util.Log

/**
 * Промпт 016 §3.5: классификация приложенной карты ДО любого чтения identity.
 *
 * Терминал в рейсе арм'ит reader на обе технологии (MifareClassic + IsoDep).
 * Банковская карта МИР — тоже IsoDep (ISO 14443-4), поэтому одного `IsoDep != null`
 * недостаточно: пробуем выбрать ASOP-приложение (native DESFire SelectApplication 0x5A).
 *
 * Пробы строго read-only, никаких ChangeKey/CreateApplication/WriteData:
 *  - MIFARE Classic   — MifareClassic.get(tag) != null (мгновенно, без transceive);
 *  - ASOP DESFire     — IsoDep + SelectApplication(0xA05A01) → 0x00;
 *  - BANK_EMV         — IsoDep + SelectApplication(0xA05A01) → не 0x00 (0x9D/0x1C/др.);
 *  - UNSUPPORTED      — нет ни MifareClassic, ни IsoDep, либо проба оборвалась.
 *
 * Классификатор НИКОГДА не бросает и всегда закрывает IsoDep в finally — освобождает
 * карту, чтобы её можно было передать в app-payment (handoff) без остаточного канала.
 */
enum class TapCardKind { MIFARE_CLASSIC, ASOP_DESFIRE, BANK_EMV, UNSUPPORTED }

object CardClassifier {

    private const val TAG = "CardClassifier"

    private val ASOP_AID = byteArrayOf(0xA0.toByte(), 0x5A, 0x01)

    fun classify(tag: Tag): TapCardKind {
        return try {
            if (MifareClassic.get(tag) != null) {
                return TapCardKind.MIFARE_CLASSIC
            }
            val iso = IsoDep.get(tag) ?: return TapCardKind.UNSUPPORTED
            classifyIsoDep(iso)
        } catch (e: Exception) {
            Log.w(TAG, "classify failed: ${e.javaClass.simpleName} ${e.message}")
            TapCardKind.UNSUPPORTED
        }
    }

    private fun classifyIsoDep(iso: IsoDep): TapCardKind {
        try {
            iso.connect()
            iso.timeout = 1500
            val probe = DesfireCardWriter().selectApplicationDetailed(iso, ASOP_AID)
            Log.i(TAG, "IsoDep AID 0xA05A01 select → $probe")
            return when (probe) {
                DesfireCardWriter.SelectResult.OK -> TapCardKind.ASOP_DESFIRE
                DesfireCardWriter.SelectResult.UNSUPPORTED,
                DesfireCardWriter.SelectResult.ERROR_STATUS -> TapCardKind.BANK_EMV
                DesfireCardWriter.SelectResult.IO_ERROR -> TapCardKind.UNSUPPORTED
            }
        } catch (e: Exception) {
            Log.w(TAG, "IsoDep classify failed: ${e.javaClass.simpleName} ${e.message}")
            return TapCardKind.UNSUPPORTED
        } finally {
            runCatching { if (iso.isConnected) iso.close() }
        }
    }
}
