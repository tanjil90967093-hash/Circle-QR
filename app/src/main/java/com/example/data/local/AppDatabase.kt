package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.data.local.dao.ExpenseDao
import com.example.data.local.dao.HistoryDao
import com.example.data.local.dao.NoteDao
import com.example.data.local.dao.UtilityDao
import com.example.data.local.entity.ExpenseEntity
import com.example.data.local.entity.FavoriteEntity
import com.example.data.local.entity.HistoryEntity
import com.example.data.local.entity.NoteEntity
import com.example.data.local.entity.RecentToolEntity

@Database(
    entities = [
        ExpenseEntity::class,
        NoteEntity::class,
        HistoryEntity::class,
        FavoriteEntity::class,
        RecentToolEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun expenseDao(): ExpenseDao
    abstract fun noteDao(): NoteDao
    abstract fun historyDao(): HistoryDao
    abstract fun utilityDao(): UtilityDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "utility_hub.db"
                ).fallbackToDestructiveMigration().build()
                INSTANCE = instance
                instance
            }
        }
    }
}
