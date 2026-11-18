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

        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Table definitions are preserved
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "gemini_summarizer_database"
                )
                .addMigrations(MIGRATION_1_2)
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
                            val all = dao.getAllModelsList()
                            for (model in all) {
                                if (!ModelFilter.isAllowedTextModel(model.id, model.displayName)) {
                                    dao.deleteModelById(model.id)
                                }
                            }
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
                    id = "@cf/google/gemma-4-26b-a4b-it",
                    displayName = "Gemma 4 26B (Cloudflare)",
                    description = "Google Gemma 4 26B Instruct on Cloudflare Workers AI with 256K Context Window",
                    inputTokenLimit = 262144,
                    outputTokenLimit = 4096,
                    isPreview = false,
                    isStable = true,
                    supportsTextGeneration = true,
                    priority = 1,
                    isEnabled = true
                )
            )
        }
    }
}
