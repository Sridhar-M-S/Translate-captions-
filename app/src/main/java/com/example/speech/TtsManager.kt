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

    fun speak(text: String, speed: Float = 1.0f, pitch: Float = 1.0f) {
        if (!isInitialized) {
            pendingText = text
            return
        }
        try {
            tts?.setSpeechRate(speed)
            tts?.setPitch(pitch)
            
            // Try to set high-quality Tamil voice if available
            val voices = tts?.voices
            if (voices != null) {
                val tamilVoice = voices.find { voice ->
                    voice.locale.language == "ta" && !voice.isNetworkConnectionRequired
                } ?: voices.find { voice ->
                    voice.locale.language == "ta"
                }
                if (tamilVoice != null) {
                    tts?.voice = tamilVoice
                }
            }

            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "LiveSubtitleTr")
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
