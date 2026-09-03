package com.trailback.app.data.db
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
@Dao
interface TrackPointDao {
    @Insert
    suspend fun insert(point: TrackPoint)
    @Query("SELECT * FROM track_points WHERE entryPointId = :entryPointId ORDER BY timestamp ASC")
    fun observeForEntryPoint(entryPointId: Long): Flow<List<TrackPoint>>
    @Query("SELECT * FROM track_points WHERE entryPointId = :entryPointId ORDER BY timestamp ASC")
    suspend fun getForEntryPoint(entryPointId: Long): List<TrackPoint>
    @Query("DELETE FROM track_points WHERE entryPointId = :entryPointId")
    suspend fun deleteForEntryPoint(entryPointId: Long)
    @Query("DELETE FROM track_points WHERE entryPointId != :activeEntryPointId")
    suspend fun deleteAllExceptEntryPoint(activeEntryPointId: Long)
    @Query("DELETE FROM track_points")
    suspend fun deleteAll()
    @Query("SELECT COUNT(*) FROM track_points WHERE entryPointId = :entryPointId")
    suspend fun countForEntryPoint(entryPointId: Long): Int
}
