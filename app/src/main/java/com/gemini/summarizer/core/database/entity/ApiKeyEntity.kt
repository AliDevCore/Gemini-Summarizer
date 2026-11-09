package com.gemini.summarizer.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "api_keys",
    foreignKeys = [
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["accountId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["accountId"]), Index(value = ["projectId"])]
)
data class ApiKeyEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val accountId: Long,
    val keyIdentifier: String, // Reference ID used in SecurityManager
    val projectId: String,      // Google Cloud Project ID for shared quota grouping
    val isActive: Boolean = true,
    val lastUsedTimestamp: Long = 0L,
    val isInvalid: Boolean = false,
    val statusMessage: String = "Ready"
)

