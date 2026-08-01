package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "translation_history")
data class TranslationHistory(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val originalText: String,
    val translatedText: String,
    val sourceLanguage: String = "Auto",
    val timestamp: Long = System.currentTimeMillis()
)
