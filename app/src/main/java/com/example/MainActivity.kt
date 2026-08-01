package com.example

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.with
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.AppDatabase
import com.example.data.HistoryRepository
import com.example.data.SettingsManager
import com.example.service.SubtitleAccessibilityService
import com.example.ui.home.isAccessibilityServiceEnabled
import com.example.speech.TtsManager
import com.example.translation.TranslationService
import com.example.ui.about.AboutScreen
import com.example.ui.history.HistoryScreen
import com.example.ui.home.HomeScreen
import com.example.ui.settings.SettingsScreen
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.translator.LiveTranslatorScreen
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {

    private lateinit var settingsManager: SettingsManager
    private lateinit var ttsManager: TtsManager
    private lateinit var translationService: TranslationService
    private lateinit var historyRepository: HistoryRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Initialize core dependencies
        settingsManager = SettingsManager(this)
        ttsManager = TtsManager(this)
        translationService = TranslationService()
        
        val database = AppDatabase.getDatabase(this)
        historyRepository = HistoryRepository(database.historyDao())

        setContent {
            MyApplicationTheme(darkTheme = true) { // Enforce Dark Mode UI
                var currentScreen by remember { mutableStateOf(Screen.Home) }
                var isServiceRunning by remember { mutableStateOf(settingsManager.isTranslatorActive) }
                val context = LocalContext.current

                // Periodically verify if service is active and running
                LaunchedEffect(Unit) {
                    while (true) {
                        val isEnabledInSettings = isAccessibilityServiceEnabled(context, SubtitleAccessibilityService::class.java)
                        if (!isEnabledInSettings && settingsManager.isTranslatorActive) {
                            // If user revoked accessibility permission, sync active state down
                            settingsManager.isTranslatorActive = false
                        }
                        isServiceRunning = settingsManager.isTranslatorActive
                        delay(1000)
                    }
                }

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    topBar = {
                        MainTopBar(currentScreen)
                    },
                    bottomBar = {
                        BottomNavBar(
                            currentScreen = currentScreen,
                            onScreenSelected = { currentScreen = it }
                        )
                    },
                    containerColor = Color(0xFF0F0F15) // Sleek midnight background
                ) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                            .background(Color(0xFF0F0F15))
                    ) {
                        when (currentScreen) {
                            Screen.Home -> HomeScreen(
                                settingsManager = settingsManager,
                                isServiceRunning = isServiceRunning,
                                onToggleService = { start ->
                                    val intent = Intent(context, SubtitleAccessibilityService::class.java)
                                    if (start) {
                                        intent.action = "START"
                                        context.startService(intent)
                                        isServiceRunning = true
                                    } else {
                                        intent.action = "STOP"
                                        context.startService(intent)
                                        isServiceRunning = false
                                    }
                                }
                            )
                            Screen.LiveTranslator -> LiveTranslatorScreen(
                                settingsManager = settingsManager,
                                translationService = translationService,
                                ttsManager = ttsManager
                            )
                            Screen.Settings -> SettingsScreen(
                                settingsManager = settingsManager
                            )
                            Screen.History -> HistoryScreen(
                                historyRepository = historyRepository,
                                ttsManager = ttsManager,
                                speechSpeed = settingsManager.speechSpeed,
                                speechPitch = settingsManager.speechPitch
                            )
                            Screen.About -> AboutScreen()
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        ttsManager.shutdown()
    }
}

enum class Screen {
    Home, LiveTranslator, Settings, History, About
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainTopBar(currentScreen: Screen) {
    val title = when (currentScreen) {
        Screen.Home -> "Live Subtitle Translator"
        Screen.LiveTranslator -> "Translator Sandbox"
        Screen.Settings -> "Configuration"
        Screen.History -> "Translation Logs"
        Screen.About -> "App Info"
    }

    TopAppBar(
        title = {
            Text(
                text = title,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                color = Color.White
            )
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Color(0xFF12121A),
            titleContentColor = Color.White
        ),
        modifier = Modifier.testTag("app_top_bar")
    )
}

@Composable
fun BottomNavBar(
    currentScreen: Screen,
    onScreenSelected: (Screen) -> Unit
) {
    NavigationBar(
        containerColor = Color(0xFF12121A),
        tonalElevation = 8.dp,
        modifier = Modifier.testTag("app_bottom_nav_bar")
    ) {
        NavigationBarItem(
            selected = currentScreen == Screen.Home,
            onClick = { onScreenSelected(Screen.Home) },
            icon = { Icon(imageVector = Icons.Default.Home, contentDescription = "Home", modifier = Modifier.size(20.dp)) },
            label = { Text("Home", fontSize = 10.sp) },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = Color(0xFF00C853),
                selectedTextColor = Color(0xFF00C853),
                unselectedIconColor = Color.Gray,
                unselectedTextColor = Color.Gray,
                indicatorColor = Color(0xFF00C853).copy(alpha = 0.12f)
            ),
            modifier = Modifier.testTag("nav_item_home")
        )
        NavigationBarItem(
            selected = currentScreen == Screen.LiveTranslator,
            onClick = { onScreenSelected(Screen.LiveTranslator) },
            icon = { Icon(imageVector = Icons.Default.Translate, contentDescription = "Sandbox", modifier = Modifier.size(20.dp)) },
            label = { Text("Sandbox", fontSize = 10.sp) },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = Color(0xFF00C853),
                selectedTextColor = Color(0xFF00C853),
                unselectedIconColor = Color.Gray,
                unselectedTextColor = Color.Gray,
                indicatorColor = Color(0xFF00C853).copy(alpha = 0.12f)
            ),
            modifier = Modifier.testTag("nav_item_sandbox")
        )
        NavigationBarItem(
            selected = currentScreen == Screen.Settings,
            onClick = { onScreenSelected(Screen.Settings) },
            icon = { Icon(imageVector = Icons.Default.Settings, contentDescription = "Settings", modifier = Modifier.size(20.dp)) },
            label = { Text("Settings", fontSize = 10.sp) },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = Color(0xFF00C853),
                selectedTextColor = Color(0xFF00C853),
                unselectedIconColor = Color.Gray,
                unselectedTextColor = Color.Gray,
                indicatorColor = Color(0xFF00C853).copy(alpha = 0.12f)
            ),
            modifier = Modifier.testTag("nav_item_settings")
        )
        NavigationBarItem(
            selected = currentScreen == Screen.History,
            onClick = { onScreenSelected(Screen.History) },
            icon = { Icon(imageVector = Icons.Default.History, contentDescription = "History", modifier = Modifier.size(20.dp)) },
            label = { Text("History", fontSize = 10.sp) },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = Color(0xFF00C853),
                selectedTextColor = Color(0xFF00C853),
                unselectedIconColor = Color.Gray,
                unselectedTextColor = Color.Gray,
                indicatorColor = Color(0xFF00C853).copy(alpha = 0.12f)
            ),
            modifier = Modifier.testTag("nav_item_history")
        )
        NavigationBarItem(
            selected = currentScreen == Screen.About,
            onClick = { onScreenSelected(Screen.About) },
            icon = { Icon(imageVector = Icons.Default.Info, contentDescription = "About", modifier = Modifier.size(20.dp)) },
            label = { Text("About", fontSize = 10.sp) },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = Color(0xFF00C853),
                selectedTextColor = Color(0xFF00C853),
                unselectedIconColor = Color.Gray,
                unselectedTextColor = Color.Gray,
                indicatorColor = Color(0xFF00C853).copy(alpha = 0.12f)
            ),
            modifier = Modifier.testTag("nav_item_about")
        )
    }
}
