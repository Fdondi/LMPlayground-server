package com.druk.lmplayground.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [ChatSessionEntity::class, ChatMessageEntity::class],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun chatDao(): ChatDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE chat_sessions ADD COLUMN contextSize INTEGER NOT NULL DEFAULT 4096")
                db.execSQL("ALTER TABLE chat_sessions ADD COLUMN temperature REAL NOT NULL DEFAULT 0.8")
                db.execSQL("ALTER TABLE chat_sessions ADD COLUMN topP REAL NOT NULL DEFAULT 0.95")
                db.execSQL("ALTER TABLE chat_sessions ADD COLUMN repetitionPenalty REAL NOT NULL DEFAULT 1.0")
                db.execSQL("ALTER TABLE chat_sessions ADD COLUMN topK INTEGER NOT NULL DEFAULT 40")
                db.execSQL("ALTER TABLE chat_sessions ADD COLUMN minP REAL NOT NULL DEFAULT 0.05")
                db.execSQL("ALTER TABLE chat_sessions ADD COLUMN seed INTEGER NOT NULL DEFAULT -1")
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "lmplayground.db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .build().also { INSTANCE = it }
            }
        }
    }
}
