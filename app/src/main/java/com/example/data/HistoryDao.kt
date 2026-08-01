package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface HistoryDao {
    @Query("SELECT * FROM translation_history ORDER BY timestamp DESC")
    fun getAllHistory(): Flow<List<TranslationHistory>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHistory(history: TranslationHistory)

    @Query("DELETE FROM translation_history WHERE id = :id")
    suspend fun deleteHistoryById(id: Int)

    @Query("DELETE FROM translation_history")
    suspend fun clearAllHistory()

    @Query("SELECT * FROM translation_history WHERE originalText LIKE :query OR translatedText LIKE :query ORDER BY timestamp DESC")
    fun searchHistory(query: String): Flow<List<TranslationHistory>>
}
