package com.example.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.FrameLayout
import android.media.AudioManager
import java.io.File
import java.io.FileOutputStream
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import com.example.MainActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.*
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.example.data.AppDatabase
import com.example.data.HistoryRepository
import com.example.data.SettingsManager
import com.example.data.TranslationHistory
import com.example.speech.TtsManager
import com.example.translation.TranslationResult
import com.example.translation.TranslationService
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

@Suppress("DEPRECATION")
class SubtitleAccessibilityService : AccessibilityService() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var settingsManager: SettingsManager
    private lateinit var ttsManager: TtsManager
    private lateinit var translationService: TranslationService
    private lateinit var historyRepository: HistoryRepository
    private var textRecognizer: com.google.mlkit.vision.text.TextRecognizer? = null

    private var lastSubtitleText = ""
    private var lastTranslatedText = ""
    private var detectedLanguage = "en"
    private var emptyOcrFramesCounter = 0

    private var windowManager: WindowManager? = null

    // Subtitle and selection overlay views
    private var subtitleOverlayView: FrameLayout? = null
    private var selectionOverlayView: FrameLayout? = null
    private var menuOverlayView: FrameLayout? = null

    // State flows for compose overlay & debugging
    private val subtitleState = MutableStateFlow("")
    private val translationState = MutableStateFlow("")
    private val isTranslatingState = MutableStateFlow(false)
    private val isMutedState = MutableStateFlow(false)
    private val debugStatusState = MutableStateFlow("Initializing OCR...")
    private val debugRegionSizeState = MutableStateFlow("0x0")
    private val debugRegionCoordsState = MutableStateFlow("l=0, t=0, r=0, b=0")
    private val debugCaptureState = MutableStateFlow("Idle")

    // Periodic OCR scanner handler
    private val ocrHandler = Handler(Looper.getMainLooper())
    private val ocrRunnable = object : Runnable {
        override fun run() {
            if (settingsManager.isTranslatorActive && settingsManager.isOcrEnabled) {
                captureAndRecognizeText()
            }
            ocrHandler.postDelayed(this, settingsManager.ocrSensitivity)
        }
    }

    override fun onCreate() {
        try {
            super.onCreate()
            settingsManager = SettingsManager(this)
            ttsManager = TtsManager(this)
            translationService = TranslationService()
            textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            
            val database = AppDatabase.getDatabase(this)
            historyRepository = HistoryRepository(database.historyDao())

            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            
            // Sync initial state
            isTranslatingState.value = settingsManager.isTranslatorActive
            
            // Register shared preference change listener
            val prefs = getSharedPreferences("subtitle_translator_prefs", Context.MODE_PRIVATE)
            prefs.registerOnSharedPreferenceChangeListener(prefListener)
            
            // Apply initial audio settings
            applyAudioSettings()
        } catch (e: Exception) {
            Log.e("SubtitleService", "Error in onCreate", e)
        }
    }

    private val prefListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "original_video_volume" || key == "is_voice_enabled") {
            applyAudioSettings()
        }
    }

    override fun onServiceConnected() {
        try {
            super.onServiceConnected()
            Log.d("SubtitleService", "Service Connected")
            
            val info = serviceInfo
            info.flags = info.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REQUEST_ACCESSIBILITY_BUTTON
            serviceInfo = info

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    accessibilityButtonController.registerAccessibilityButtonCallback(
                        object : android.accessibilityservice.AccessibilityButtonController.AccessibilityButtonCallback() {
                            override fun onClicked(controller: android.accessibilityservice.AccessibilityButtonController?) {
                                Log.d("SubtitleService", "Accessibility Button Clicked (Controller Callback)")
                                toggleMenuOverlay()
                            }
                        }
                    )
                } catch (e: Exception) {
                    Log.e("SubtitleService", "Error registering accessibility button callback", e)
                }
            }

            if (settingsManager.isTranslatorActive) {
                startTranslationSession()
            }
        } catch (e: Exception) {
            Log.e("SubtitleService", "Error in onServiceConnected", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            intent?.let {
                val action = it.action
                Log.d("SubtitleService", "onStartCommand with action: $action")
                when (action) {
                    "START" -> startTranslationSession()
                    "STOP" -> stopTranslationSession()
                    "SELECT_REGION" -> showSelectionOverlay()
                }
            }
        } catch (e: Exception) {
            Log.e("SubtitleService", "Error in onStartCommand", e)
        }
        return START_STICKY
    }

    private fun startTranslationSession() {
        settingsManager.isTranslatorActive = true
        isTranslatingState.value = true
        
        applyAudioSettings()
        if (settingsManager.isOcrDebugVisible) {
            showSubtitleOverlay()
        }
        
        // Ensure OCR periodic scanning is started and running
        ocrHandler.removeCallbacks(ocrRunnable)
        ocrHandler.postDelayed(ocrRunnable, 500)
        
        Log.d("SubtitleService", "Translation Session Started and OCR scheduled")
    }

    private fun toggleOcrDebugVisibility(visible: Boolean) {
        settingsManager.isOcrDebugVisible = visible
        if (visible) {
            if (settingsManager.isTranslatorActive) {
                showSubtitleOverlay()
            }
        } else {
            removeSubtitleOverlay()
        }
    }

    private fun pauseTranslationSession() {
        settingsManager.isTranslatorActive = false
        isTranslatingState.value = false
        
        ocrHandler.removeCallbacks(ocrRunnable)
        ttsManager.stop()
        applyAudioSettings()
        
        Log.d("SubtitleService", "Translation Session Paused")
    }

    private fun stopTranslationSession() {
        settingsManager.isTranslatorActive = false
        isTranslatingState.value = false
        
        ocrHandler.removeCallbacks(ocrRunnable)
        ttsManager.stop()
        removeSubtitleOverlay()

        // Unmute original audio on stop to half of maximum volume
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxVol / 2, 0)
        } catch (e: Exception) {
            Log.e("SubtitleService", "Error unmuting audio in stopTranslationSession", e)
        }
        
        subtitleState.value = ""
        translationState.value = ""
        lastSubtitleText = ""
        lastTranslatedText = ""

        Log.d("SubtitleService", "Translation Session Stopped")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Ignored to strictly follow: "Only perform OCR inside the selected rectangle. Ignore every other text on the screen outside the rectangle."
    }

    private fun isBitmapBlank(bitmap: Bitmap): Boolean {
        try {
            val w = bitmap.width
            val h = bitmap.height
            if (w <= 0 || h <= 0) return true
            var nonBlackCount = 0
            val stepX = maxOf(1, w / 15)
            val stepY = maxOf(1, h / 15)
            for (x in 0 until w step stepX) {
                for (y in 0 until h step stepY) {
                    val pixel = bitmap.getPixel(x, y)
                    val r = (pixel shr 16) and 0xFF
                    val g = (pixel shr 8) and 0xFF
                    val b = pixel and 0xFF
                    if (r > 20 || g > 20 || b > 20) {
                        nonBlackCount++
                    }
                }
            }
            return nonBlackCount < 3
        } catch (e: Exception) {
            return false
        }
    }

    private fun captureAndRecognizeText() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                Log.d("SubtitleService", "OCR Debug: Initiating takeScreenshot capture...")
                debugCaptureState.value = "Capturing..."
                takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                    override fun onSuccess(screenshotResult: ScreenshotResult) {
                        try {
                            val buffer = screenshotResult.hardwareBuffer
                            val colorSpace = screenshotResult.colorSpace
                            val bitmap = Bitmap.wrapHardwareBuffer(buffer, colorSpace)

                            if (bitmap != null) {
                                val softwareBitmap = try {
                                    bitmap.copy(Bitmap.Config.ARGB_8888, false)
                                } catch (e: Exception) {
                                    Log.e("SubtitleService", "Failed to copy hardware bitmap", e)
                                    null
                                }
                                bitmap.recycle()
                                buffer.close()
                                
                                if (softwareBitmap != null) {
                                    val width = softwareBitmap.width
                                    val height = softwareBitmap.height
                                
                                val lPct = settingsManager.customRectLeft
                                val tPct = settingsManager.customRectTop
                                val rPct = settingsManager.customRectRight
                                val bPct = settingsManager.customRectBottom

                                val left = (lPct * width).toInt().coerceIn(0, width - 1)
                                val top = (tPct * height).toInt().coerceIn(0, height - 1)
                                val right = (rPct * width).toInt().coerceIn(left + 1, width)
                                val bottom = (bPct * height).toInt().coerceIn(top + 1, height)
                                
                                val cropWidth = right - left
                                val cropHeight = bottom - top
                                
                                debugRegionSizeState.value = "${cropWidth}x${cropHeight}"
                                debugRegionCoordsState.value = "l=$left, t=$top, r=$right, b=$bottom"
                                
                                Log.d("SubtitleService", "OCR Debug: Screenshot success. Screen: ${width}x${height}, Crop: l=$left, t=$top, r=$right, b=$bottom (w=$cropWidth, h=$cropHeight)")
                                
                                if (cropWidth > 0 && cropHeight > 0) {
                                    val croppedBitmap = Bitmap.createBitmap(softwareBitmap, left, top, cropWidth, cropHeight)
                                    
                                    // Save cropped bitmap to local storage for inspection
                                    try {
                                        val debugFile = File(filesDir, "debug_ocr_crop.png")
                                        FileOutputStream(debugFile).use { out ->
                                            croppedBitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                                        }
                                        Log.d("SubtitleService", "OCR Debug: Saved cropped OCR bitmap to ${debugFile.absolutePath}")
                                    } catch (e: Exception) {
                                        Log.e("SubtitleService", "OCR Debug: Failed to save debug bitmap", e)
                                    }

                                    val blank = isBitmapBlank(croppedBitmap)
                                    if (blank) {
                                        Log.w("SubtitleService", "OCR Debug: Cropped bitmap is BLANK (all black or uniform).")
                                        debugCaptureState.value = "Capture OK, Bitmap BLANK"
                                    } else {
                                        debugCaptureState.value = "Capture OK, Non-blank"
                                    }

                                    runOcrOnBitmap(croppedBitmap)
                                    croppedBitmap.recycle()
                                } else {
                                    Log.w("SubtitleService", "OCR Debug: Invalid crop dimensions ($cropWidth x $cropHeight)")
                                    debugCaptureState.value = "Invalid crop dimensions"
                                    runOcrOnBitmap(softwareBitmap)
                                }
                                softwareBitmap.recycle()
                            }
                        } else {
                                Log.e("SubtitleService", "OCR Debug: Wrapped hardware bitmap is null!")
                                debugCaptureState.value = "Hardware bitmap is null"
                                buffer.close()
                            }
                        } catch (e: Exception) {
                            Log.e("SubtitleService", "OCR Debug: Error processing screenshot onSuccess", e)
                            debugCaptureState.value = "Processing Error: ${e.localizedMessage}"
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        Log.e("SubtitleService", "OCR Debug: takeScreenshot onFailure called. ErrorCode: $errorCode")
                        debugCaptureState.value = "takeScreenshot Failed (code $errorCode)"
                        subtitleState.value = "No text detected (Capture Failed)"
                    }
                })
            } catch (e: Exception) {
                Log.e("SubtitleService", "OCR Debug: Error requesting takeScreenshot", e)
                debugCaptureState.value = "Request Exception: ${e.localizedMessage}"
            }
        } else {
            Log.w("SubtitleService", "OCR Debug: Screenshot capture not supported below SDK 30")
            debugCaptureState.value = "SDK < 30 not supported"
        }
    }

    private fun isValidWord(word: String): Boolean {
        val trimmed = word.trim()
        if (trimmed.isEmpty()) return false

        // Rule 1: Remove random Unicode garbage characters (only allow ASCII letters, numbers, standard punctuation)
        if (trimmed.any { it.code > 127 || (!it.isLetterOrDigit() && !". , ? ! ' \" - :".contains(it)) }) {
            return false
        }

        // If pure symbols/punctuation (e.g. ###, %%%)
        if (trimmed.all { !it.isLetterOrDigit() }) {
            return false
        }

        val len = trimmed.length

        // Rule 4: Ignore words where more than 40% of characters are digits or symbols
        val nonLetterCount = trimmed.count { !it.isLetter() }
        if (nonLetterCount.toDouble() / len > 0.4) {
            return false
        }

        // Rule 3: Ignore words containing many symbols (more than 2 symbols or symbol ratio > 30%)
        val symbolCount = trimmed.count { !it.isLetterOrDigit() && !it.isWhitespace() }
        if (symbolCount > 2 || (symbolCount.toDouble() / len > 0.3)) {
            return false
        }

        // Rule 2 & 6: Isolated letters mixed with numbers or non-words (e.g. 5I, 1O, or gibberish like Gsiu, Geuw with 0 vowels)
        val lettersOnly = trimmed.filter { it.isLetter() }
        if (lettersOnly.isNotEmpty()) {
            val lowerLetters = lettersOnly.lowercase(Locale.ROOT)
            val vowels = lowerLetters.count { it == 'a' || it == 'e' || it == 'i' || it == 'o' || it == 'u' || it == 'y' }
            // If length >= 3 and 0 vowels, it's gibberish (e.g. Gsiu, Geuw)
            if (lettersOnly.length >= 3 && vowels == 0) {
                return false
            }
            // Single letter must be 'a' or 'i'
            if (lettersOnly.length == 1 && lowerLetters != "a" && lowerLetters != "i") {
                return false
            }
        }

        return true
    }

    private fun extractTextFromVision(visionText: com.google.mlkit.vision.text.Text): String {
        val validLines = mutableListOf<String>()
        for (block in visionText.textBlocks) {
            for (line in block.lines) {
                val lineText = line.text.trim()
                if (lineText.isEmpty()) continue

                val lower = lineText.lowercase(Locale.ROOT)
                if (!lower.contains("visit advertiser") &&
                    !lower.contains("skip ad") &&
                    !lower.contains("sponsored") &&
                    !lower.contains("subscribe") &&
                    !lower.contains("comments") &&
                    !lower.contains("live translator") &&
                    !lower.contains("share") &&
                    !lower.contains("like") &&
                    !lower.contains("dislike") &&
                    !lower.contains("save") &&
                    !lower.contains("download") &&
                    !lower.contains("playlist") &&
                    !lower.contains("autoplay") &&
                    !lower.contains("home") &&
                    !lower.contains("shorts") &&
                    !lower.contains("subscriptions") &&
                    !lower.contains("library") &&
                    !lower.contains("channel") &&
                    !lower.matches(Regex(".*\\d{1,2}:\\d{2}\\s*/\\s*\\d{1,2}:\\d{2}.*")) &&
                    !lower.matches(Regex(".*\\d+\\s*(views|subscribers|likes).*"))
                ) {
                    val words = lineText.split(Regex("\\s+"))
                    val validWords = words.filter { isValidWord(it) }
                    if (validWords.isNotEmpty() && validWords.size >= (words.size / 2.0)) {
                        validLines.add(validWords.joinToString(" "))
                    }
                }
            }
        }
        if (validLines.isEmpty()) return ""
        val combined = validLines.joinToString(" ").trim()
        if (!combined.any { it.isLetter() }) return ""
        return combined
    }

    private fun runOcrOnBitmap(bitmap: Bitmap) {
        try {
            val upscaled = try {
                Bitmap.createScaledBitmap(bitmap, bitmap.width * 2, bitmap.height * 2, true)
            } catch (e: Exception) {
                bitmap
            }

            val image = InputImage.fromBitmap(upscaled, 0)
            val recognizer = textRecognizer ?: TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            
            recognizer.process(image)
                .addOnSuccessListener { visionText ->
                    try {
                        if (upscaled != bitmap) upscaled.recycle()
                        
                        val blockCount = visionText.textBlocks.size
                        val rawText = visionText.text ?: ""
                        Log.d("SubtitleService", "OCR Debug: ML Kit processed. Blocks: $blockCount, Raw text: '$rawText'")
                        
                        val combinedText = extractTextFromVision(visionText)
                        Log.d("SubtitleService", "OCR Debug: Filtered text: '$combinedText'")
                        
                        if (combinedText.isNotEmpty()) {
                            emptyOcrFramesCounter = 0
                            debugStatusState.value = "Success: '$combinedText'"
                            processNewSubtitle(combinedText)
                        } else {
                            emptyOcrFramesCounter++
                            val rootCause = when {
                                blockCount == 0 -> "ML Kit found 0 text blocks"
                                rawText.isEmpty() -> "ML Kit raw text is empty"
                                else -> "Filtered out by rules"
                            }
                            Log.w("SubtitleService", "OCR Debug: No valid text. Reason: $rootCause (Raw: '$rawText')")
                            debugStatusState.value = "No text ($rootCause)"
                            
                            if (emptyOcrFramesCounter >= 5) {
                                subtitleState.value = "No text detected ($rootCause)"
                                if (!settingsManager.isTranslatorActive) {
                                    translationState.value = ""
                                }
                            }
                        }
                    } catch (e: Exception) {
                        if (upscaled != bitmap) upscaled.recycle()
                        Log.e("SubtitleService", "OCR Debug: Error in onSuccess", e)
                        debugStatusState.value = "Error: ${e.localizedMessage}"
                    }
                }
                .addOnFailureListener { e ->
                    if (upscaled != bitmap) upscaled.recycle()
                    Log.e("SubtitleService", "OCR Debug: ML Kit failure", e)
                    debugStatusState.value = "ML Kit Error: ${e.localizedMessage}"
                    emptyOcrFramesCounter++
                    if (emptyOcrFramesCounter >= 5) {
                        subtitleState.value = "No text detected (ML Kit Failed)"
                    }
                }
        } catch (e: Exception) {
            Log.e("SubtitleService", "OCR Debug: Exception running OCR", e)
            debugStatusState.value = "Exception: ${e.localizedMessage}"
        }
    }

    private fun processNewSubtitle(text: String) {
        // Guard against identical subtitle or tiny jitter updates
        if (isDuplicate(text, lastSubtitleText)) return

        lastSubtitleText = text
        subtitleState.value = text

        serviceScope.launch {
            try {
                // Translate
                val targetLang = settingsManager.targetLanguage
                val result = if (com.example.BuildConfig.GEMINI_API_KEY.isNotEmpty()) {
                    translationService.translateGemini(text, targetLang)
                } else {
                    translationService.translateGoogle(text, targetLang)
                }

                if (result is TranslationResult.Success) {
                    lastTranslatedText = result.translatedText
                    detectedLanguage = result.detectedLanguage
                    translationState.value = result.translatedText

                    // Save to Room history
                    try {
                        historyRepository.insert(
                            TranslationHistory(
                                originalText = text,
                                translatedText = result.translatedText,
                                sourceLanguage = result.detectedLanguage.uppercase(Locale.ROOT)
                            )
                        )
                    } catch (e: Exception) {
                        Log.e("SubtitleService", "Failed to save history to Room", e)
                    }

                    // TTS speak in target language if translation voice is enabled and not muted
                    val isVoiceOn = settingsManager.isVoiceEnabled && isTranslatingState.value && !isMutedState.value
                    if (isVoiceOn) {
                        try {
                            ttsManager.speak(
                                result.translatedText,
                                languageCode = targetLang,
                                speed = settingsManager.speechSpeed,
                                pitch = settingsManager.speechPitch,
                                gender = settingsManager.voiceGender,
                                volume = settingsManager.translationVoiceVolume
                            )
                        } catch (e: Exception) {
                            Log.e("SubtitleService", "TTS speak failed", e)
                        }
                    }
                } else if (result is TranslationResult.Error) {
                    translationState.value = "Translation Error: ${result.message}"
                }
            } catch (e: Exception) {
                Log.e("SubtitleService", "Error in processNewSubtitle coroutine", e)
                translationState.value = "Translation Error: ${e.localizedMessage}"
            }
        }
    }

    private fun applyAudioSettings() {
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            // Unmute music stream so video song is audible
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, 0)
            } else {
                @Suppress("DEPRECATION")
                audioManager.setStreamMute(AudioManager.STREAM_MUSIC, false)
            }
            val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val vol = settingsManager.originalVideoVolume.coerceIn(1, maxVol)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxVol, AudioManager.FLAG_SHOW_UI)
            
            val isVoiceOn = settingsManager.isVoiceEnabled && isTranslatingState.value && !isMutedState.value
            if (!isVoiceOn) {
                ttsManager.stop()
            }
        } catch (e: Exception) {
            Log.e("SubtitleService", "Error in applyAudioSettings", e)
        }
    }

    private fun isDuplicate(newText: String, lastText: String): Boolean {
        if (newText.isEmpty() || lastText.isEmpty()) return false
        val t1 = newText.trim().lowercase()
        val t2 = lastText.trim().lowercase()
        if (t1 == t2) return true
        
        // Compute Levenshtein distance similarity
        val distance = levenshteinDistance(t1, t2)
        val maxLength = maxOf(t1.length, t2.length)
        val similarity = 1.0 - (distance.toDouble() / maxLength.toDouble())
        return similarity > 0.82 // robust similarity threshold
    }

    private fun levenshteinDistance(s1: String, s2: String): Int {
        val dp = IntArray(s2.length + 1) { it }
        for (i in 1..s1.length) {
            var prev = dp[0]
            dp[0] = i
            for (j in 1..s2.length) {
                val temp = dp[j]
                if (s1[i - 1] == s2[j - 1]) {
                    dp[j] = prev
                } else {
                    dp[j] = minOf(dp[j - 1], dp[j], prev) + 1
                }
                prev = temp
            }
        }
        return dp[s2.length]
    }

    private fun showSubtitleOverlay() {
        if (subtitleOverlayView != null) return

        try {
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                y = 100
            }

            val lifecycleOwner = ServiceLifecycleOwner().apply {
                performRestore(null)
                handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
                handleLifecycleEvent(Lifecycle.Event.ON_START)
                handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
            }

            val composeView = ComposeView(this).apply {
                setViewTreeLifecycleOwner(lifecycleOwner)
                setViewTreeViewModelStoreOwner(lifecycleOwner)
                setViewTreeSavedStateRegistryOwner(lifecycleOwner)
                setContent {
                    SubtitleOverlayContent(
                        subtitleFlow = subtitleState,
                        translationFlow = translationState,
                        isTranslatingFlow = isTranslatingState,
                        debugStatusFlow = debugStatusState,
                        debugRegionSizeFlow = debugRegionSizeState,
                        debugRegionCoordsFlow = debugRegionCoordsState,
                        debugCaptureFlow = debugCaptureState
                    )
                }
            }

            val parentLayout = FrameLayout(this).apply {
                setViewTreeLifecycleOwner(lifecycleOwner)
                setViewTreeViewModelStoreOwner(lifecycleOwner)
                setViewTreeSavedStateRegistryOwner(lifecycleOwner)
                addView(composeView)
            }

            windowManager?.addView(parentLayout, params)
            subtitleOverlayView = parentLayout
        } catch (e: Exception) {
            Log.e("SubtitleService", "Error showing subtitle overlay", e)
            subtitleOverlayView = null
        }
    }

    private fun removeSubtitleOverlay() {
        subtitleOverlayView?.let { view ->
            if (view.isAttachedToWindow) {
                try {
                    windowManager?.removeView(view)
                } catch (e: Exception) {
                    Log.e("SubtitleService", "Error removing subtitle overlay", e)
                }
            }
            subtitleOverlayView = null
        }
    }

    private fun showSelectionOverlay() {
        if (selectionOverlayView != null) return

        removeSubtitleOverlay()

        try {
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            )

            val lifecycleOwner = ServiceLifecycleOwner().apply {
                performRestore(null)
                handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
                handleLifecycleEvent(Lifecycle.Event.ON_START)
                handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
            }

            val composeView = ComposeView(this).apply {
                setViewTreeLifecycleOwner(lifecycleOwner)
                setViewTreeViewModelStoreOwner(lifecycleOwner)
                setViewTreeSavedStateRegistryOwner(lifecycleOwner)
                setContent {
                    CaptionAreaSelectionScreen(
                        settingsManager = settingsManager,
                        onSave = { l, t, r, b ->
                            settingsManager.customRectLeft = l
                            settingsManager.customRectTop = t
                            settingsManager.customRectRight = r
                            settingsManager.customRectBottom = b
                            
                            removeSelectionOverlay()
                            if (isTranslatingState.value) {
                                showSubtitleOverlay()
                            }
                        },
                        onCancel = {
                            removeSelectionOverlay()
                            if (isTranslatingState.value) {
                                showSubtitleOverlay()
                            }
                        }
                    )
                }
            }

            val parentLayout = FrameLayout(this).apply {
                setViewTreeLifecycleOwner(lifecycleOwner)
                setViewTreeViewModelStoreOwner(lifecycleOwner)
                setViewTreeSavedStateRegistryOwner(lifecycleOwner)
                addView(composeView)
            }

            windowManager?.addView(parentLayout, params)
            selectionOverlayView = parentLayout
        } catch (e: Exception) {
            Log.e("SubtitleService", "Error showing selection overlay", e)
            selectionOverlayView = null
            if (isTranslatingState.value) {
                showSubtitleOverlay()
            }
        }
    }

    private fun removeSelectionOverlay() {
        selectionOverlayView?.let { view ->
            if (view.isAttachedToWindow) {
                try {
                    windowManager?.removeView(view)
                } catch (e: Exception) {
                    Log.e("SubtitleService", "Error removing selection overlay", e)
                }
            }
            selectionOverlayView = null
        }
    }

    private fun toggleMenuOverlay() {
        if (menuOverlayView != null) {
            removeMenuOverlay()
        } else {
            showMenuOverlay()
        }
    }

    private fun showMenuOverlay() {
        if (menuOverlayView != null) return

        try {
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            )

            val lifecycleOwner = ServiceLifecycleOwner().apply {
                performRestore(null)
                handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
                handleLifecycleEvent(Lifecycle.Event.ON_START)
                handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
            }

            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val maxMusicVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val currentMusicVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)

            val composeView = ComposeView(this).apply {
                setViewTreeLifecycleOwner(lifecycleOwner)
                setViewTreeViewModelStoreOwner(lifecycleOwner)
                setViewTreeSavedStateRegistryOwner(lifecycleOwner)
                setContent {
                    TranslationMenuPopupContent(
                        isTranslatingFlow = isTranslatingState,
                        settingsManager = settingsManager,
                        maxMusicVolume = maxMusicVolume,
                        currentMusicVolume = currentMusicVolume,
                        onStartTranslation = {
                            startTranslationSession()
                            removeMenuOverlay()
                        },
                        onStopTranslation = {
                            stopTranslationSession()
                            removeMenuOverlay()
                        },
                        onSkip = {
                            ttsManager.skip()
                        },
                        onSelectCaptionArea = {
                            showSelectionOverlay()
                            removeMenuOverlay()
                        },
                        onToggleOcrDebug = { visible ->
                            toggleOcrDebugVisibility(visible)
                        },
                        ocrDebugVisible = settingsManager.isOcrDebugVisible,
                        onOriginalVolumeChanged = { newVol ->
                            settingsManager.originalVideoVolume = newVol
                            applyAudioSettings()
                        },
                        onTranslationVolumeChanged = { newVol ->
                            settingsManager.translationVoiceVolume = newVol
                            applyAudioSettings()
                        },
                        onClose = {
                            removeMenuOverlay()
                        },
                        onOpenSettings = {
                            try {
                                val intent = Intent(this@SubtitleAccessibilityService, MainActivity::class.java).apply {
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                }
                                startActivity(intent)
                                removeMenuOverlay()
                            } catch (e: Exception) {
                                Log.e("SubtitleService", "Error opening settings screen", e)
                            }
                        }
                    )
                }
            }

            val parentLayout = FrameLayout(this).apply {
                setViewTreeLifecycleOwner(lifecycleOwner)
                setViewTreeViewModelStoreOwner(lifecycleOwner)
                setViewTreeSavedStateRegistryOwner(lifecycleOwner)
                addView(composeView)
            }

            windowManager?.addView(parentLayout, params)
            menuOverlayView = parentLayout
        } catch (e: Exception) {
            Log.e("SubtitleService", "Error showing menu overlay", e)
            menuOverlayView = null
        }
    }

    private fun removeMenuOverlay() {
        menuOverlayView?.let { view ->
            if (view.isAttachedToWindow) {
                try {
                    windowManager?.removeView(view)
                } catch (e: Exception) {
                    Log.e("SubtitleService", "Error removing menu overlay", e)
                }
            }
            menuOverlayView = null
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        try {
            super.onDestroy()
            serviceScope.cancel()
            ttsManager.shutdown()
            textRecognizer?.close()
            removeSubtitleOverlay()
            removeSelectionOverlay()
            removeMenuOverlay()

            // Restore original audio on destroy
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
            } else {
                @Suppress("DEPRECATION")
                audioManager.setStreamMute(AudioManager.STREAM_MUSIC, false)
            }
        } catch (e: Exception) {
            Log.e("SubtitleService", "Error in onDestroy", e)
        }
    }
}

