package ru.asop.test

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Сравнение Room-данных терминала (reference_rows через ContentProvider)
 * с ethalon JSON (SQL-генерация из Postgres).
 *
 * Сопоставление строк — по полю VERSION (глобальный sequence, уникален).
 * Канонизация обеих сторон, чтобы нивелировать различия JsonFormat vs SQL:
 * - ключи → snake_case;
 * - исключить created_at/updated_at;
 * - deleted_at — только признак "есть/нет";
 * - числа и числовые строки → BigDecimal (2.50 == 2.5);
 * - даты/таймстампы (в т.ч. pg-формат 'YYYY-MM-DD HH:MM:SS+ZZ') → epoch millis;
 * - proto3 default (null/пустая строка/false/0) отбрасывается на обеих сторонах.
 */
class EthalonChecker(private val resolver: ContentResolver) {

    companion object {
        private const val AUTHORITY = "ru.asop.terminal.provider"
        private const val URI = "content://$AUTHORITY/reference_rows"

        private val FMT_OFFSET = DateTimeFormatter.ISO_OFFSET_DATE_TIME
        private val FMT_INSTANT = DateTimeFormatter.ISO_INSTANT
        private val FMT_LOCAL_DT = DateTimeFormatter.ISO_LOCAL_DATE_TIME
        private val FMT_LOCAL_DATE = DateTimeFormatter.ISO_LOCAL_DATE
    }

    data class RowDiff(
        val table: String,
        val version: Long,
        val room: String,
        val ethalon: String
    )

    data class TableResult(
        val table: String,
        val roomCount: Int,
        val ethalonCount: Int,
        val missingInRoom: List<Long>,
        val extraInRoom: List<Long>,
        val diffs: List<RowDiff>
    ) {
        val ok: Boolean
            get() = missingInRoom.isEmpty() && extraInRoom.isEmpty() && diffs.isEmpty()
    }

    data class Result(
        val assetName: String,
        val tables: List<TableResult>,
        val error: String? = null
    ) {
        val ok: Boolean get() = error == null && tables.all { it.ok }
    }

    /** Проверяет ethalon-файл из assets против reference_rows. */
    fun check(context: Context, assetName: String): Result {
        val text = try {
            context.assets.open(assetName).bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            return Result(assetName, emptyList(), "Не удалось прочитать $assetName: ${e.message}")
        }
        val root = try {
            JSONObject(text)
        } catch (e: Exception) {
            return Result(assetName, emptyList(), "Некорректный JSON в $assetName: ${e.message}")
        }

        val ethalonByTable = mutableMapOf<String, Map<Long, String>>()
        root.keys().forEach { table ->
            val arr = root.optJSONArray(table) ?: return@forEach
            val m = mutableMapOf<Long, String>()
            for (i in 0 until arr.length()) {
                val row = arr.getJSONObject(i)
                val version = row.optLong("version", -1L)
                if (version < 0) continue
                m[version] = canonicalize(row)
            }
            ethalonByTable[table] = m
        }

        val roomByTable = readRoom()

        val tables = ethalonByTable.keys.sorted().map { table ->
            val ethalon = ethalonByTable[table] ?: emptyMap()
            val room = roomByTable[table] ?: emptyMap()
            val missingInRoom = ethalon.keys.filter { it !in room }.sorted()
            val extraInRoom = room.keys.filter { it !in ethalon }.sorted()
            val diffs = ethalon.keys.filter { it in room && room[it] != ethalon[it] }.map { v ->
                RowDiff(table, v, room[v] ?: "", ethalon[v] ?: "")
            }
            TableResult(table, room.size, ethalon.size, missingInRoom, extraInRoom, diffs)
        }

        return Result(assetName, tables)
    }

    private fun readRoom(): Map<String, Map<Long, String>> {
        val cursor = resolver.query(Uri.parse(URI), null, null, null, null)
            ?: return emptyMap()
        return cursor.use { c ->
            val colTable = c.getColumnIndexOrThrow("table_name")
            val colPayload = c.getColumnIndexOrThrow("payload_json")
            val colDeleted = c.getColumnIndexOrThrow("deleted_at")
            val colVersion = c.getColumnIndexOrThrow("version")
            val map = mutableMapOf<String, MutableMap<Long, String>>()
            while (c.moveToNext()) {
                val table = c.getString(colTable)
                val payload = c.getString(colPayload)
                val deletedAt = if (c.isNull(colDeleted)) null else c.getString(colDeleted)
                val version = if (c.isNull(colVersion)) null else c.getLong(colVersion)
                if (version == null) continue
                map.getOrPut(table) { mutableMapOf() }[version] =
                    canonicalizeRoom(payload, deletedAt)
            }
            map
        }
    }

