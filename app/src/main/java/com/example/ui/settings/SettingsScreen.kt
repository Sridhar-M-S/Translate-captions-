package com.example.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.SettingsManager
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settingsManager: SettingsManager
) {
    val scrollState = rememberScrollState()

    // Local states mirroring SettingsManager for immediate UI response
    var targetLanguage by remember { mutableStateOf(settingsManager.targetLanguage) }
    var speechSpeed by remember { mutableStateOf(settingsManager.speechSpeed) }
    var speechPitch by remember { mutableStateOf(settingsManager.speechPitch) }
    var isVoiceEnabled by remember { mutableStateOf(settingsManager.isVoiceEnabled) }
    var isOverlayEnabled by remember { mutableStateOf(settingsManager.isOverlayEnabled) }
    var isOcrEnabled by remember { mutableStateOf(settingsManager.isOcrEnabled) }
    var ocrSensitivity by remember { mutableStateOf(settingsManager.ocrSensitivity) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Translation & TTS Settings",
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp,
            color = Color.White,
            modifier = Modifier.fillMaxWidth()
        )

        // 1. Language Card
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF161622)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(imageVector = Icons.Default.Language, contentDescription = "Lang", tint = Color(0xFF00C853))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Target Translation Language", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color.White)
                }
                Spacer(modifier = Modifier.height(4.dp))
                
                // Currently focused on Tamil but we can support others if requested
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    LanguageChip(
                        name = "Tamil (Default)",
                        code = "ta",
                        isSelected = targetLanguage == "ta",
                        onSelect = {
                            targetLanguage = "ta"
                            settingsManager.targetLanguage = "ta"
                        }
                    )
                    LanguageChip(
                        name = "Hindi",
                        code = "hi",
                        isSelected = targetLanguage == "hi",
                        onSelect = {
                            targetLanguage = "hi"
                            settingsManager.targetLanguage = "hi"
                        }
                    )
                    LanguageChip(
                        name = "Telugu",
                        code = "te",
                        isSelected = targetLanguage == "te",
                        onSelect = {
                            targetLanguage = "te"
                            settingsManager.targetLanguage = "te"
                        }
                    )
                }
            }
        }

        // 2. TTS Voice Parameters Card
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF161622)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(imageVector = Icons.Default.VolumeUp, contentDescription = "Voice", tint = Color(0xFF00C853))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Enable Text-To-Speech (Tamil)", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color.White)
                    }
                    Switch(
                        checked = isVoiceEnabled,
                        onCheckedChange = {
                            isVoiceEnabled = it
                            settingsManager.isVoiceEnabled = it
                        },
                        colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF00C853), checkedTrackColor = Color(0xFF00C853).copy(alpha = 0.4f)),
                        modifier = Modifier.testTag("voice_enable_switch")
                    )
                }

                if (isVoiceEnabled) {
                    Divider(color = Color.White.copy(alpha = 0.08f))

                    // Voice Speed Slider
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Speech Speed (Rate)", fontSize = 13.sp, color = Color.LightGray)
                            Text("${String.format("%.1f", speechSpeed)}x", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFF00C853))
                        }
                        Slider(
                            value = speechSpeed,
                            onValueChange = {
                                speechSpeed = it
                                settingsManager.speechSpeed = it
                            },
                            valueRange = 0.5f..2.0f,
                            colors = SliderDefaults.colors(
                                thumbColor = Color(0xFF00C853),
                                activeTrackColor = Color(0xFF00C853)
                            ),
                            modifier = Modifier.testTag("speech_speed_slider")
                        )
                    }

                    // Voice Pitch Slider
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Speech Pitch", fontSize = 13.sp, color = Color.LightGray)
                            Text("${String.format("%.1f", speechPitch)}x", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFF00C853))
                        }
                        Slider(
                            value = speechPitch,
                            onValueChange = {
                                speechPitch = it
                                settingsManager.speechPitch = it
                            },
                            valueRange = 0.5f..1.5f,
                            colors = SliderDefaults.colors(
                                thumbColor = Color(0xFF00C853),
                                activeTrackColor = Color(0xFF00C853)
                            ),
                            modifier = Modifier.testTag("speech_pitch_slider")
                        )
                    }

                    Divider(color = Color.White.copy(alpha = 0.08f))

                    // Voice Gender Selection
                    var voiceGender by remember { mutableStateOf(settingsManager.voiceGender) }
                    Column {
                        Text("Voice Gender Preference", fontSize = 13.sp, color = Color.LightGray)
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    voiceGender = "female"
                                    settingsManager.voiceGender = "female"
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (voiceGender == "female") Color(0xFF00C853) else Color.DarkGray
                                ),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Female", color = Color.White)
                            }

                            Button(
                                onClick = {
                                    voiceGender = "male"
                                    settingsManager.voiceGender = "male"
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (voiceGender == "male") Color(0xFF00C853) else Color.DarkGray
                                ),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Male", color = Color.White)
                            }
                        }
                    }
                }
            }
        }

        // 3. Subtitle Overlay Toggles Card
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF161622)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Overlay enable/disable
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(imageVector = Icons.Default.PictureInPicture, contentDescription = "Overlay", tint = Color(0xFF00C853))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Show Floating Subtitles", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color.White)
                    }
                    Switch(
                        checked = isOverlayEnabled,
                        onCheckedChange = {
                            isOverlayEnabled = it
                            settingsManager.isOverlayEnabled = it
                        },
                        colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF00C853), checkedTrackColor = Color(0xFF00C853).copy(alpha = 0.4f)),
                        modifier = Modifier.testTag("overlay_enable_switch")
                    )
                }

                Divider(color = Color.White.copy(alpha = 0.08f))

                // OCR Enable/Disable
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(imageVector = Icons.Default.PhotoCamera, contentDescription = "OCR", tint = Color(0xFF00C853))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("OCR Fallback (Screen Scanning)", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color.White)
                    }
                    Switch(
                        checked = isOcrEnabled,
                        onCheckedChange = {
                            isOcrEnabled = it
                            settingsManager.isOcrEnabled = it
                        },
                        colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF00C853), checkedTrackColor = Color(0xFF00C853).copy(alpha = 0.4f)),
                        modifier = Modifier.testTag("ocr_enable_switch")
                    )
                }

                if (isOcrEnabled) {
                    Divider(color = Color.White.copy(alpha = 0.08f))

                    // OCR Sensitivity
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("OCR Scan Sensitivity", fontSize = 13.sp, color = Color.LightGray)
                            val rateText = when (ocrSensitivity) {
                                1000L -> "Fast (1s)"
                                1500L -> "Medium (1.5s)"
                                3000L -> "Eco (3s)"
                                else -> "Medium"
                            }
                            Text(rateText, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFF00C853))
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            SensitivityChip(
                                label = "1.0 Sec",
                                isSelected = ocrSensitivity == 1000L,
                                onClick = {
                                    ocrSensitivity = 1000L
                                    settingsManager.ocrSensitivity = 1000L
                                }
                            )
                            SensitivityChip(
                                label = "1.5 Sec",
                                isSelected = ocrSensitivity == 1500L,
                                onClick = {
                                    ocrSensitivity = 1500L
                                    settingsManager.ocrSensitivity = 1500L
                                }
                            )
                            SensitivityChip(
                                label = "3.0 Sec",
                                isSelected = ocrSensitivity == 3000L,
                                onClick = {
                                    ocrSensitivity = 3000L
                                    settingsManager.ocrSensitivity = 3000L
                                }
                            )
                        }
                    }

                    Divider(color = Color.White.copy(alpha = 0.08f))

                    // OCR Scan Region
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text("OCR Scan Region", fontSize = 13.sp, color = Color.LightGray)
                        Text(
                            "Custom Screen Selection is used as the exact OCR scan region. Draw and confirm your rectangle selection on screen to scan subtitles only within that region.",
                            fontSize = 12.sp,
                            color = Color.Gray
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LanguageChip(
    name: String,
    code: String,
    isSelected: Boolean,
    onSelect: () -> Unit
) {
    FilterChip(
        selected = isSelected,
        onClick = onSelect,
        label = { Text(name, fontSize = 12.sp) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = Color(0xFF00C853).copy(alpha = 0.15f),
            selectedLabelColor = Color(0xFF00C853),
            labelColor = Color.Gray
        )
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SensitivityChip(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    FilterChip(
        selected = isSelected,
        onClick = onClick,
        label = { Text(label, fontSize = 11.sp) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = Color(0xFF00C853).copy(alpha = 0.15f),
            selectedLabelColor = Color(0xFF00C853),
            labelColor = Color.Gray
        )
    )
}
