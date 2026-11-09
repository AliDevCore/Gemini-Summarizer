package com.gemini.summarizer.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "summary_history")
data class HistoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val originalText: String,
    val summaryText: String,
    val modelUsed: String,
    val accountUsed: String,
    val projectId: String,
    val timestamp: Long = System.currentTimeMillis(),
    val inputWords: Int,
    val outputWords: Int,
    val stepsCount: Int = 1,
    val targetLanguage: String = "English",
    val summaryType: String = "General"
)

