package com.gemini.summarizer.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.gemini.summarizer.core.database.dao.*
import com.gemini.summarizer.core.database.entity.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Database(
    entities = [
        AccountEntity::class,
        ApiKeyEntity::class,
        ModelEntity::class,
        QuotaStateEntity::class,
        HistoryEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun accountDao(): AccountDao
    abstract fun apiKeyDao(): ApiKeyDao
    abstract fun modelDao(): ModelDao
    abstract fun quotaStateDao(): QuotaStateDao
    abstract fun historyDao(): HistoryDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "gemini_summarizer_database"
                )
                .addCallback(object : Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        super.onCreate(db)
                        // Pre-populate initial default models
                        CoroutineScope(Dispatchers.IO).launch {
                            getInstance(context).modelDao().insertModels(
                                getDefaultModels()
                            )
                        }
                    }
                })
                .build()
                INSTANCE = instance
                instance
            }
        }

        fun getDefaultModels(): List<ModelEntity> {
            return listOf(
                ModelEntity(
                    id = "gemini-2.5-pro",
                    displayName = "Gemini 2.5 Pro",
                    description = "Most capable model for deep reasoning and complex summarization",
                    inputTokenLimit = 2097152,
                    outputTokenLimit = 8192,
                    isPreview = true,
                    isStable = false,
                    supportsTextGeneration = true,
                    priority = 1,
                    isEnabled = true
                ),
                ModelEntity(
                    id = "gemini-2.0-flash",
                    displayName = "Gemini 2.0 Flash",
                    description = "Next-gen multimodal, fast speed with high quality",
                    inputTokenLimit = 1048576,
                    outputTokenLimit = 8192,
                    isPreview = false,
                    isStable = true,
                    supportsTextGeneration = true,
                    priority = 2,
                    isEnabled = true
                ),
                ModelEntity(
                    id = "gemini-1.5-pro",
                    displayName = "Gemini 1.5 Pro",
                    description = "Advanced reasoning, long context window up to 2M tokens",
                    inputTokenLimit = 2097152,
                    outputTokenLimit = 8192,
                    isPreview = false,
                    isStable = true,
                    supportsTextGeneration = true,
                    priority = 3,
                    isEnabled = true
                ),
                ModelEntity(
                    id = "gemini-1.5-flash",
                    displayName = "Gemini 1.5 Flash",
                    description = "Fast and versatile performance for general summarization",
                    inputTokenLimit = 1048576,
                    outputTokenLimit = 8192,
                    isPreview = false,
                    isStable = true,
                    supportsTextGeneration = true,
                    priority = 4,
                    isEnabled = true
                ),
                ModelEntity(
                    id = "gemini-1.5-flash-8b",
                    displayName = "Gemini 1.5 Flash-8B",
                    description = "High-throughput and lightweight model",
                    inputTokenLimit = 1048576,
                    outputTokenLimit = 8192,
                    isPreview = false,
                    isStable = true,
                    supportsTextGeneration = true,
                    priority = 5,
                    isEnabled = true
                )
            )
        }
    }
}
