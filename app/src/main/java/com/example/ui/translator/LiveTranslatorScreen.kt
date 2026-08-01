package com.example.ui.translator

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.SettingsManager
import com.example.speech.TtsManager
import com.example.translation.TranslationResult
import com.example.translation.TranslationService
import com.google.mlkit.nl.languageid.LanguageIdentification
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveTranslatorScreen(
    settingsManager: SettingsManager,
    translationService: TranslationService,
    ttsManager: TtsManager
) {
    val coroutineScope = rememberCoroutineScope()
    var inputText by remember { mutableStateOf("") }
    var translatedText by remember { mutableStateOf("") }
    var detectedLang by remember { mutableStateOf("Unknown") }
    var isTranslating by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Translation Sandbox",
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp,
            color = Color.White,
            modifier = Modifier.fillMaxWidth()
        )

        // Text input card
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
                    text = "Source Text",
                    fontSize = 12.sp,
                    color = Color.Gray,
                    fontWeight = FontWeight.Medium
                )

                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    placeholder = { Text("Type English subtitle here...", color = Color.Gray) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(110.dp)
                        .testTag("sandbox_input_field"),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF00C853),
                        unfocusedBorderColor = Color.White.copy(alpha = 0.15f),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    )
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (detectedLang.isNotEmpty() && detectedLang != "Unknown") {
                        AssistChip(
                            onClick = {},
                            label = { Text("Detected: ${detectedLang.uppercase()}", color = Color(0xFF00C853)) },
                            colors = AssistChipDefaults.assistChipColors(
                                leadingIconContentColor = Color(0xFF00C853)
                            )
                        )
                    } else {
                        Spacer(modifier = Modifier.width(1.dp))
                    }

                    Row {
                        if (inputText.isNotEmpty()) {
                            IconButton(onClick = {
                                inputText = ""
                                translatedText = ""
                                detectedLang = "Unknown"
                            }) {
                                Icon(imageVector = Icons.Default.Clear, contentDescription = "Clear", tint = Color.Gray)
                            }
                        }
                        
                        Button(
                            onClick = {
                                if (inputText.isBlank()) return@Button
                                isTranslating = true
                                coroutineScope.launch {
                                    // 1. Language Identification
                                    val languageIdentifier = LanguageIdentification.getClient()
                                    languageIdentifier.identifyLanguage(inputText)
                                        .addOnSuccessListener { languageCode ->
                                            detectedLang = if (languageCode == "und") "Unknown" else languageCode
                                        }
                                        .addOnFailureListener {
                                            detectedLang = "Error"
                                        }

                                    // 2. Translation
                                    val targetLang = settingsManager.targetLanguage
                                    val result = if (com.example.BuildConfig.GEMINI_API_KEY.isNotEmpty()) {
                                        translationService.translateGemini(inputText, targetLang)
                                    } else {
                                        translationService.translateGoogle(inputText, targetLang)
                                    }

                                    isTranslating = false
                                    if (result is TranslationResult.Success) {
                                        translatedText = result.translatedText
                                        
                                        // 3. Play TTS voice if enabled
                                        if (settingsManager.isVoiceEnabled) {
                                            ttsManager.speak(
                                                result.translatedText,
                                                speed = settingsManager.speechSpeed,
                                                pitch = settingsManager.speechPitch
                                            )
                                        }
                                    } else if (result is TranslationResult.Error) {
                                        translatedText = "Error: ${result.message}"
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00C853)),
                            shape = RoundedCornerShape(10.dp),
                            enabled = inputText.isNotBlank() && !isTranslating,
                            modifier = Modifier.testTag("translate_speak_button")
                        ) {
                            if (isTranslating) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                            } else {
                                Icon(imageVector = Icons.Default.VolumeUp, contentDescription = "Translate")
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Speak Tamil", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }

        // Translation Result Card
        AnimatedVisibility(visible = translatedText.isNotEmpty()) {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0D1E15)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Translated (Tamil)",
                            fontSize = 12.sp,
                            color = Color(0xFF00C853),
                            fontWeight = FontWeight.Bold
                        )
                        
                        IconButton(
                            onClick = {
                                ttsManager.speak(
                                    translatedText,
                                    speed = settingsManager.speechSpeed,
                                    pitch = settingsManager.speechPitch
                                )
                            },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(imageVector = Icons.Default.PlayArrow, contentDescription = "Replay", tint = Color(0xFF00C853))
                        }
                    }

                    Text(
                        text = translatedText,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        // Quick Tips
        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF161622).copy(alpha = 0.5f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(imageVector = Icons.Default.Info, contentDescription = "Tip", tint = Color.Gray, modifier = Modifier.size(16.dp))
                Text(
                    text = "Tip: Use this playground to test speech rate, volume, and translation quality without leaving the app.",
                    fontSize = 11.sp,
                    color = Color.Gray
                )
            }
        }
    }
}
