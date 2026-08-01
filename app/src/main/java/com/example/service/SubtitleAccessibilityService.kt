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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

class SubtitleAccessibilityService : AccessibilityService() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var settingsManager: SettingsManager
    private lateinit var ttsManager: TtsManager
    private lateinit var translationService: TranslationService
    private lateinit var historyRepository: HistoryRepository

    private var lastSubtitleText = ""
    private var lastTranslatedText = ""
    private var detectedLanguage = "en"

    // Overlay components
    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var overlayParams: WindowManager.LayoutParams? = null

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
        super.onCreate()
        settingsManager = SettingsManager(this)
        ttsManager = TtsManager(this)
        translationService = TranslationService()
        
        val database = AppDatabase.getDatabase(this)
        historyRepository = HistoryRepository(database.historyDao())

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        
        // Sync initial state
        isTranslatingState.value = settingsManager.isTranslatorActive
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d("SubtitleService", "Service Connected")
        if (settingsManager.isTranslatorActive) {
            startTranslationSession()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.let {
            val action = it.action
            if (action == "START") {
                startTranslationSession()
            } else if (action == "STOP") {
                stopTranslationSession()
            }
        }
        return START_STICKY
    }

    private fun startTranslationSession() {
        settingsManager.isTranslatorActive = true
        isTranslatingState.value = true
        
        // Create overlay
        if (settingsManager.isOverlayEnabled) {
            showFloatingOverlay()
        }
        
        // Start OCR periodic scanning
        if (settingsManager.isOcrEnabled) {
            ocrHandler.removeCallbacks(ocrRunnable)
            ocrHandler.postDelayed(ocrRunnable, 500)
        }
        
        Log.d("SubtitleService", "Translation Session Started")
    }

    private fun stopTranslationSession() {
        settingsManager.isTranslatorActive = false
        isTranslatingState.value = false
        
        ocrHandler.removeCallbacks(ocrRunnable)
        ttsManager.stop()
        removeFloatingOverlay()
        
        subtitleState.value = ""
        translationState.value = ""
        lastSubtitleText = ""
        lastTranslatedText = ""

        Log.d("SubtitleService", "Translation Session Stopped")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !settingsManager.isTranslatorActive) return

        // Listen for standard window text changes (for apps that expose accessible text like YouTube)
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val source = event.source ?: return
                val textNodes = mutableListOf<String>()
                findTextNodes(source, textNodes)
                source.recycle()

                // Filter text nodes to find subtitles (typically at the bottom, short texts that update)
                val subtitleCandidates = textNodes.filter { 
                    it.length in 4..180 && 
                    !it.contains(":", ignoreCase = true) && 
                    !it.contains("http", ignoreCase = true)
                }

                if (subtitleCandidates.isNotEmpty()) {
                    // Choose the most likely subtitle node (typically the last one in view)
                    val candidate = subtitleCandidates.last()
                    processNewSubtitle(candidate)
                }
            }
        }
    }

    private fun findTextNodes(node: AccessibilityNodeInfo, list: MutableList<String>) {
        if (node.text != null && node.text.toString().isNotBlank()) {
            list.add(node.text.toString())
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                findTextNodes(child, list)
                child.recycle()
            }
        }
    }

    private fun captureAndRecognizeText() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                    override fun onSuccess(screenshotResult: ScreenshotResult) {
                        val buffer = screenshotResult.hardwareBuffer
                        val colorSpace = screenshotResult.colorSpace
                        val bitmap = Bitmap.wrapHardwareBuffer(buffer, colorSpace)
                        buffer.close()

                        if (bitmap != null) {
                            // Convert to software bitmap for ML Kit
                            val softwareBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false)
                            runOcrOnBitmap(softwareBitmap)
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        Log.e("SubtitleService", "Screenshot failure: $errorCode")
                    }
                })
            } catch (e: Exception) {
                Log.e("SubtitleService", "Error taking screenshot", e)
            }
        }
    }

    private fun runOcrOnBitmap(bitmap: Bitmap) {
        val image = InputImage.fromBitmap(bitmap, 0)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        
        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                // Look for text in the bottom third of the screen where subtitles usually live
                val height = bitmap.height
                val bottomThirdY = height * 2 / 3
                
                val subtitleLines = mutableListOf<String>()
                for (block in visionText.textBlocks) {
                    for (line in block.lines) {
                        val rect = line.boundingBox
                        if (rect != null && rect.bottom > bottomThirdY) {
                            subtitleLines.add(line.text)
                        }
                    }
                }

                if (subtitleLines.isNotEmpty()) {
                    val combinedText = subtitleLines.joinToString(" ").trim()
                    if (combinedText.length in 3..200) {
                        processNewSubtitle(combinedText)
                    }
                }
            }
            .addOnFailureListener { e ->
                Log.e("SubtitleService", "OCR Text recognition failed", e)
            }
    }

    private fun processNewSubtitle(text: String) {
        // Guard against identical subtitle or tiny jitter updates
        if (isDuplicate(text, lastSubtitleText)) return

        lastSubtitleText = text
        subtitleState.value = text

        serviceScope.launch {
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
                historyRepository.insert(
                    TranslationHistory(
                        originalText = text,
                        translatedText = result.translatedText,
                        sourceLanguage = result.detectedLanguage.uppercase(Locale.ROOT)
                    )
                )

                // TTS speak in Tamil (or target lang) if enabled and not muted
                if (settingsManager.isVoiceEnabled && !isMutedState.value) {
                    ttsManager.speak(
                        result.translatedText,
                        speed = settingsManager.speechSpeed,
                        pitch = settingsManager.speechPitch
                    )
                }
            }
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

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) 
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY 
            else 
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = 200 // Margin from bottom of the screen
        }

        overlayParams = params

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
                FloatingOverlayContent(
                    subtitleFlow = subtitleState,
                    translationFlow = translationState,
                    isTranslatingFlow = isTranslatingState,
                    isMutedFlow = isMutedState,
                    onToggleMute = { isMutedState.value = !isMutedState.value },
                    onStop = { stopTranslationSession() }
                )
            }
        }

        // Add a parent FrameLayout to support potential dragging/touch interception
        val parentLayout = FrameLayout(this).apply {
            addView(composeView)
        }

        overlayView = parentLayout
        windowManager?.addView(parentLayout, params)
    }

    private fun removeFloatingOverlay() {
        overlayView?.let {
            windowManager?.removeView(it)
            overlayView = null
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        ttsManager.shutdown()
        removeFloatingOverlay()
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

/**
 * The Floating Subtitle overlay Composable
 */
@Composable
fun FloatingOverlayContent(
    subtitleFlow: StateFlow<String>,
    translationFlow: StateFlow<String>,
    isTranslatingFlow: StateFlow<Boolean>,
    isMutedFlow: StateFlow<Boolean>,
    onToggleMute: () -> Unit,
    onStop: () -> Unit
) {
    val subtitle by subtitleFlow.collectAsState()
    val translation by translationFlow.collectAsState()
    val isMuted by isMutedFlow.collectAsState()
    
    var isExpanded by remember { mutableStateOf(true) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        if (!isExpanded) {
            // Collapsed tiny control bubble
            IconButton(
                onClick = { isExpanded = true },
                modifier = Modifier
                    .size(48.dp)
                    .background(Color(0xFF1E1E2E).copy(alpha = 0.9f), CircleShape)
                    .shadow(4.dp, CircleShape)
                    .testTag("floating_collapsed_bubble")
            ) {
                Icon(
                    imageVector = Icons.Default.Translate,
                    contentDescription = "Expand Overlay",
                    tint = Color(0xFF00C853)
                )
            }
        } else {
            // Beautiful Subtitle Translation Panel
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFF12121E).copy(alpha = 0.93f)
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(6.dp, RoundedCornerShape(16.dp))
                    .testTag("floating_expanded_panel")
            ) {
                Column(
                    modifier = Modifier.padding(12.dp)
                ) {
                    // Header with controls
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .background(Color(0xFF00C853), CircleShape)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Tamil Subtitle Translator",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White.copy(alpha = 0.7f)
                            )
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = onToggleMute,
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    imageVector = if (isMuted) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                                    contentDescription = "Mute Voice",
                                    tint = if (isMuted) Color.LightGray else Color(0xFF00C853),
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(4.dp))
                            IconButton(
                                onClick = { isExpanded = false },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Minimize,
                                    contentDescription = "Collapse",
                                    tint = Color.White,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(4.dp))
                            IconButton(
                                onClick = onStop,
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Stop Service",
                                    tint = Color.Red,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }

                    if (subtitle.isNotEmpty() || translation.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Divider(color = Color.White.copy(alpha = 0.1f))
                        Spacer(modifier = Modifier.height(6.dp))

                        // Original Subtitle text (if non-empty)
                        if (subtitle.isNotEmpty()) {
                            Text(
                                text = subtitle,
                                color = Color.LightGray,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Normal,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)
                            )
                        }

                        // Translated Tamil Subtitle text (Primary)
                        if (translation.isNotEmpty()) {
                            Text(
                                text = translation,
                                color = Color(0xFF00C853),
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    } else {
                        // Scanning / Waiting State
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Waiting for subtitle...",
                            fontSize = 12.sp,
                            color = Color.White.copy(alpha = 0.4f),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    }
}
