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
    version = 2,
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
                .fallbackToDestructiveMigration()
                .addCallback(object : Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        super.onCreate(db)
                        CoroutineScope(Dispatchers.IO).launch {
                            getInstance(context).modelDao().insertModels(getDefaultModels())
                        }
                    }
                    override fun onOpen(db: SupportSQLiteDatabase) {
                        super.onOpen(db)
                        CoroutineScope(Dispatchers.IO).launch {
                            val dao = getInstance(context).modelDao()
                            // Clean up any deprecated/unsupported models from database
                            dao.deleteDeprecatedModels()
                            // Ensure modern models are always present
                            for (model in getDefaultModels()) {
                                dao.insertModelIfNotExist(model)
                            }
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
                    id = "gemini-3.8-flash",
                    displayName = "Gemini 3.8 Flash",
                    description = "High-efficiency balanced model optimized for fast summarization",
                    inputTokenLimit = 1048576,
                    outputTokenLimit = 8192,
                    isPreview = false,
                    isStable = true,
                    supportsTextGeneration = true,
                    priority = 1,
                    isEnabled = true
                ),
                ModelEntity(
                    id = "gemini-3.7-flash",
                    displayName = "Gemini 3.7 Flash",
                    description = "Enhanced speed and quality reasoning model",
                    inputTokenLimit = 1048576,
                    outputTokenLimit = 8192,
                    isPreview = false,
                    isStable = true,
                    supportsTextGeneration = true,
                    priority = 2,
                    isEnabled = true
                ),
                ModelEntity(
                    id = "gemini-3.6-flash",
                    displayName = "Gemini 3.6 Flash",
                    description = "Stable performance model for high-volume summarization",
                    inputTokenLimit = 1048576,
                    outputTokenLimit = 8192,
                    isPreview = false,
                    isStable = true,
                    supportsTextGeneration = true,
                    priority = 3,
                    isEnabled = true
                ),
                ModelEntity(
                    id = "gemini-3.5-flash",
                    displayName = "Gemini 3.5 Flash",
                    description = "Standard high-throughput production Flash model",
                    inputTokenLimit = 1048576,
                    outputTokenLimit = 8192,
                    isPreview = false,
                    isStable = true,
                    supportsTextGeneration = true,
                    priority = 4,
                    isEnabled = true
                ),
                ModelEntity(
                    id = "gemini-3.5-flash-lite",
                    displayName = "Gemini 3.5 Flash-Lite",
                    description = "Ultra-fast, lowest latency model for rapid response",
                    inputTokenLimit = 1048576,
                    outputTokenLimit = 8192,
                    isPreview = false,
                    isStable = true,
                    supportsTextGeneration = true,
                    priority = 5,
                    isEnabled = true
                ),
                ModelEntity(
                    id = "gemini-3.1-pro-preview",
                    displayName = "Gemini 3.1 Pro Preview",
                    description = "Most capable frontier model for deep reasoning and complex synthesis",
                    inputTokenLimit = 2097152,
                    outputTokenLimit = 8192,
                    isPreview = true,
                    isStable = false,
                    supportsTextGeneration = true,
                    priority = 6,
                    isEnabled = true
                ),
                ModelEntity(
                    id = "gemini-3.1-flash-lite",
                    displayName = "Gemini 3.1 Flash-Lite",
                    description = "Lightweight high-speed model with low quota footprint",
                    inputTokenLimit = 1048576,
                    outputTokenLimit = 8192,
                    isPreview = false,
                    isStable = true,
                    supportsTextGeneration = true,
                    priority = 7,
                    isEnabled = true
                ),
                ModelEntity(
                    id = "gemini-3-flash-preview",
                    displayName = "Gemini 3 Flash Preview",
                    description = "Next-gen ultra fast multimodal model with frontier speed",
                    inputTokenLimit = 1048576,
                    outputTokenLimit = 8192,
                    isPreview = true,
                    isStable = false,
                    supportsTextGeneration = true,
                    priority = 8,
                    isEnabled = true
                ),
                ModelEntity(
                    id = "gemini-flash-lite-latest",
                    displayName = "Gemini Flash-Lite Latest",
                    description = "Dynamic alias tracking the newest production Flash-Lite checkpoint",
                    inputTokenLimit = 1048576,
                    outputTokenLimit = 8192,
                    isPreview = false,
                    isStable = true,
                    supportsTextGeneration = true,
                    priority = 9,
                    isEnabled = true
                )
            )
        }
    }
}
