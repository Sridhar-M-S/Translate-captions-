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

    init {
        tts = TextToSpeech(context.applicationContext, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val locale = Locale("ta", "IN")
            val result = tts?.setLanguage(locale)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e("TtsManager", "Tamil language is not supported or missing data")
                // Fallback to standard Tamil locale
                tts?.setLanguage(Locale("ta"))
            }
            isInitialized = true
            pendingText?.let {
                speak(it)
                pendingText = null
            }
        } else {
            Log.e("TtsManager", "TTS Initialization failed")
        }
    }

    fun speak(text: String, speed: Float = 1.0f, pitch: Float = 1.0f, gender: String = "female", volume: Float = 1.0f) {
        if (!isInitialized) {
            pendingText = text
            return
        }
        try {
            tts?.setSpeechRate(speed)
            tts?.setPitch(pitch)
            
            // Try to set high-quality Tamil voice based on requested gender if available
            val voices = tts?.voices
            if (voices != null) {
                val tamilVoices = voices.filter { voice ->
                    voice.locale.language == "ta"
                }
                
                if (tamilVoices.isNotEmpty()) {
                    val matchedVoice = if (gender.lowercase() == "male") {
                        // Look for typical male voice markers (e.g., "-man-", "-male-", "tam" for tamil male, etc.)
                        tamilVoices.find { voice ->
                            voice.name.contains("male", ignoreCase = true) ||
                            voice.name.contains("man", ignoreCase = true) ||
                            voice.name.contains("tam", ignoreCase = true)
                        } ?: tamilVoices.firstOrNull()
                    } else {
                        // Look for typical female voice markers
                        tamilVoices.find { voice ->
                            voice.name.contains("female", ignoreCase = true) ||
                            voice.name.contains("woman", ignoreCase = true) ||
                            voice.name.contains("taf", ignoreCase = true)
                        } ?: tamilVoices.firstOrNull()
                    }
                    if (matchedVoice != null) {
                        tts?.voice = matchedVoice
                    }
                }
            }

            // Set translation voice volume relative multiplier
            val params = android.os.Bundle().apply {
                putString(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume.toString())
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
