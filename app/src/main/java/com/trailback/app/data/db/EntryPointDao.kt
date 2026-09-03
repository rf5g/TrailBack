package com.trailback.app.data.db
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
@Dao
interface EntryPointDao {
    @Insert
    suspend fun insert(entryPoint: EntryPoint): Long
    @Query("SELECT * FROM entry_points ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<EntryPoint>>
    @Query("SELECT * FROM entry_points WHERE id = :id")
    suspend fun getById(id: Long): EntryPoint?
    @Query("SELECT * FROM entry_points ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLast(): EntryPoint?
    /** Массовая очистка — единственный способ удаления, с тройным подтверждением в UI. */
    @Query("DELETE FROM entry_points")
    suspend fun deleteAll()
}
