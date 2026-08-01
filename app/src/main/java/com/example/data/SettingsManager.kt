package com.example.data

import android.content.Context
import android.content.SharedPreferences

class SettingsManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("subtitle_translator_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_LANG = "target_language"
        private const val KEY_SPEED = "speech_speed"
        private const val KEY_PITCH = "speech_pitch"
        private const val KEY_ENABLE_VOICE = "enable_voice"
        private const val KEY_ENABLE_OVERLAY = "enable_overlay"
        private const val KEY_OCR_SENSITIVITY = "ocr_sensitivity"
        private const val KEY_OCR_ENABLED = "ocr_enabled"
        private const val KEY_TRANSLATOR_ACTIVE = "translator_active"
    }

    var targetLanguage: String
        get() = prefs.getString(KEY_LANG, "ta") ?: "ta"
        set(value) = prefs.edit().putString(KEY_LANG, value).apply()

    var speechSpeed: Float
        get() = prefs.getFloat(KEY_SPEED, 1.0f)
        set(value) = prefs.edit().putFloat(KEY_SPEED, value).apply()

    var speechPitch: Float
        get() = prefs.getFloat(KEY_PITCH, 1.0f)
        set(value) = prefs.edit().putFloat(KEY_PITCH, value).apply()

    var isVoiceEnabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLE_VOICE, true)
        set(value) = prefs.edit().putBoolean(KEY_ENABLE_VOICE, value).apply()

    var isOverlayEnabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLE_OVERLAY, true)
        set(value) = prefs.edit().putBoolean(KEY_ENABLE_OVERLAY, value).apply()

    var ocrSensitivity: Long
        get() = prefs.getLong(KEY_OCR_SENSITIVITY, 1500L)
        set(value) = prefs.edit().putLong(KEY_OCR_SENSITIVITY, value).apply()

    var isOcrEnabled: Boolean
        get() = prefs.getBoolean(KEY_OCR_ENABLED, true) // Enable by default as standard fallback
        set(value) = prefs.edit().putBoolean(KEY_OCR_ENABLED, value).apply()

    var isTranslatorActive: Boolean
        get() = prefs.getBoolean(KEY_TRANSLATOR_ACTIVE, false)
        set(value) = prefs.edit().putBoolean(KEY_TRANSLATOR_ACTIVE, value).apply()
}
