package com.example.speech

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.util.Log
import java.util.Locale

class TtsManager(private val context: Context) : TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = null
    private var isInitialized = false
    private var pendingText: String? = null
    private var pendingLang: String? = null

    init {
        tts = TextToSpeech(context.applicationContext, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            isInitialized = true
            pendingText?.let {
                speak(it, pendingLang ?: "ta")
                pendingText = null
                pendingLang = null
            }
        } else {
            Log.e("TtsManager", "TTS Initialization failed")
        }
    }

    fun speak(text: String, languageCode: String = "ta", speed: Float = 1.0f, pitch: Float = 1.0f, gender: String = "female", volume: Float = 1.0f) {
        if (!isInitialized) {
            pendingText = text
            pendingLang = languageCode
            return
        }
        try {
            tts?.setSpeechRate(speed)
            tts?.setPitch(pitch)
            
            val locale = when (languageCode.lowercase()) {
                "ta" -> Locale("ta", "IN")
                "hi" -> Locale("hi", "IN")
                "te" -> Locale("te", "IN")
                else -> Locale(languageCode)
            }
            tts?.setLanguage(locale)
            
            // Try to set high-quality voice based on requested gender if available
            val voices = tts?.voices
            if (voices != null) {
                val matchingVoices = voices.filter { voice ->
                    voice.locale.language == locale.language
                }
                
                if (matchingVoices.isNotEmpty()) {
                    val matchedVoice = if (gender.lowercase() == "male") {
                        matchingVoices.find { voice ->
                            voice.name.contains("male", ignoreCase = true) ||
                            voice.name.contains("man", ignoreCase = true) ||
                            voice.name.contains("tam", ignoreCase = true)
                        } ?: matchingVoices.firstOrNull()
                    } else {
                        matchingVoices.find { voice ->
                            voice.name.contains("female", ignoreCase = true) ||
                            voice.name.contains("woman", ignoreCase = true) ||
                            voice.name.contains("taf", ignoreCase = true)
                        } ?: matchingVoices.firstOrNull()
                    }
                    if (matchedVoice != null) {
                        tts?.voice = matchedVoice
                    }
                }
            }

            // Set translation voice volume relative multiplier
            val params = android.os.Bundle().apply {
                putString(TextToSpeech.Engine.KEY_PARAM_VOLUME, "1.0") // Keep full relative volume, stream master handles it
                // Use STREAM_ACCESSIBILITY so we can mute stream_music without muting translation
                putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, android.media.AudioManager.STREAM_ACCESSIBILITY)
            }

            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, "LiveSubtitleTr")
        } catch (e: Exception) {
            Log.e("TtsManager", "Error speaking text", e)
        }
    }

    fun stop() {
        if (isInitialized) {
            tts?.stop()
        }
    }

    fun shutdown() {
        if (isInitialized) {
            tts?.stop()
            tts?.shutdown()
            isInitialized = false
        }
    }
}