/**
 * ServiceLifecycleOwner enables Compose inside Windows/Services
 */
class ServiceLifecycleOwner : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    init {
        lifecycleRegistry.currentState = Lifecycle.State.INITIALIZED
    }

    fun handleLifecycleEvent(event: Lifecycle.Event) {
        lifecycleRegistry.handleLifecycleEvent(event)
    }

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry

    fun performRestore(savedState: Bundle?) {
        savedStateRegistryController.performRestore(savedState)
    }
}

enum class ActiveHandle {
    NONE, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT, CENTER
}

/**
 * The full-screen overlay for drawing and resizing the caption area
 */
@Composable
fun CaptionAreaSelectionScreen(
    settingsManager: SettingsManager,
    onSave: (left: Float, top: Float, right: Float, bottom: Float) -> Unit,
    onCancel: () -> Unit
) {
    var relativeLeft by remember { mutableStateOf(settingsManager.customRectLeft) }
    var relativeTop by remember { mutableStateOf(settingsManager.customRectTop) }
    var relativeRight by remember { mutableStateOf(settingsManager.customRectRight) }
    var relativeBottom by remember { mutableStateOf(settingsManager.customRectBottom) }

    var activeHandle by remember { mutableStateOf(ActiveHandle.NONE) }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Transparent)
    ) {
        val widthPx = constraints.maxWidth.toFloat()
        val heightPx = constraints.maxHeight.toFloat()

        fun dist(x1: Float, y1: Float, x2: Float, y2: Float): Float {
            val dx = x1 - x2
            val dy = y1 - y2
            return kotlin.math.sqrt(dx * dx + dy * dy)
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            val x = offset.x
                            val y = offset.y
                            val l = relativeLeft * widthPx
                            val t = relativeTop * heightPx
                            val r = relativeRight * widthPx
                            val b = relativeBottom * heightPx

                            val threshold = 70f // interactive threshold in pixels

                            activeHandle = when {
                                dist(x, y, l, t) < threshold -> ActiveHandle.TOP_LEFT
                                dist(x, y, r, t) < threshold -> ActiveHandle.TOP_RIGHT
                                dist(x, y, l, b) < threshold -> ActiveHandle.BOTTOM_LEFT
                                dist(x, y, r, b) < threshold -> ActiveHandle.BOTTOM_RIGHT
                                x in l..r && y in t..b -> ActiveHandle.CENTER
                                else -> ActiveHandle.NONE
                            }
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            val dx = dragAmount.x / widthPx
                            val dy = dragAmount.y / heightPx

                            when (activeHandle) {
                                ActiveHandle.TOP_LEFT -> {
                                    relativeLeft = (relativeLeft + dx).coerceIn(0f, relativeRight - 0.05f)
                                    relativeTop = (relativeTop + dy).coerceIn(0f, relativeBottom - 0.05f)
                                }
                                ActiveHandle.TOP_RIGHT -> {
                                    relativeRight = (relativeRight + dx).coerceIn(relativeLeft + 0.05f, 1f)
                                    relativeTop = (relativeTop + dy).coerceIn(0f, relativeBottom - 0.05f)
                                }
                                ActiveHandle.BOTTOM_LEFT -> {
                                    relativeLeft = (relativeLeft + dx).coerceIn(0f, relativeRight - 0.05f)
                                    relativeBottom = (relativeBottom + dy).coerceIn(relativeTop + 0.05f, 1f)
                                }
                                ActiveHandle.BOTTOM_RIGHT -> {
                                    relativeRight = (relativeRight + dx).coerceIn(relativeLeft + 0.05f, 1f)
                                    relativeBottom = (relativeBottom + dy).coerceIn(relativeTop + 0.05f, 1f)
                                }
                                ActiveHandle.CENTER -> {
                                    val w = relativeRight - relativeLeft
                                    val h = relativeBottom - relativeTop

                                    val newLeft = (relativeLeft + dx).coerceIn(0f, 1f - w)
                                    val newTop = (relativeTop + dy).coerceIn(0f, 1f - h)

                                    relativeLeft = newLeft
                                    relativeTop = newTop
                                    relativeRight = newLeft + w
                                    relativeBottom = newTop + h
                                }
                                ActiveHandle.NONE -> {}
                            }
                        },
                        onDragEnd = {
                            activeHandle = ActiveHandle.NONE
                        }
                    )
                }
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val l = relativeLeft * size.width
                val t = relativeTop * size.height
                val r = relativeRight * size.width
                val b = relativeBottom * size.height

                // 1. Semi-transparent overlay
                drawRect(
                    color = Color.Black.copy(alpha = 0.62f),
                    size = size
                )

                // 2. Clear selected rectangle
                drawRect(
                    color = Color.Transparent,
                    topLeft = Offset(l, t),
                    size = Size(r - l, b - t),
                    blendMode = BlendMode.Clear
                )

                // 3. Draw bright border
                drawRect(
                    color = Color(0xFF00C853),
                    topLeft = Offset(l, t),
                    size = Size(r - l, b - t),
                    style = Stroke(width = 3.dp.toPx())
                )

                // 4. Draw corner circles
                val handleRadius = 12.dp.toPx()
                drawCircle(color = Color(0xFF00C853), radius = handleRadius, center = Offset(l, t))
                drawCircle(color = Color(0xFF00C853), radius = handleRadius, center = Offset(r, t))
                drawCircle(color = Color(0xFF00C853), radius = handleRadius, center = Offset(l, b))
                drawCircle(color = Color(0xFF00C853), radius = handleRadius, center = Offset(r, b))
            }
        }

        // Save & Cancel buttons at the bottom
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E2E).copy(alpha = 0.9f)),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = "Drag corners to resize | Drag center to move",
                    color = Color.White,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally)
            ) {
                Button(
                    onClick = onCancel,
                    colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray),
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier.weight(1f).height(48.dp)
                ) {
                    Icon(imageVector = Icons.Default.Close, contentDescription = "Cancel", tint = Color.White)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Cancel", color = Color.White)
                }

                Button(
                    onClick = { onSave(relativeLeft, relativeTop, relativeRight, relativeBottom) },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00C853)),
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier.weight(1f).height(48.dp)
                ) {
                    Icon(imageVector = Icons.Default.Check, contentDescription = "Confirm", tint = Color.White)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Confirm", color = Color.White)
                }
            }
        }
    }
}

