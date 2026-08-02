package ru.asop.terminal.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import ru.asop.terminal.db.AppDatabase
import androidx.sqlite.db.SupportSQLiteQueryBuilder

/**
 * Read-only ContentProvider над 6 Room-таблицами терминала.
 * Доступ — только приложениям, подписанным тем же debug-ключом
 * (custom permission ru.asop.terminal.provider.READ).
 */
class AsopContentProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = "ru.asop.terminal.provider"
        private const val PENDING_EVENTS = 1
        private const val SESSIONS = 2
        private const val TRANSACTIONS = 3
        private const val SYNC_META = 4
        private const val DELTA_SYNC_JOBS = 5
        private const val REFERENCE_ROWS = 6

        private val TABLE_BY_CODE = mapOf(
            PENDING_EVENTS to "pending_events",
            SESSIONS to "sessions",
            TRANSACTIONS to "transactions",
            SYNC_META to "sync_meta",
            DELTA_SYNC_JOBS to "delta_sync_jobs",
            REFERENCE_ROWS to "reference_rows"
        )

        private val MATCHER = android.content.UriMatcher(android.content.UriMatcher.NO_MATCH).apply {
            addURI(AUTHORITY, "pending_events", PENDING_EVENTS)
            addURI(AUTHORITY, "sessions", SESSIONS)
            addURI(AUTHORITY, "transactions", TRANSACTIONS)
            addURI(AUTHORITY, "sync_meta", SYNC_META)
            addURI(AUTHORITY, "delta_sync_jobs", DELTA_SYNC_JOBS)
            addURI(AUTHORITY, "reference_rows", REFERENCE_ROWS)
        }
    }

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? {
        val code = MATCHER.match(uri)
        val table = TABLE_BY_CODE[code] ?: return null
        val db = AppDatabase.instance?.openHelper?.readableDatabase ?: return null
        val builder = SupportSQLiteQueryBuilder.builder(table)
        if (projection != null && projection.isNotEmpty()) builder.columns(projection)
        builder.selection(selection, selectionArgs)
        if (!sortOrder.isNullOrBlank()) builder.orderBy(sortOrder)
        return db.query(builder.create())
    }

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0
}
