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
 * Сопоставление строк — по содержимому (мультимножество канонизированных строк):
 * version как ключ использовать нельзя — глобальный sequence переиндексируется
 * при каждом прогоне seed. Канонизация обеих сторон, чтобы нивелировать различия
 * JsonFormat vs SQL:
 * - ключи → snake_case;
 * - исключить version/sync_version/аудит-поля и нестабильные NOW()-поля сида;
 * - deleted_at — только признак "есть/нет";
 * - числа и числовые строки → BigDecimal (2.50 == 2.5);
 * - даты/таймстампы (в т.ч. pg-формат 'YYYY-MM-DD HH:MM:SS+ZZ') → epoch millis;
 * - proto3 default (null/пустая строка/false/0) отбрасывается на обеих сторонах.
 *
 * Изменённая строка выглядит как одна missing + одна extra; fieldLevelDiffs
 * спаривает такие пары по совпадению атрибутов и показывает, какие поля разошлись.
 */
class EthalonChecker(private val resolver: ContentResolver) {

    companion object {
        private const val AUTHORITY = "ru.asop.terminal.provider"
        private const val URI = "content://$AUTHORITY/reference_rows"

        private const val MAX_DIFF_FIELDS = 12

        private val FMT_OFFSET = DateTimeFormatter.ISO_OFFSET_DATE_TIME
        private val FMT_INSTANT = DateTimeFormatter.ISO_INSTANT
        private val FMT_LOCAL_DT = DateTimeFormatter.ISO_LOCAL_DATE_TIME
        private val FMT_LOCAL_DATE = DateTimeFormatter.ISO_LOCAL_DATE

        /**
         * Технические поля без бизнес-смысла: version — глобальный sequence
         * (переиндексируется при каждом прогоне seed), sync_version — внутренний,
         * created_at/updated_at/created_by/updated_by — служебные аудит-поля.
         * Не участвуют в сверке ни в одной таблице.
         */
        private val IGNORED_FIELDS = setOf(
            "version", "sync_version", "created_at", "updated_at", "created_by", "updated_by"
        )

        /**
         * Поля, которые сид заполняет NOW()/CURRENT_TIMESTAMP — нестабильны между
         * перегенерацией ethalon и синком терминала, поэтому сверке не подлежат.
         * Прочие даты в сидах — стабильные литералы ('2026-12-31', '2024-01-01 00:00:00+03')
         * и продолжают сверяться (card_tariffs.expiration_date,
         * path_discounts.valid_from/valid_until, card_mifares.valid_from/valid_until,
         * contracts.start_date/end_date).
         */
        private val UNSTABLE_FIELDS_BY_TABLE = mapOf(
            "asop_cards" to setOf("registered_at"),
            "asop_blacklists" to setOf("blocked_at"),
            "asop_user_benefits" to setOf("valid_from", "valid_until")
        )
    }

    data class RowDiff(
        val table: String,
        val room: String,
        val ethalon: String
    )

    data class TableResult(
        val table: String,
        val roomCount: Int,
        val ethalonCount: Int,
        val missingInRoomCount: Int,
        val extraInRoomCount: Int,
        val diffs: List<RowDiff>
    ) {
        val ok: Boolean
            get() = missingInRoomCount == 0 && extraInRoomCount == 0 && diffs.isEmpty()
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

        val ethalonByTable = mutableMapOf<String, MutableList<String>>()
        root.keys().forEach { table ->
            val arr = root.optJSONArray(table) ?: return@forEach
            val list = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                val row = arr.getJSONObject(i)
                list += canonicalize(table, row)
            }
            ethalonByTable[table] = list
        }

        val roomByTable = readRoom()

        val tables = ethalonByTable.keys.sorted().map { table ->
            val ethalon = ethalonByTable[table] ?: emptyList()
            val room = roomByTable[table] ?: emptyList()
            val missingInRoom = ethalon.subtract(room)
            val extraInRoom = room.subtract(ethalon)
            val diffs = fieldLevelDiffs(table, missingInRoom, extraInRoom)
            TableResult(table, room.size, ethalon.size, missingInRoom.size, extraInRoom.size, diffs)
        }