/**
 * Clean, translucent click-through subtitle overlay displaying original and translated texts
 */
@Composable
fun SubtitleOverlayContent(
    subtitleFlow: StateFlow<String>,
    translationFlow: StateFlow<String>,
    isTranslatingFlow: StateFlow<Boolean>,
    debugStatusFlow: StateFlow<String>,
    debugRegionSizeFlow: StateFlow<String>,
    debugRegionCoordsFlow: StateFlow<String>,
    debugCaptureFlow: StateFlow<String>
) {
    val subtitle by subtitleFlow.collectAsState()
    val translation by translationFlow.collectAsState()
    val isTranslating by isTranslatingFlow.collectAsState()
    val debugStatus by debugStatusFlow.collectAsState()
    val regionSize by debugRegionSizeFlow.collectAsState()
    val regionCoords by debugRegionCoordsFlow.collectAsState()
    val captureState by debugCaptureFlow.collectAsState()

    var isMinimized by remember { mutableStateOf(false) }

    if (isTranslating) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFF1B5E20).copy(alpha = 0.95f)
                ),
                border = BorderStroke(2.dp, Color(0xFF00E676)),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(4.dp)
            ) {
                Column(
                    modifier = Modifier.padding(10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // Header with Minimize/Expand Button
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (isMinimized) "OCR SUBTITLES (Minimized)" else "OCR DEBUG PIPELINE",
                            color = Color(0xFFB9F6CA),
                            fontSize = 11.sp,
                            style = androidx.compose.ui.text.TextStyle(
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.2.sp
                            )
                        )
                        IconButton(
                            onClick = { isMinimized = !isMinimized },
                            modifier = Modifier
                                .size(28.dp)
                                .testTag("minimize_debug_button")
                        ) {
                            Icon(
                                imageVector = if (isMinimized) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                                contentDescription = if (isMinimized) "Expand" else "Minimize",
                                tint = Color(0xFF00E676),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    if (!isMinimized) {
                        // Region Stats Row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(text = "Size: $regionSize", color = Color(0xFFC8E6C9), fontSize = 10.sp)
                            Text(text = "Coords: $regionCoords", color = Color(0xFFC8E6C9), fontSize = 10.sp)
                        }

                        // Capture State Row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(text = "Capture: $captureState", color = Color(0xFF81C784), fontSize = 10.sp)
                            Text(text = "Status: $debugStatus", color = Color(0xFFFFD54F), fontSize = 10.sp)
                        }

                        // Divider
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(Color(0xFF2E7D32))
                        )

                        // English OCR
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Text(
                                text = "OCR [EN]:",
                                color = Color(0xFFE8F5E9),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.width(68.dp)
                            )
                            val isNoText = subtitle.isEmpty() || subtitle.startsWith("No text detected")
                            Text(
                                text = if (isNoText) subtitle.ifEmpty { "No text detected" } else subtitle,
                                color = if (isNoText) Color(0xFFFF8A80) else Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.weight(1f)
                            )
                        }

                        // Divider
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(Color(0xFF2E7D32))
                        )
                    }

                    // Tamil Translation (always visible even when minimized for clean viewing)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Text(
                            text = "TRANS [TA]:",
                            color = Color(0xFFE8F5E9),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.width(68.dp)
                        )
                        val isNoText = subtitle.isEmpty() || subtitle.startsWith("No text detected")
                        val displayTranslation = if (translation.startsWith("Translation Error")) {
                            translation
                        } else if (isNoText) {
                            "—"
                        } else if (translation.isEmpty()) {
                            "Waiting for translation..."
                        } else {
                            translation
                        }

                        val textColor = when {
                            displayTranslation.startsWith("Translation Error") -> Color(0xFFFFB74D)
                            displayTranslation == "—" -> Color(0xFFFF8A80)
                            displayTranslation == "Waiting for translation..." -> Color(0xFFA5D6A7)
                            else -> Color(0xFFCCFF90)
                        }

                        Text(
                            text = displayTranslation,
                            color = textColor,
                            fontSize = if (isMinimized) 14.sp else 15.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}

