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
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.media.AudioManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
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

    private var windowManager: WindowManager? = null

    // Floating overlay views
    private var overlayView: FrameLayout? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var selectionOverlayView: FrameLayout? = null

    // State flows for compose overlay
    private val subtitleState = MutableStateFlow("")
    private val translationState = MutableStateFlow("")
    private val isTranslatingState = MutableStateFlow(false)
    private val isMutedState = MutableStateFlow(false)

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
            
            // Show initial floating overlay (will be collapsed or expanded depending on state)
            showFloatingOverlay()
            
            // Apply initial audio settings
            applyAudioSettings()
        } catch (e: Exception) {
            Log.e("SubtitleService", "Error in onCreate", e)
        }
    }

    override fun onServiceConnected() {
        try {
            super.onServiceConnected()
            Log.d("SubtitleService", "Service Connected")
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
                if (action == "START") {
                    startTranslationSession()
                } else if (action == "STOP") {
                    stopTranslationSession()
                }
            }
        } catch (e: Exception) {
            Log.e("SubtitleService", "Error in onStartCommand", e)
        }
        return START_STICKY
    }

    private fun startTranslationSession() {
        if (isTranslatingState.value) return
        settingsManager.isTranslatorActive = true
        isTranslatingState.value = true
        
        applyAudioSettings()
        showFloatingOverlay()
        
        // Start OCR periodic scanning unconditionally
        ocrHandler.removeCallbacks(ocrRunnable)
        ocrHandler.postDelayed(ocrRunnable, 500)
        
        Log.d("SubtitleService", "Translation Session Started")
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
        removeFloatingOverlay()

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

    private fun findTextNodes(node: AccessibilityNodeInfo, list: MutableList<String>) {
        try {
            if (node.text != null && node.text.toString().isNotBlank()) {
                list.add(node.text.toString())
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i)
                if (child != null) {
                    try {
                        findTextNodes(child, list)
                    } finally {
                        try {
                            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                                @Suppress("DEPRECATION")
                                child.recycle()
                            }
                        } catch (e: Exception) {
                            // Ignore recycle error
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("SubtitleService", "Error in findTextNodes", e)
        }
    }

    private fun captureAndRecognizeText() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                Log.d("SubtitleService", "Initiating takeScreenshot capture...")
                takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                    override fun onSuccess(screenshotResult: ScreenshotResult) {
                        try {
                            val buffer = screenshotResult.hardwareBuffer
                            val colorSpace = screenshotResult.colorSpace
                            val bitmap = Bitmap.wrapHardwareBuffer(buffer, colorSpace)

                            if (bitmap != null) {
                                // Convert to software bitmap for ML Kit while keeping the buffer open
                                val softwareBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false)
                                // Close the hardware buffer after software copy is made to release hardware resources safely
                                buffer.close()
                                
                                val width = softwareBitmap.width
                                val height = softwareBitmap.height
                                
                                // Calculate cropping coordinates from relative crop settings
                                val left = (settingsManager.customRectLeft * width).toInt().coerceIn(0, width - 1)
                                val top = (settingsManager.customRectTop * height).toInt().coerceIn(0, height - 1)
                                val right = (settingsManager.customRectRight * width).toInt().coerceIn(left + 1, width)
                                val bottom = (settingsManager.customRectBottom * height).toInt().coerceIn(top + 1, height)
                                
                                val cropWidth = right - left
                                val cropHeight = bottom - top
                                
                                Log.d("SubtitleService", "Screenshot onSuccess. Size: ${width}x${height}. Crop: l=$left, t=$top, r=$right, b=$bottom (width=$cropWidth, height=$cropHeight)")
                                
                                if (cropWidth > 0 && cropHeight > 0) {
                                    val croppedBitmap = Bitmap.createBitmap(softwareBitmap, left, top, cropWidth, cropHeight)
                                    runOcrOnBitmap(croppedBitmap)
                                    croppedBitmap.recycle()
                                } else {
                                    Log.w("SubtitleService", "Invalid crop dimensions, running OCR on entire screen")
                                    runOcrOnBitmap(softwareBitmap)
                                }
                                softwareBitmap.recycle()
                            } else {
                                Log.e("SubtitleService", "Wrapped hardware bitmap is null!")
                                buffer.close()
                            }
                        } catch (e: Exception) {
                            Log.e("SubtitleService", "Error processing screenshot onSuccess", e)
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        Log.e("SubtitleService", "Screenshot capture onFailure called. ErrorCode: $errorCode")
                    }
                })
            } catch (e: Exception) {
                Log.e("SubtitleService", "Error requesting takeScreenshot", e)
            }
        } else {
            Log.w("SubtitleService", "Screenshot capture not supported on SDK versions below R (API 30)")
        }
    }

    private fun runOcrOnBitmap(bitmap: Bitmap) {
        try {
            val image = InputImage.fromBitmap(bitmap, 0)
            val recognizer = textRecognizer ?: TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            
            recognizer.process(image)
                .addOnSuccessListener { visionText ->
                    try {
                        val subtitleLines = mutableListOf<String>()
                        for (block in visionText.textBlocks) {
                            for (line in block.lines) {
                                subtitleLines.add(line.text)
                            }
                        }

                        val combinedText = subtitleLines.joinToString(" ").trim()
                        Log.d("SubtitleService", "OCR raw detected text: '$combinedText'")
                        
                        if (combinedText.isNotEmpty()) {
                            if (combinedText.length in 3..200) {
                                processNewSubtitle(combinedText)
                            } else {
                                Log.d("SubtitleService", "Detected text ignored due to length constraints (${combinedText.length} chars)")
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("SubtitleService", "Error processing OCR vision text blocks", e)
                    }
                }
                .addOnFailureListener { e ->
                    Log.e("SubtitleService", "OCR Text recognition failed", e)
                }
        } catch (e: Exception) {
            Log.e("SubtitleService", "Error running OCR on bitmap", e)
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

                    // TTS speak in Tamil (or target lang) if translation voice is enabled and not muted
                    val isVoiceOn = settingsManager.isVoiceEnabled && !isMutedState.value
                    if (isVoiceOn) {
                        try {
                            ttsManager.speak(
                                result.translatedText,
                                speed = settingsManager.speechSpeed,
                                pitch = settingsManager.speechPitch,
                                gender = settingsManager.voiceGender,
                                volume = settingsManager.translationVoiceVolume
                            )
                        } catch (e: Exception) {
                            Log.e("SubtitleService", "TTS speak failed", e)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("SubtitleService", "Error in processNewSubtitle coroutine", e)
            }
        }
    }

    private fun applyAudioSettings() {
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            // Set STREAM_MUSIC (original video volume) strictly to user's chosen value from the slider
            val vol = settingsManager.originalVideoVolume
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, vol, 0)
            
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

    private fun showFloatingOverlay() {
        if (overlayView != null) return
        if (!android.provider.Settings.canDrawOverlays(this)) return

        try {
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) 
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY 
                else 
                    WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                val displayMetrics = resources.displayMetrics
                x = (displayMetrics.widthPixels - 340) / 2
                y = displayMetrics.heightPixels - 700
            }

            overlayParams = params

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
                    FloatingOverlayContent(
                        subtitleFlow = subtitleState,
                        translationFlow = translationState,
                        isTranslatingFlow = isTranslatingState,
                        isMutedFlow = isMutedState,
                        settingsManager = settingsManager,
                        onToggleMute = { 
                            isMutedState.value = !isMutedState.value 
                            applyAudioSettings()
                        },
                        onStartTranslation = { startTranslationSession() },
                        onPauseTranslation = { pauseTranslationSession() },
                        onStop = { stopTranslationSession() },
                        onDrag = { dx, dy ->
                            params.x += dx
                            params.y += dy
                            try {
                                windowManager?.updateViewLayout(overlayView, params)
                            } catch (e: Exception) {
                                Log.e("SubtitleService", "Error updating overlay layout", e)
                            }
                        },
                        onSelectCaptionArea = {
                            showSelectionOverlay()
                        },
                        onOpenSettings = {
                            try {
                                val intent = Intent(this@SubtitleAccessibilityService, MainActivity::class.java).apply {
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                }
                                startActivity(intent)
                            } catch (e: Exception) {
                                Log.e("SubtitleService", "Error opening settings screen", e)
                            }
                        },
                        maxMusicVolume = maxMusicVolume,
                        currentMusicVolume = currentMusicVolume,
                        onOriginalVolumeChanged = { newVol ->
                            settingsManager.originalVideoVolume = newVol
                            applyAudioSettings()
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

            overlayView = parentLayout
            windowManager?.addView(parentLayout, params)
        } catch (e: Exception) {
            Log.e("SubtitleService", "Error showing floating overlay", e)
            overlayView = null
        }
    }

    private fun removeFloatingOverlay() {
        overlayView?.let {
            try {
                windowManager?.removeView(it)
            } catch (e: Exception) {
                Log.e("SubtitleService", "Error removing floating overlay", e)
            }
            overlayView = null
        }
    }

    private fun showSelectionOverlay() {
        if (selectionOverlayView != null) return
        if (!android.provider.Settings.canDrawOverlays(this)) return

        removeFloatingOverlay()

        try {
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) 
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY 
                else 
                    WindowManager.LayoutParams.TYPE_PHONE,
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
                            showFloatingOverlay()
                        },
                        onCancel = {
                            removeSelectionOverlay()
                            showFloatingOverlay()
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

            selectionOverlayView = parentLayout
            windowManager?.addView(parentLayout, params)
        } catch (e: Exception) {
            Log.e("SubtitleService", "Error showing selection overlay", e)
            selectionOverlayView = null
            showFloatingOverlay()
        }
    }

    private fun removeSelectionOverlay() {
        selectionOverlayView?.let {
            try {
                windowManager?.removeView(it)
            } catch (e: Exception) {
                Log.e("SubtitleService", "Error removing selection overlay", e)
            }
            selectionOverlayView = null
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        try {
            super.onDestroy()
            serviceScope.cancel()
            ttsManager.shutdown()
            textRecognizer?.close()
            removeFloatingOverlay()
            removeSelectionOverlay()

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
 * The Floating Subtitle overlay Composable with Draggability and Integrated Settings Menu
 */
@Composable
fun FloatingOverlayContent(
    subtitleFlow: StateFlow<String>,
    translationFlow: StateFlow<String>,
    isTranslatingFlow: StateFlow<Boolean>,
    isMutedFlow: StateFlow<Boolean>,
    settingsManager: SettingsManager,
    onToggleMute: () -> Unit,
    onStartTranslation: () -> Unit,
    onPauseTranslation: () -> Unit,
    onStop: () -> Unit,
    onDrag: (dx: Int, dy: Int) -> Unit,
    onSelectCaptionArea: () -> Unit,
    onOpenSettings: () -> Unit,
    maxMusicVolume: Int,
    currentMusicVolume: Int,
    onOriginalVolumeChanged: (Int) -> Unit
) {
    val subtitle by subtitleFlow.collectAsState()
    val translation by translationFlow.collectAsState()
    val isTranslating by isTranslatingFlow.collectAsState()
    val isMuted by isMutedFlow.collectAsState()
    
    var isMenuOpen by remember { mutableStateOf(false) }
    
    var origVolume by remember { mutableStateOf(currentMusicVolume) }
    var transVolume by remember { mutableStateOf(settingsManager.translationVoiceVolume) }

    Column(
        modifier = Modifier.wrapContentSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (isMenuOpen) {
            // Popup Menu Card right above the button
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFF12121E).copy(alpha = 0.95f)
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                modifier = Modifier
                    .width(280.dp)
                    .padding(bottom = 8.dp)
                    .shadow(6.dp, RoundedCornerShape(12.dp))
                    .testTag("floating_popup_menu")
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Translator Menu",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF00C853),
                        modifier = Modifier.padding(bottom = 4.dp)
                    )

                    // 1. Start Translation
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .clickable {
                                onStartTranslation()
                                isMenuOpen = false
                            }
                            .background(if (isTranslating) Color(0xFF00C853).copy(alpha = 0.15f) else Color.Transparent)
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = "Start Translation",
                            tint = if (isTranslating) Color(0xFF00C853) else Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Start Translation",
                            color = if (isTranslating) Color(0xFF00C853) else Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    // 2. Pause Translation
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .clickable {
                                onPauseTranslation()
                                isMenuOpen = false
                            }
                            .background(if (!isTranslating && settingsManager.isTranslatorActive) Color.Yellow.copy(alpha = 0.15f) else Color.Transparent)
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Pause,
                            contentDescription = "Pause Translation",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Pause Translation",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    // 3. Stop Translation
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .clickable {
                                onStop()
                                isMenuOpen = false
                            }
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Stop,
                            contentDescription = "Stop Translation",
                            tint = Color.Red,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Stop Translation",
                            color = Color.Red,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    // 4. Custom Screen Selection
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .clickable {
                                onSelectCaptionArea()
                                isMenuOpen = false
                            }
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.CropFree,
                            contentDescription = "Custom Screen Selection",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Custom Screen Selection",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    HorizontalDivider(color = Color.White.copy(alpha = 0.1f))

                    // 5. Original Video Volume Slider
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("🔊 Original Video Volume", fontSize = 11.sp, color = Color.LightGray)
                            Text(origVolume.toString(), fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.Bold)
                        }
                        Slider(
                            value = origVolume.toFloat(),
                            onValueChange = {
                                val newVol = it.toInt()
                                origVolume = newVol
                                onOriginalVolumeChanged(newVol)
                            },
                            valueRange = 0f..maxMusicVolume.toFloat(),
                            modifier = Modifier.height(24.dp),
                            colors = SliderDefaults.colors(
                                activeTrackColor = Color(0xFF00C853),
                                inactiveTrackColor = Color.DarkGray,
                                thumbColor = Color(0xFF00C853)
                            )
                        )
                    }

                    // 6. Translation Voice Volume Slider
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("🗣 Translation Voice Volume", fontSize = 11.sp, color = Color.LightGray)
                            Text("${(transVolume * 100).toInt()}%", fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.Bold)
                        }
                        Slider(
                            value = transVolume,
                            onValueChange = {
                                transVolume = it
                                settingsManager.translationVoiceVolume = it
                            },
                            valueRange = 0f..1.0f,
                            modifier = Modifier.height(24.dp),
                            colors = SliderDefaults.colors(
                                activeTrackColor = Color(0xFF00C853),
                                inactiveTrackColor = Color.DarkGray,
                                thumbColor = Color(0xFF00C853)
                            )
                        )
                    }

                    HorizontalDivider(color = Color.White.copy(alpha = 0.1f))

                    // 7. Settings
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .clickable {
                                onOpenSettings()
                                isMenuOpen = false
                            }
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Settings",
                            tint = Color(0xFF00C853),
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Settings",
                            color = Color(0xFF00C853),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }

        // Green Floating Button
        Box(
            modifier = Modifier
                .size(56.dp)
                .shadow(6.dp, CircleShape)
                .background(
                    brush = Brush.radialGradient(
                        colors = if (isTranslating) {
                            listOf(Color(0xFF00FF66), Color(0xFF007A33))
                        } else {
                            listOf(Color(0xFF3B82F6), Color(0xFF1D4ED8))
                        }
                    ),
                    shape = CircleShape
                )
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        onDrag(dragAmount.x.toInt(), dragAmount.y.toInt())
                    }
                }
                .clickable { isMenuOpen = !isMenuOpen },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Translate,
                contentDescription = "Translator Menu Toggle",
                tint = Color.White,
                modifier = Modifier.size(28.dp)
            )
            if (isTranslating) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .align(Alignment.TopEnd)
                        .offset(x = (-2).dp, y = 2.dp)
                        .background(Color.Red, CircleShape)
                        .border(1.5.dp, Color.White, CircleShape)
                )
            }
        }

        // Subtitles display directly attached below the green button when menu is closed
        if (!isMenuOpen && isTranslating && (subtitle.isNotEmpty() || translation.isNotEmpty())) {
            Card(
                shape = RoundedCornerShape(8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Color.Black.copy(alpha = 0.85f)
                ),
                border = BorderStroke(1.dp, Color(0xFF00C853).copy(alpha = 0.4f)),
                modifier = Modifier
                    .width(300.dp)
                    .padding(top = 8.dp)
                    .shadow(4.dp, RoundedCornerShape(8.dp))
            ) {
                Column(
                    modifier = Modifier.padding(10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (subtitle.isNotEmpty()) {
                        Text(
                            text = subtitle,
                            color = Color.LightGray,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Normal,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)
                        )
                    }
                    if (translation.isNotEmpty()) {
                        Text(
                            text = translation,
                            color = Color(0xFF00C853),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    }
}
