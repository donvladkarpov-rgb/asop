package ru.asop.terminal.db

import androidx.room.Database
import androidx.room.RoomDatabase
import ru.asop.terminal.db.dao.DeltaSyncJobDao
import ru.asop.terminal.db.dao.PendingEventDao
import ru.asop.terminal.db.dao.ReferenceRowDao
import ru.asop.terminal.db.dao.SessionDao
import ru.asop.terminal.db.dao.SyncMetaDao
import ru.asop.terminal.db.dao.TerminalKeyDao
import ru.asop.terminal.db.dao.TransactionDao
import ru.asop.terminal.db.entity.DeltaSyncJobEntity
import ru.asop.terminal.db.entity.PendingEventEntity
import ru.asop.terminal.db.entity.ReferenceRowEntity
import ru.asop.terminal.db.entity.SessionEntity
import ru.asop.terminal.db.entity.SyncMetaEntity
import ru.asop.terminal.db.entity.TerminalKeyEntity
import ru.asop.terminal.db.entity.TransactionEntity

@Database(
    entities = [
        PendingEventEntity::class,
        SessionEntity::class,
        TransactionEntity::class,
        SyncMetaEntity::class,
        DeltaSyncJobEntity::class,
        ReferenceRowEntity::class,
        TerminalKeyEntity::class
    ],
    version = 5,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun pendingEventDao(): PendingEventDao
    abstract fun sessionDao(): SessionDao
    abstract fun transactionDao(): TransactionDao
    abstract fun syncMetaDao(): SyncMetaDao
    abstract fun deltaSyncJobDao(): DeltaSyncJobDao
    abstract fun referenceRowDao(): ReferenceRowDao
    abstract fun terminalKeyDao(): TerminalKeyDao

    companion object {
        @Volatile
        var instance: AppDatabase? = null
    }
}