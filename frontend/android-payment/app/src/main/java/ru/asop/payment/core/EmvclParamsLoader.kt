package ru.asop.payment.core

import android.content.Context
import android.util.Log
import com.ft.possystemapi.server.ServiceManager as SysServiceManager
import com.ft.possystemapi.server.file.IEMVFileManager
import com.ftpos.library.smartpos.bean.CMastercardBean
import com.ftpos.library.smartpos.bean.CRupayBean
import com.ftpos.library.smartpos.bean.CVisaBean
import com.ftpos.library.smartpos.bean.DefaultParamsBean
import com.ftpos.library.smartpos.emv.Emv
import com.ftpos.library.smartpos.emv.IActionFlag
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Загрузчик бесконтактных EMV-параметров в ядро FTSDK + диаг. ФАЙЛОВОГО менеджера.
 *
 * МИР на F20 обслуживается ядром RUPAY (`RupayICS=07582F`), ядра MIR нет. Параллельно
 * пробуем зарегистрировать Mastercard(02) и Visa(03), чтобы понять: `rc=5` на ADD —
 * особенность MIR/Rupay или универсальный отказ (гейт/файловый путь).
 */
object EmvclParamsLoader {

    private const val TAG = "EmvclParamsLoader"

    @Volatile
    var kernelIdOverride: String? = null

    private const val AID_MIR = "A0000006581010"
    private const val AID_MC = "A0000000041010"
    private const val AID_VISA = "A0000000031010"
    private const val RUPAY_KERNEL_ID = "0D"

    fun load(context: Context, emv: Emv): String {
        val kernel = (kernelIdOverride ?: RUPAY_KERNEL_ID).trim()

        // Отладочный режим ядра (аккредитационный флаг Feitian) — пробуем включить.
        val dbg = runCatching { emv.switchDebug(1) }.getOrElse { e -> "err:${e.javaClass.simpleName}" }.toString()

        val clearRc = runCatching {
            emv.manageEmvclAppParameters(IActionFlag.CLEAR, null)
        }.getOrElse { e -> return "err=clear:${e.javaClass.simpleName}:${e.message}" }

        val defTlv = runCatching { buildDefaultParams().toTlvByteArray() }.getOrNull()
        val defRc = if (defTlv == null) "buildFailed" else runCatching {
            emv.setDefaultAppParameters(defTlv)
        }.getOrElse { e -> "err=${e.javaClass.simpleName}:${e.message}" }.toString()

        val sb = StringBuilder("[debug=$dbg][clear=$clearRc][def=$defRc]")

        // МИР → Rupay kernel (перезаписываемый KernelID)
        sb.append(addScheme(emv, "mir", AID_MIR, buildRupayBean(kernel)))
        // Mastercard PayPass → kernel 02
        sb.append(addScheme(emv, "mc", AID_MC, buildMastercardBean()))
        // Visa payWave → kernel 03
        sb.append(addScheme(emv, "visa", AID_VISA, buildVisaBean()))

        return sb.toString()
    }

    private fun addScheme(emv: Emv, name: String, aid: String, bean: Any): String {
        val tlv = runCatching { serializeToTlv(bean) }.getOrElse { e ->
            return "[$name err=build:${e.javaClass.simpleName}]"
        } ?: return "[$name err=null-tlv]"
        val rc = runCatching { emv.manageEmvclAppParameters(IActionFlag.ADD, tlv) }
            .getOrElse { e -> return "[$name err=add:${e.javaClass.simpleName}]" }
        Log.i(TAG, "$name aid=$aid rc=$rc tlvLen=${tlv.size} tlv=${tlv.joinToString("") { "%02X".format(it) }}")
        return "[$name aid=$aid rc=$rc tlvLen=${tlv.size}]"
    }

    private fun serializeToTlv(bean: Any): ByteArray? = when (bean) {
        is CRupayBean -> bean.toTlvByteArray()
        is CMastercardBean -> bean.toTlvByteArray()
        is CVisaBean -> bean.toTlvByteArray()
        else -> null
    }

