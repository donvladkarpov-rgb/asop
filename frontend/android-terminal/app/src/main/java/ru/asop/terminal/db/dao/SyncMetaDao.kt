package ru.asop.terminal.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import ru.asop.terminal.db.entity.SyncMetaEntity

@Dao
interface SyncMetaDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(meta: SyncMetaEntity)

    @Query("SELECT * FROM sync_meta WHERE id = 0")
    suspend fun get(): SyncMetaEntity?

    @Query("SELECT * FROM sync_meta WHERE id = 0")
    fun observe(): Flow<SyncMetaEntity?>

    /**
     * Сброс терминала при повторной регистрации: watermark обнуляется, и следующая дельта
     * забирает справочники с нуля (все читатели используют get()?.lastVersion → null → 0).
     */
    @Query("DELETE FROM sync_meta")
    suspend fun clearAll()
}