        return Result(assetName, tables)
    }

    /**
     * Полевой diff для строк, присутствующих только на одной стороне: спаривает
     * ethalon-строку с room-строкой по максимальному совпадению значений атрибутов
     * (одинаковый идентификатор, изменилась часть полей). Реально новые/удалённые
     * строки парой не связываются и остаются в счётчиках missing/extra.
     */
    private fun fieldLevelDiffs(
        table: String,
        missing: List<String>,
        extra: List<String>
    ): List<RowDiff> {
        val diffs = mutableListOf<RowDiff>()
        val extraPool = extra.toMutableList()
        for (m in missing) {
            val mFields = parseCanonical(m)
            var bestIdx = -1
            var bestScore = -1
            for ((i, e) in extraPool.withIndex()) {
                val score = sharedFieldScore(mFields, parseCanonical(e))
                if (score > bestScore) {
                    bestScore = score
                    bestIdx = i
                }
            }
            if (bestIdx < 0 || bestScore <= 0) continue
            val eFields = parseCanonical(extraPool[bestIdx])
            val changed = (mFields.keys + eFields.keys)
                .filter { mFields[it] != eFields[it] }
                .sorted()
                .take(MAX_DIFF_FIELDS)
            if (changed.isEmpty()) continue
            diffs += RowDiff(
                table,
                room = changed.joinToString(", ") { "$it=${eFields[it]}" },
                ethalon = changed.joinToString(", ") { "$it=${mFields[it]}" }
            )
            extraPool.removeAt(bestIdx)
        }
        return diffs
    }

    private fun sharedFieldScore(a: Map<String, String>, b: Map<String, String>): Int =
        a.keys.intersect(b.keys).count { k -> a[k] == b[k] }

    /** Каноническая строка "k=v&k=v&..." → map. */
    private fun parseCanonical(canonical: String): Map<String, String> {
        if (canonical.isBlank()) return emptyMap()
        return canonical.split("&").mapNotNull { part ->
            val idx = part.indexOf('=')
            if (idx <= 0) null else part.substring(0, idx) to part.substring(idx + 1)
        }.toMap()
    }

    /** Мультимножественная разность: элементы this, отсутствующие в other (O(n)). */
    private fun List<String>.subtract(other: List<String>): MutableList<String> {
        val counts = other.groupingBy { it }.eachCount().toMutableMap()
        val result = mutableListOf<String>()
        for (s in this) {
            val c = counts[s] ?: 0
            if (c > 0) counts[s] = c - 1 else result += s
        }
        return result
    }

    private fun readRoom(): Map<String, MutableList<String>> {
        val cursor = resolver.query(Uri.parse(URI), null, null, null, null)
            ?: return emptyMap()
        return cursor.use { c ->
            val colTable = c.getColumnIndexOrThrow("table_name")
            val colPayload = c.getColumnIndexOrThrow("payload_json")
            val colDeleted = c.getColumnIndexOrThrow("deleted_at")
            val map = mutableMapOf<String, MutableList<String>>()
            while (c.moveToNext()) {
                val table = c.getString(colTable)
                val payload = c.getString(colPayload)
                val deletedAt = if (c.isNull(colDeleted)) null else c.getString(colDeleted)
                val canonical = canonicalizeRoom(table, payload, deletedAt)
                if (canonical.isEmpty()) continue
                map.getOrPut(table) { mutableListOf() } += canonical
            }
            map
        }
    }

    private fun ignoredFor(table: String): Set<String> =
        IGNORED_FIELDS + (UNSTABLE_FIELDS_BY_TABLE[table] ?: emptySet())

    /** Канонизация строки из reference_rows (payload_json = JsonFormat, camelCase). */
    private fun canonicalizeRoom(table: String, payloadJson: String, deletedAt: String?): String {
        val json = try {
            JSONObject(payloadJson)
        } catch (e: Exception) {
            return "err:bad-json"
        }
        val ignored = ignoredFor(table)
        val parts = mutableListOf<String>()
        json.keys().forEach { k ->
            val snake = camelToSnake(k)
            // deleted_at приходит и в payload (JsonFormat), и в отдельной колонке —
            // используем только колонку (см. isDeleted ниже), чтобы не дублировать ключ.
            if (snake == "deleted_at") return@forEach
            if (snake in ignored) return@forEach
            val v = json.opt(k)
            val canonical = canonicalValue(v)
            if (canonical != null) parts += "$snake=$canonical"
        }
        val isDeleted = !deletedAt.isNullOrBlank()
        // Пустая/мусорная строка без бизнес-полей и не-удалённая — не участвует в сверке.
        if (!isDeleted && parts.isEmpty()) return ""
        if (isDeleted) parts += "deleted=1"
        return parts.sorted().joinToString("&")
    }

    /** Канонизация строки из ethalon SQL (snake_case). */
    private fun canonicalize(table: String, ethalonRow: JSONObject): String {
        val ignored = ignoredFor(table)
        val parts = mutableListOf<String>()
        ethalonRow.keys().forEach { k ->
            if (k in ignored) return@forEach
            val v = ethalonRow.opt(k)
            if (k == "deleted_at") {
                val isDeleted = when (v) {
                    null, JSONObject.NULL -> false
                    is Boolean -> v
                    else -> true
                }
                if (isDeleted) parts += "deleted=1"
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
        if (candidate.length > 10 && candidate[4] == '-' && candidate[10] == ' ') {
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