    private fun buildDefaultParams(): DefaultParamsBean {
        val d = DefaultParamsBean()
        d.initDefaultParams("9F35", "22")
        d.initDefaultParams("9F1B", "00000000")
        d.initDefaultParams("9F33", "E0B8C8")
        d.initDefaultParams("9F40", "FF80F0A001")
        d.initDefaultParams("1F04", "DC4004F800")
        d.initDefaultParams("1F05", "0000000000")
        d.initDefaultParams("1F06", "FC50ACA000")
        d.initDefaultParams("9F01", "000000000001")
        d.initDefaultParams("9F15", "4111")
        d.initDefaultParams("9F16", "000000000000001")
        d.initDefaultParams("9F1C", "00000001")
        d.initDefaultParams("9F1A", "0643")
        return d
    }

    private fun buildRupayBean(kernel: String): CRupayBean {
        val r = CRupayBean()
        r.initCRupayBean("9F06", AID_MIR)
        r.initCRupayBean("1F60", kernel)
        r.initCRupayBean("9F33", "E0B8C8")
        r.initCRupayBean("9F40", "FF80F0A001")
        r.initCRupayBean("9F35", "22")
        r.initCRupayBean("9F1B", "00000000")
        r.initCRupayBean("1F04", "DC4004F800")
        r.initCRupayBean("1F05", "0000000000")
        r.initCRupayBean("1F06", "FC50ACA000")
        r.initCRupayBean("9F1A", "0643")
        r.initCRupayBean("5F2A", "0643")
        r.initCRupayBean("5F36", "02")
        r.initCRupayBean("9F01", "000000000001")
        r.setTransTypeGroup_1F62("00,01")
        return r
    }

    private fun buildMastercardBean(): CMastercardBean {
        val m = CMastercardBean()
        m.initCMastercardBean("9F06", AID_MC)
        m.initCMastercardBean("1F60", "02")
        m.initCMastercardBean("5F2A", "0643")
        m.initCMastercardBean("5F36", "02")
        m.initCMastercardBean("9F1A", "0643")
        m.initCMastercardBean("9F01", "000000000001")
        m.initCMastercardBean("9F35", "22")
        m.initCMastercardBean("9F33", "E0B8C8")
        // PayPass TAC (DF8120/21/22)
        m.initCMastercardBean("DF8120", "DC4004F800")
        m.initCMastercardBean("DF8121", "0000000000")
        m.initCMastercardBean("DF8122", "FC50ACA000")
        return m
    }

    private fun buildVisaBean(): CVisaBean {
        val v = CVisaBean()
        v.initCVisaBean("9F06", AID_VISA)
        v.initCVisaBean("1F60", "03")
        v.initCVisaBean("5F2A", "0643")
        v.initCVisaBean("5F36", "02")
        v.initCVisaBean("9F1A", "0643")
        v.initCVisaBean("9F01", "000000000001")
        v.initCVisaBean("9F35", "22")
        v.initCVisaBean("9F66", "B6204000") // Terminal Transaction Qualifiers (payWave)
        return v
    }

    // ---------- Файловый менеджер EMV (SysAPI) ----------

    /** Диагностика: список файлов параметров EMV на устройстве (SysAPI IEMVFileManager). */
    fun probeEmvFiles(context: Context): String {
        val fm = sysEmvFileManager(context)
            ?: return """{"error":"IEMVFileManager недоступен (bind SysAPI?)"}"""

        val list = runCatching { fm.readEMVParamFileList("*") }
            .getOrElse { e -> """{"listError":"${e.javaClass.simpleName}:${e.message}"}""" }

        val sb = StringBuilder()
        sb.append("""{"fileList":""").append(if (list is String) list else list)
        if (list is Array<*>) {
            sb.append(""","files":[""")
            sb.append(list.joinToString(",") { org.json.JSONObject.quote(it?.toString()) })
            sb.append("]")
        }
        sb.append("}")
        return sb.toString()
    }

    private fun sysEmvFileManager(context: Context): IEMVFileManager? = runCatching {
        val latch = CountDownLatch(1)
        val failCode = AtomicInteger(-1)
        val cb = object : SysServiceManager.ServiceConnectCallback {
            override fun onSuccess() { latch.countDown() }
            override fun onFail(code: Int) { failCode.set(code); latch.countDown() }
        }
        SysServiceManager.getInstance(context)
        SysServiceManager.bindPosSysServerSync(context, cb)
        if (!latch.await(15, TimeUnit.SECONDS) || failCode.get() != -1) return null
        val binder = SysServiceManager.getEMVFileManager() ?: return null
        IEMVFileManager.Stub.asInterface(binder)
    }.getOrElse { e ->
        Log.e(TAG, "sysEmvFileManager bind failed: ${e.message}")
        null
    }
}
