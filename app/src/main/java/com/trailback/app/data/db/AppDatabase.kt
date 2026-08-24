package com.trailback.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [EntryPoint::class, TrackPoint::class, MarkedPlace::class],
    version = 1,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun entryPointDao(): EntryPointDao
    abstract fun trackPointDao(): TrackPointDao
    abstract fun markedPlaceDao(): MarkedPlaceDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "trailback.db"
                ).build().also { instance = it }
            }
    }
}
