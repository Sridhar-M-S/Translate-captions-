package com.example.speech

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.LinkedList
import java.util.Locale

class TtsManager(private val context: Context) : TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = null
    private var isInitialized = false

    data class SpeechItem(
        val text: String,
        val languageCode: String,
        val speed: Float,
        val pitch: Float,
        val gender: String,
        val volume: Float
    )

    private val speechQueue: LinkedList<SpeechItem> = LinkedList()
    @Volatile private var speaking = false

    init {
        tts = TextToSpeech(context.applicationContext, this).apply {
            setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    speaking = true
                }

                override fun onDone(utteranceId: String?) {
                    speaking = false
                    playNext()
                }

                override fun onError(utteranceId: String?) {
                    speaking = false
                    playNext()
                }

                override fun onStop(utteranceId: String?, interrupted: Boolean) {
                    speaking = false
                    playNext()
                }
            })
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            isInitialized = true
            playNext()
        } else {
            Log.e("TtsManager", "TTS Initialization failed")
        }
    }

    @Synchronized
    fun speak(text: String, languageCode: String = "ta", speed: Float = 1.0f, pitch: Float = 1.0f, gender: String = "female", volume: Float = 1.0f) {
        if (text.isBlank()) return
        val item = SpeechItem(text, languageCode, speed, pitch, gender, volume)
        speechQueue.offer(item)
        if (isInitialized && !speaking) {
            playNext()
        }
    }

    @Synchronized
    private fun playNext() {
        if (!isInitialized) return
        val item = speechQueue.poll() ?: return
        speaking = true

        try {
            tts?.setSpeechRate(item.speed)
            tts?.setPitch(item.pitch)

            val locale = when (item.languageCode.lowercase()) {
                "ta" -> Locale("ta", "IN")
                "hi" -> Locale("hi", "IN")
                "te" -> Locale("te", "IN")
                "tanglish" -> Locale.US
                else -> Locale(item.languageCode)
            }
            val langResult = tts?.setLanguage(locale)
            if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED || langResult == null) {
                val fallbackResult = tts?.setLanguage(Locale.getDefault())
                if (fallbackResult == TextToSpeech.LANG_MISSING_DATA || fallbackResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts?.setLanguage(Locale.US)
                }
            }

            val voices = tts?.voices
            if (voices != null) {
                val matchingVoices = voices.filter { it.locale.language == locale.language }
                if (matchingVoices.isNotEmpty()) {
                    val matchedVoice = if (item.gender.lowercase() == "male") {
                        matchingVoices.find { it.name.contains("male", true) || it.name.contains("man", true) } ?: matchingVoices.firstOrNull()
                    } else {
                        matchingVoices.find { it.name.contains("female", true) || it.name.contains("woman", true) } ?: matchingVoices.firstOrNull()
                    }
                    if (matchedVoice != null) {
                        tts?.voice = matchedVoice
                    }
                }
            }

            val params = Bundle().apply {
                putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, item.volume)
                putString(TextToSpeech.Engine.KEY_PARAM_VOLUME, item.volume.toString())
                putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, android.media.AudioManager.STREAM_MUSIC)
            }

            tts?.speak(item.text, TextToSpeech.QUEUE_FLUSH, params, "SubUtterance_${System.currentTimeMillis()}")
        } catch (e: Exception) {
            Log.e("TtsManager", "Error in playNext", e)
            speaking = false
            playNext()
        }
    }

    @Synchronized
    fun skip() {
        if (isInitialized) {
            tts?.stop() // Stops current utterance, triggering onStop -> playNext() immediately
        }
    }

    @Synchronized
    fun stop() {
        if (isInitialized) {
            speechQueue.clear()
            tts?.stop()
            speaking = false
        }
    }

    @Synchronized
    fun shutdown() {
        if (isInitialized) {
            speechQueue.clear()
            tts?.stop()
            tts?.shutdown()
            isInitialized = false
            speaking = false
        }
    }
}