/**
 * A beautiful popup menu that opens when clicking the system accessibility shortcut.
 * Dismisses when tapping outside the central card.
 */
@Composable
fun TranslationMenuPopupContent(
    isTranslatingFlow: StateFlow<Boolean>,
    settingsManager: SettingsManager,
    maxMusicVolume: Int,
    currentMusicVolume: Int,
    onStartTranslation: () -> Unit,
    onStopTranslation: () -> Unit,
    onSkip: () -> Unit,
    onSelectCaptionArea: () -> Unit,
    onToggleOcrDebug: (Boolean) -> Unit,
    ocrDebugVisible: Boolean,
    onOriginalVolumeChanged: (Int) -> Unit,
    onTranslationVolumeChanged: (Float) -> Unit,
    onClose: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val isTranslating by isTranslatingFlow.collectAsState()
    
    var origVolume by remember { mutableStateOf(settingsManager.originalVideoVolume) }
    var transVolume by remember { mutableStateOf(settingsManager.translationVoiceVolume) }
    var targetLanguage by remember { mutableStateOf(settingsManager.targetLanguage) }

    // Full-screen backdrop that dismisses the popup on click
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                onClose()
            },
        contentAlignment = Alignment.Center
    ) {
        // Translation Menu Card
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = Color(0xFF161622)
            ),
            border = BorderStroke(1.5.dp, Color(0xFF00C853).copy(alpha = 0.6f)),
            modifier = Modifier
                .width(320.dp)
                .padding(16.dp)
                // Consume clicks inside the card so they don't dismiss the popup
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    enabled = true,
                    onClick = {}
                )
                .testTag("translation_menu_popup_card")
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Translation Menu",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = Color(0xFF00C853)
                    )
                    IconButton(
                        onClick = onClose,
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close Menu",
                            tint = Color.LightGray
                        )
                    }
                }

                // Start/Stop Session
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = onStartTranslation,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (!isTranslating) Color(0xFF00C853) else Color.Gray
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .height(40.dp)
                            .testTag("popup_start_button"),
                        shape = RoundedCornerShape(8.dp),
                        enabled = !isTranslating
                    ) {
                        Icon(imageVector = Icons.Default.PlayArrow, contentDescription = "Start", modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Start", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }

                    Button(
                        onClick = onStopTranslation,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isTranslating) Color.Red else Color.Gray
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .height(40.dp)
                            .testTag("popup_stop_button"),
                        shape = RoundedCornerShape(8.dp),
                        enabled = isTranslating
                    ) {
                        Icon(imageVector = Icons.Default.Stop, contentDescription = "Stop", modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Stop", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }

                // Skip Current Dialogue Button
                Button(
                    onClick = onSkip,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE65100)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(40.dp)
                        .testTag("popup_skip_button"),
                    shape = RoundedCornerShape(8.dp),
                    enabled = isTranslating
                ) {
                    Icon(imageVector = Icons.Default.SkipNext, contentDescription = "Skip", modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Skip Current Dialogue", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }

                // Custom Screen Selection
                Button(
                    onClick = onSelectCaptionArea,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E2E3E)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(40.dp)
                        .testTag("popup_select_area_button"),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(imageVector = Icons.Default.CropFree, contentDescription = "Crop", modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Custom Screen Selection", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }

                // Show OCR Debug Pipeline Toggle
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF2E2E3E))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(imageVector = Icons.Default.Visibility, contentDescription = "OCR Pipeline", tint = Color(0xFF00C853), modifier = Modifier.size(16.dp))
                        Text("Show OCR Pipeline", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                    Switch(
                        checked = ocrDebugVisible,
                        onCheckedChange = onToggleOcrDebug,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = Color(0xFF00C853),
                            uncheckedThumbColor = Color.LightGray,
                            uncheckedTrackColor = Color.DarkGray
                        ),
                        modifier = Modifier.testTag("ocr_debug_switch")
                    )
                }

                HorizontalDivider(color = Color.White.copy(alpha = 0.08f))

                // Original Video Volume Slider
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(imageVector = Icons.AutoMirrored.Filled.VolumeUp, contentDescription = "Original Vol", tint = Color.LightGray, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Original Video Volume", fontSize = 12.sp, color = Color.LightGray)
                        }
                        Text("$origVolume / $maxMusicVolume", fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.Bold)
                    }
                    Slider(
                        value = origVolume.toFloat(),
                        onValueChange = {
                            val newVol = it.toInt()
                            origVolume = newVol
                            onOriginalVolumeChanged(newVol)
                        },
                        valueRange = 0f..maxMusicVolume.toFloat(),
                        colors = SliderDefaults.colors(
                            activeTrackColor = Color(0xFF00C853),
                            inactiveTrackColor = Color.DarkGray,
                            thumbColor = Color(0xFF00C853)
                        ),
                        modifier = Modifier.height(24.dp)
                    )
                }

                // Translation Voice Volume Slider
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(imageVector = Icons.Default.Hearing, contentDescription = "Translation Vol", tint = Color.LightGray, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Translation Voice Volume", fontSize = 12.sp, color = Color.LightGray)
                        }
                        Text("${(transVolume * 100).toInt()}%", fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.Bold)
                    }
                    Slider(
                        value = transVolume,
                        onValueChange = {
                            transVolume = it
                            settingsManager.translationVoiceVolume = it
                            onTranslationVolumeChanged(it)
                        },
                        valueRange = 0f..1.0f,
                        colors = SliderDefaults.colors(
                            activeTrackColor = Color(0xFF00C853),
                            inactiveTrackColor = Color.DarkGray,
                            thumbColor = Color(0xFF00C853)
                        ),
                        modifier = Modifier.height(24.dp)
                    )
                }

                HorizontalDivider(color = Color.White.copy(alpha = 0.08f))

                 // Language selection
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Target Language", fontSize = 12.sp, color = Color.LightGray)
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            // Tamil
                            Box(
                                modifier = Modifier
                                    .background(
                                        if (targetLanguage == "ta") Color(0xFF00C853).copy(alpha = 0.15f) else Color(0xFF2E2E3E),
                                        RoundedCornerShape(6.dp)
                                    )
                                    .border(
                                        1.dp,
                                        if (targetLanguage == "ta") Color(0xFF00C853) else Color.Transparent,
                                        RoundedCornerShape(6.dp)
                                    )
                                    .clickable {
                                        targetLanguage = "ta"
                                        settingsManager.targetLanguage = "ta"
                                    }
                                    .padding(horizontal = 8.dp, vertical = 6.dp)
                            ) {
                                Text("Tamil", color = if (targetLanguage == "ta") Color(0xFF00C853) else Color.White, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                            }
                            // Tanglish
                            Box(
                                modifier = Modifier
                                    .background(
                                        if (targetLanguage == "tanglish") Color(0xFF00C853).copy(alpha = 0.15f) else Color(0xFF2E2E3E),
                                        RoundedCornerShape(6.dp)
                                    )
                                    .border(
                                        1.dp,
                                        if (targetLanguage == "tanglish") Color(0xFF00C853) else Color.Transparent,
                                        RoundedCornerShape(6.dp)
                                    )
                                    .clickable {
                                        targetLanguage = "tanglish"
                                        settingsManager.targetLanguage = "tanglish"
                                    }
                                    .padding(horizontal = 8.dp, vertical = 6.dp)
                            ) {
                                Text("Tanglish", color = if (targetLanguage == "tanglish") Color(0xFF00C853) else Color.White, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            // Hindi
                            Box(
                                modifier = Modifier
                                    .background(
                                        if (targetLanguage == "hi") Color(0xFF00C853).copy(alpha = 0.15f) else Color(0xFF2E2E3E),
                                        RoundedCornerShape(6.dp)
                                    )
                                    .border(
                                        1.dp,
                                        if (targetLanguage == "hi") Color(0xFF00C853) else Color.Transparent,
                                        RoundedCornerShape(6.dp)
                                    )
                                    .clickable {
                                        targetLanguage = "hi"
                                        settingsManager.targetLanguage = "hi"
                                    }
                                    .padding(horizontal = 8.dp, vertical = 6.dp)
                            ) {
                                Text("Hindi", color = if (targetLanguage == "hi") Color(0xFF00C853) else Color.White, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                            }
                            // Telugu
                            Box(
                                modifier = Modifier
                                    .background(
                                        if (targetLanguage == "te") Color(0xFF00C853).copy(alpha = 0.15f) else Color(0xFF2E2E3E),
                                        RoundedCornerShape(6.dp)
                                    )
                                    .border(
                                        1.dp,
                                        if (targetLanguage == "te") Color(0xFF00C853) else Color.Transparent,
                                        RoundedCornerShape(6.dp)
                                    )
                                    .clickable {
                                        targetLanguage = "te"
                                        settingsManager.targetLanguage = "te"
                                    }
                                    .padding(horizontal = 8.dp, vertical = 6.dp)
                            ) {
                                Text("Telugu", color = if (targetLanguage == "te") Color(0xFF00C853) else Color.White, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                }

                HorizontalDivider(color = Color.White.copy(alpha = 0.08f))

                // Configure Settings button
                Button(
                    onClick = onOpenSettings,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF252538)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(36.dp)
                        .testTag("popup_settings_button"),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Icon(imageVector = Icons.Default.Settings, contentDescription = "Settings", tint = Color(0xFF00C853), modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Settings", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                }
            }
        }
    }
}
