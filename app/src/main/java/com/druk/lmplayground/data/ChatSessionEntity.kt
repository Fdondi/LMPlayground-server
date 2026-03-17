package com.druk.lmplayground.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "chat_sessions")
data class ChatSessionEntity(
    @PrimaryKey val id: String,
    val title: String,
    val modelFilename: String,
    val modelName: String,
    val createdAt: Long,
    val updatedAt: Long,
    val pinned: Boolean = false
)
