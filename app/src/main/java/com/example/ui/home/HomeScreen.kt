package com.example.ui.home

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.SettingsManager
import com.example.service.SubtitleAccessibilityService

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    settingsManager: SettingsManager,
    isServiceRunning: Boolean,
    onToggleService: (Boolean) -> Unit
) {
    val context = LocalContext.current
    var hasAccessibilityPermission by remember { mutableStateOf(isAccessibilityServiceEnabled(context, SubtitleAccessibilityService::class.java)) }
    var hasOverlayPermission by remember { mutableStateOf(Settings.canDrawOverlays(context)) }

    // Automatically check and update permissions whenever the app returns to foreground
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasAccessibilityPermission = isAccessibilityServiceEnabled(context, SubtitleAccessibilityService::class.java)
                hasOverlayPermission = Settings.canDrawOverlays(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Refresh permissions when screen is visible
    LaunchedEffect(Unit) {
        hasAccessibilityPermission = isAccessibilityServiceEnabled(context, SubtitleAccessibilityService::class.java)
        hasOverlayPermission = Settings.canDrawOverlays(context)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // App Title Banner
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E2E)),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .background(Color(0xFF00C853).copy(alpha = 0.15f), CircleShape)
                        .border(1.dp, Color(0xFF00C853), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Translate,
                        contentDescription = "Translate Logo",
                        tint = Color(0xFF00C853),
                        modifier = Modifier.size(32.dp)
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Live Subtitle Translator",
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp,
                    color = Color.White
                )
                Text(
                    text = "Tamil AI Voice (TTS)",
                    fontSize = 14.sp,
                    color = Color(0xFF00C853),
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Translate on-screen subtitles from any video player automatically and read them aloud in natural Tamil.",
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    color = Color.Gray,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
            }
        }

        // Permissions Card
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF161622)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Required Permissions",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = Color.White
                )

                // 1. Accessibility service permission row
                PermissionRow(
                    title = "Accessibility Service",
                    description = "Required to detect subtitles on screen",
                    isGranted = hasAccessibilityPermission,
                    onRequestGrant = {
                        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        context.startActivity(intent)
                    }
                )

                Divider(color = Color.White.copy(alpha = 0.08f))

                // 2. Draw over other apps permission row
                PermissionRow(
                    title = "Display Over Other Apps",
                    description = "Required to show translated subtitle overlay",
                    isGranted = hasOverlayPermission,
                    onRequestGrant = {
                        val intent = Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}")
                        )
                        context.startActivity(intent)
                    }
                )
            }
        }

        // Start/Stop Controller Card
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF161622)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Translator Status",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = Color.White,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(16.dp))

                val isReady = hasAccessibilityPermission && hasOverlayPermission

                if (isServiceRunning) {
                    // Running state
                    Button(
                        onClick = { onToggleService(false) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Red),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .testTag("stop_service_button"),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(imageVector = Icons.Default.Stop, contentDescription = "Stop")
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Stop Translator Session", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                } else {
                    // Stopped state
                    Button(
                        onClick = { onToggleService(true) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isReady) Color(0xFF00C853) else Color.Gray
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .testTag("start_service_button"),
                        shape = RoundedCornerShape(12.dp),
                        enabled = isReady
                    ) {
                        Icon(imageVector = Icons.Default.PlayArrow, contentDescription = "Start")
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Start Translation Service", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                AnimatedVisibility(visible = !isReady) {
                    Text(
                        text = "⚠️ Please grant both permissions above to start.",
                        color = Color(0xFFFFB300),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center
                    )
                }

                AnimatedVisibility(visible = isReady && !isServiceRunning) {
                    Text(
                        text = "Ready! Tap to start. A floating bar will appear to show subtitles.",
                        color = Color.Gray,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center
                    )
                }

                AnimatedVisibility(visible = isServiceRunning) {
                    Text(
                        text = "Active! Open YouTube, Netflix, or any browser to see live translations.",
                        color = Color(0xFF00C853),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

@Composable
fun PermissionRow(
    title: String,
    description: String,
    isGranted: Boolean,
    onRequestGrant: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                color = Color.White
            )
            Text(
                text = description,
                fontSize = 11.sp,
                color = Color.Gray
            )
        }
        
        Spacer(modifier = Modifier.width(8.dp))

        if (isGranted) {
            Box(
                modifier = Modifier
                    .background(Color(0xFF00C853).copy(alpha = 0.1f), CircleShape)
                    .border(1.dp, Color(0xFF00C853), CircleShape)
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    text = "GRANTED",
                    color = Color(0xFF00C853),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        } else {
            Button(
                onClick = onRequestGrant,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E2E3E)),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                modifier = Modifier.height(32.dp)
            ) {
                Text("GRANT", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }
        }
    }
}

/**
 * Checks if a specific AccessibilityService is enabled in Android Settings.
 * Uses multiple robust strategies to ensure compatibility with all devices (including Xiaomi HyperOS).
 */
fun isAccessibilityServiceEnabled(context: Context, service: Class<out AccessibilityService>): Boolean {
    // Strategy 1: Check via official AccessibilityManager
    try {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? android.view.accessibility.AccessibilityManager
        if (am != null) {
            val enabledServices = am.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            for (enabledService in enabledServices) {
                val serviceInfo = enabledService.resolveInfo?.serviceInfo
                if (serviceInfo != null) {
                    if (serviceInfo.packageName == context.packageName && serviceInfo.name == service.name) {
                        return true
                    }
                }
            }
        }
    } catch (e: Exception) {
        // Fallback to secure settings parsing
    }

    // Strategy 2: Parse Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES manually with full component matching
    try {
        val expectedPackage = context.packageName
        val expectedClassShort = "." + service.simpleName
        val expectedClassFull = service.name

        val settingValue = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val colonSplitter = settingValue.split(":")
        for (componentStr in colonSplitter) {
            if (componentStr.isBlank()) continue
            
            val slashIndex = componentStr.indexOf('/')
            if (slashIndex > 0 && slashIndex < componentStr.length - 1) {
                val pkg = componentStr.substring(0, slashIndex).trim()
                val cls = componentStr.substring(slashIndex + 1).trim()
                
                if (pkg.equals(expectedPackage, ignoreCase = true)) {
                    if (cls.equals(expectedClassFull, ignoreCase = true) || 
                        cls.equals(expectedClassShort, ignoreCase = true) || 
                        cls.endsWith(service.simpleName, ignoreCase = true)) {
                        return true
                    }
                }
            } else {
                if (componentStr.contains(service.simpleName, ignoreCase = true) && 
                    componentStr.contains(expectedPackage, ignoreCase = true)) {
                    return true
                }
            }
        }
    } catch (e: Exception) {
        // Safe fallback
    }
    return false
}
