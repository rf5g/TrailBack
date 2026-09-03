package com.trailback.app.data.db
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
@Dao
interface MarkedPlaceDao {
    @Insert
    suspend fun insert(place: MarkedPlace): Long
    @Query("SELECT * FROM marked_places ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<MarkedPlace>>
    @Delete
    suspend fun delete(place: MarkedPlace)
}
