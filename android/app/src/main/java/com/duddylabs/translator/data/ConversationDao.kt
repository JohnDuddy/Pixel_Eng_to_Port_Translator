package com.duddylabs.translator.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {
    @Insert
    suspend fun insertConversation(conversation: ConversationEntity): Long

    @Insert
    suspend fun insertMessage(message: MessageEntity): Long

    @Query("SELECT * FROM conversations ORDER BY dateTime DESC")
    fun observeConversations(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestamp ASC")
    fun observeMessages(conversationId: Long): Flow<List<MessageEntity>>

    @Query(
        """
        SELECT * FROM messages
        WHERE originalText LIKE '%' || :query || '%'
           OR polishedTranslation LIKE '%' || :query || '%'
           OR literalTranslation LIKE '%' || :query || '%'
        ORDER BY timestamp DESC
        """,
    )
    fun searchMessages(query: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages ORDER BY timestamp DESC LIMIT :limit")
    fun observeRecentMessages(limit: Int = 100): Flow<List<MessageEntity>>

    @Query("DELETE FROM conversations")
    suspend fun deleteAllConversations()
}