    /** Канонизация строки из reference_rows (payload_json = JsonFormat, camelCase). */
    private fun canonicalizeRoom(payloadJson: String, deletedAt: String?): String {
        val json = try {
            JSONObject(payloadJson)
        } catch (e: Exception) {
            return "err:bad-json"
        }
        val parts = mutableListOf<String>()
        json.keys().forEach { k ->
            val snake = camelToSnake(k)
            if (snake == "created_at" || snake == "updated_at") return@forEach
            val v = json.opt(k)
            val canonical = canonicalValue(v)
            if (canonical != null) parts += "$snake=$canonical"
        }
        parts += "deleted=${if (deletedAt.isNullOrBlank()) "0" else "1"}"
        return parts.sorted().joinToString("&")
    }

    /** Канонизация строки из ethalon SQL (snake_case). */
    private fun canonicalize(ethalonRow: JSONObject): String {
        val parts = mutableListOf<String>()
        ethalonRow.keys().forEach { k ->
            if (k == "created_at" || k == "updated_at") return@forEach
            val v = ethalonRow.opt(k)
            if (k == "deleted_at") {
                val isDeleted = when (v) {
                    null, JSONObject.NULL -> false
                    is Boolean -> v
                    else -> true
                }
                parts += "deleted=${if (isDeleted) "1" else "0"}"
                return@forEach
            }
            val canonical = canonicalValue(v)
            if (canonical != null) parts += "$k=$canonical"
        }
        return parts.sorted().joinToString("&")
    }

    /** Значение → каноническая строка. null/пусто/false/0 → null (proto3 default omission). */
    private fun canonicalValue(v: Any?): String? {
        return when (v) {
            null, JSONObject.NULL -> null
            is Boolean -> if (v) "1" else null
            is Int -> if (v == 0) null else v.toString()
            is Long -> if (v == 0L) null else v.toString()
            is Double -> if (v == 0.0) null else canonicalNumber(v.toString())
            is BigDecimal -> canonicalNumber(v.toPlainString())
            is JSONObject -> null
            is JSONArray -> null
            is String -> canonicalString(v)
            else -> null
        }
    }

    private fun canonicalString(s: String): String {
        if (s.isBlank()) return ""
        val ts = parseEpochMillis(s)
        if (ts != null) return "ts:$ts"
        val num = s.toBigDecimalOrNull()
        if (num != null) return canonicalNumber(num.toPlainString())
        return "s:$s"
    }

    private fun canonicalNumber(s: String): String {
        return BigDecimal(s).stripTrailingZeros().toPlainString()
    }

    /** Парсит дату/таймстамп (ISO-8601 или pg-формат) в epoch millis. */
    private fun parseEpochMillis(s: String): Long? {
        if (s.length < 10) return null
        // pg-формат: '2024-01-01 00:00:00+03' → ISO с 'T' и offset ':00'
        var candidate = s
        if (candidate[4] == '-' && candidate[10] == ' ') {
            candidate = candidate.substring(0, 10) + "T" + candidate.substring(11)
        }
        if (Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2})?[+-]\d{2}$""").matches(candidate)) {
            candidate += ":00"
        }
        return try {
            when {
                candidate.endsWith("Z") || Regex("""[+-]\d{2}:\d{2}$""").matches(candidate) ->
                    Instant.from(FMT_OFFSET.parse(candidate)).toEpochMilli()
                candidate.contains("T") ->
                    LocalDateTime.parse(candidate, FMT_LOCAL_DT).toInstant(ZoneOffset.UTC).toEpochMilli()
                else ->
                    LocalDate.parse(candidate, FMT_LOCAL_DATE).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun camelToSnake(s: String): String {
        return s.replace(Regex("([a-z0-9])([A-Z])"), "$1_$2").lowercase()
    }
}
