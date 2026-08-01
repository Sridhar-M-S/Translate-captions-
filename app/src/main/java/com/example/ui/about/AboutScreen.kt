package com.example.ui.about

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun AboutScreen() {
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // App Identity Header
        Box(
            modifier = Modifier
                .size(72.dp)
                .background(Color(0xFF00C853).copy(alpha = 0.12f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = "About Logo",
                tint = Color(0xFF00C853),
                modifier = Modifier.size(36.dp)
            )
        }

        Text(
            text = "About Live Subtitle Translator",
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp,
            color = Color.White
        )

        Text(
            text = "Version 1.0.0 (Tamil AI Voice Edition)",
            fontSize = 12.sp,
            color = Color.Gray
        )

        // Core Mission/Description Card
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
                    text = "A Breakthrough in Accessibility",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = Color.White
                )
                Text(
                    text = "Live Subtitle Translator breaks down linguistic barriers by translating video subtitles into high-quality spoken Tamil in real time. Perfect for watching foreign films, educational courses, and documentaries on any video app.",
                    fontSize = 12.sp,
                    color = Color.Gray,
                    lineHeight = 18.sp
                )
            }
        }

        // Privacy and Security Card
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF161622)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(imageVector = Icons.Default.Shield, contentDescription = "Privacy", tint = Color(0xFF00C853), modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "100% Privacy & Security Compliant",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = Color.White
                    )
                }
                
                Divider(color = Color.White.copy(alpha = 0.08f))
                
                Text(
                    text = "• No Audio Recording: The app does not capture, store, or stream any audio from your device, respecting all platform privacy and copyright guidelines.",
                    fontSize = 12.sp,
                    color = Color.Gray
                )
                Text(
                    text = "• DRM Compliant: This app does not bypass DRM (Digital Rights Management). It relies strictly on visible subtitles or accessible text nodes on your screen.",
                    fontSize = 12.sp,
                    color = Color.Gray
                )
                Text(
                    text = "• Local Storage: Your translation history is securely stored 100% on-device in a private SQLite database. You can clear your history anytime with one tap.",
                    fontSize = 12.sp,
                    color = Color.Gray
                )
            }
        }

        // System Info Card
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF161622)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Technology Stack",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = Color.White
                )
                Divider(color = Color.White.copy(alpha = 0.08f))
                TechRow(label = "UI Layer", value = "Jetpack Compose & Material 3")
                TechRow(label = "Local DB", value = "Android Room (SQLite)")
                TechRow(label = "Speech Engine", value = "Android Text-to-Speech (TTS)")
                TechRow(label = "OCR Fallback", value = "Google ML Kit Text Recognition")
                TechRow(label = "AI Translator", value = "Gemini AI Engine & Google Neural Translate")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
fun TechRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, fontSize = 11.sp, color = Color.Gray)
        Text(text = value, fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.Bold)
    }
}
