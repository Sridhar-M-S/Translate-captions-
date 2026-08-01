package com.example.ui.history

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.HistoryRepository
import com.example.data.TranslationHistory
import com.example.speech.TtsManager
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    historyRepository: HistoryRepository,
    ttsManager: TtsManager,
    speechSpeed: Float,
    speechPitch: Float
) {
    val coroutineScope = rememberCoroutineScope()
    var searchQuery by remember { mutableStateOf("") }
    var historyList by remember { mutableStateOf<List<TranslationHistory>>(emptyList()) }

    // Dynamically retrieve items based on search query
    LaunchedEffect(searchQuery) {
        if (searchQuery.isBlank()) {
            historyRepository.allHistory.collectLatest {
                historyList = it
            }
        } else {
            historyRepository.search(searchQuery).collectLatest {
                historyList = it
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Translation History",
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                color = Color.White
            )

            if (historyList.isNotEmpty()) {
                TextButton(
                    onClick = {
                        coroutineScope.launch {
                            historyRepository.clearAll()
                        }
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = Color.Red.copy(alpha = 0.8f))
                ) {
                    Icon(imageVector = Icons.Default.DeleteSweep, contentDescription = "Clear All", modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Clear All", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        // Search Bar
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("Search history...", color = Color.Gray, fontSize = 14.sp) },
            leadingIcon = { Icon(imageVector = Icons.Default.Search, contentDescription = "Search", tint = Color.Gray) },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { searchQuery = "" }) {
                        Icon(imageVector = Icons.Default.Clear, contentDescription = "Clear", tint = Color.Gray)
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .testTag("history_search_input"),
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color(0xFF00C853),
                unfocusedBorderColor = Color.White.copy(alpha = 0.15f),
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White
            ),
            singleLine = true
        )

        if (historyList.isEmpty()) {
            // Empty State
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.History,
                        contentDescription = "Empty",
                        tint = Color.Gray.copy(alpha = 0.4f),
                        modifier = Modifier.size(64.dp)
                    )
                    Text(
                        text = if (searchQuery.isEmpty()) "No history entries yet" else "No results found",
                        color = Color.Gray,
                        fontSize = 14.sp
                    )
                }
            }
        } else {
            // History list
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .testTag("history_list"),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(historyList, key = { it.id }) { item ->
                    HistoryItemCard(
                        item = item,
                        onSpeak = {
                            ttsManager.speak(item.translatedText, speed = speechSpeed, pitch = speechPitch)
                        },
                        onDelete = {
                            coroutineScope.launch {
                                historyRepository.deleteById(item.id)
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun HistoryItemCard(
    item: TranslationHistory,
    onSpeak: () -> Unit,
    onDelete: () -> Unit
) {
    val formatter = remember { SimpleDateFormat("hh:mm a • dd MMM", Locale.getDefault()) }
    val timeStr = remember(item.timestamp) { formatter.format(Date(item.timestamp)) }

    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF161622)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Header (Lang indicator + Timestamp + Delete button)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .background(Color(0xFF00C853).copy(alpha = 0.12f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = item.sourceLanguage,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF00C853)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = timeStr,
                        fontSize = 10.sp,
                        color = Color.Gray
                    )
                }

                Row {
                    IconButton(
                        onClick = onSpeak,
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(imageVector = Icons.Default.VolumeUp, contentDescription = "Speak", tint = Color(0xFF00C853), modifier = Modifier.size(16.dp))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(imageVector = Icons.Default.Delete, contentDescription = "Delete", tint = Color.Red.copy(alpha = 0.7f), modifier = Modifier.size(16.dp))
                    }
                }
            }

            // Original Text
            Text(
                text = item.originalText,
                fontSize = 13.sp,
                color = Color.LightGray,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            // Translated Text
            Text(
                text = item.translatedText,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
