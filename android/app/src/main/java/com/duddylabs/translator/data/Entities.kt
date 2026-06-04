package com.duddylabs.translator.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey(autoGenerate = true)
    val conversationId: Long = 0,
    val dateTime: Long,
    val mode: TranslatorMode,
)

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["conversationId"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("conversationId"), Index("timestamp")],
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true)
    val messageId: Long = 0,
    val conversationId: Long,
    val sourceLanguage: SpeakerLanguage,
    val targetLanguage: SpeakerLanguage,
    val originalText: String,
    val literalTranslation: String,
    val polishedTranslation: String,
    val timestamp: Long,
)
