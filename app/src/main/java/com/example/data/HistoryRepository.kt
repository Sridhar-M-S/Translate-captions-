package com.example.data

import kotlinx.coroutines.flow.Flow

class HistoryRepository(private val historyDao: HistoryDao) {
    val allHistory: Flow<List<TranslationHistory>> = historyDao.getAllHistory()

    suspend fun insert(history: TranslationHistory) {
        historyDao.insertHistory(history)
    }

    suspend fun deleteById(id: Int) {
        historyDao.deleteHistoryById(id)
    }

    suspend fun clearAll() {
        historyDao.clearAllHistory()
    }

    fun search(query: String): Flow<List<TranslationHistory>> {
        return historyDao.searchHistory("%$query%")
    }
}
