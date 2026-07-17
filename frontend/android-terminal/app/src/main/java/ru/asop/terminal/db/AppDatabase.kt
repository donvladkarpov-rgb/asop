package ru.asop.terminal.db

import androidx.room.Database
import androidx.room.RoomDatabase
import ru.asop.terminal.db.dao.PendingEventDao
import ru.asop.terminal.db.dao.SessionDao
import ru.asop.terminal.db.dao.TransactionDao
import ru.asop.terminal.db.entity.PendingEventEntity
import ru.asop.terminal.db.entity.SessionEntity
import ru.asop.terminal.db.entity.TransactionEntity

@Database(
    entities = [
        PendingEventEntity::class,
        SessionEntity::class,
        TransactionEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun pendingEventDao(): PendingEventDao
    abstract fun sessionDao(): SessionDao
    abstract fun transactionDao(): TransactionDao
}
