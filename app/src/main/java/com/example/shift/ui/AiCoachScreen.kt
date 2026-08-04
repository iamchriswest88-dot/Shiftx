package com.example.shift.ui

import android.content.Intent
import android.provider.CalendarContract
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.shift.data.CoachMemory

@Composable
fun AiCoachScreen(
    viewModel: AiCoachViewModel,
    mainViewModel: MainViewModel,
    onNavigateToBuilder: (String) -> Unit
) {
    val messages by viewModel.messages.collectAsState()
    val isThinking by viewModel.isThinking.collectAsState()
    val memory by viewModel.memory.collectAsState()
    val vitals by mainViewModel.userVitals.collectAsState()
    var inputText by remember { mutableStateOf("") }
    val context = LocalContext.current
    val listState = rememberLazyListState()
    var showMenu by remember { mutableStateOf(false) }
    var showMemoryCard by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        viewModel.initializeChat()
        viewModel.navigationEvent.collect { workoutId ->
            onNavigateToBuilder(workoutId)
        }
    }

    // Auto-scroll to bottom when messages change
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
    ) {
        // ── Top Bar ──────────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "AI Coach",
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface
            )

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val score = vitals?.feelScore ?: 72
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    contentColor = MaterialTheme.colorScheme.onSurface
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        val scoreColor = when {
                            score >= 70 -> Color(0xFF4CAF50) // Green
                            score >= 40 -> Color(0xFFFFC107) // Amber
                            else -> Color(0xFFF44336) // Red
                        }
                        Box(modifier = Modifier.size(8.dp).background(scoreColor, CircleShape))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Feel $score",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                        )
                    }
                }

                // Overflow menu
                Box {
                    IconButton(onClick = { showMenu = true }, modifier = Modifier.size(36.dp)) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "Options",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Clear chat history") },
                            onClick = {
                                showMenu = false
                                viewModel.clearHistory()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Clear coach memory") },
                            onClick = {
                                showMenu = false
                                viewModel.clearMemory()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(if (showMemoryCard) "Hide memory card" else "Show memory card") },
                            onClick = {
                                showMenu = false
                                showMemoryCard = !showMemoryCard
                            }
                        )
                    }
                }
            }
        }

        // ── Memory Card ──────────────────────────────────────────────────────
        val hasMemory = memory.goals.isNotBlank() || memory.preferences.isNotBlank() || memory.notes.isNotBlank()
        if (hasMemory && showMemoryCard) {
            CoachMemoryCard(
                memory = memory,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }

        // ── Messages List ────────────────────────────────────────────────────
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 16.dp, top = 8.dp)
        ) {
            items(messages) { message ->
                if (message.text == SESSION_DIVIDER.text) {
                    // Session resume divider
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        HorizontalDivider(modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
                        Text(
                            text = "  resumed  ",
                            style = MaterialTheme.typography.labelSmall.copy(fontStyle = FontStyle.Italic),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        HorizontalDivider(modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
                    }
                } else {
                    MessageBubble(
                        message = message,
                        onSchedule = { title, duration ->
                            val intent = Intent(Intent.ACTION_INSERT).apply {
                                data = CalendarContract.Events.CONTENT_URI
                                putExtra(CalendarContract.Events.TITLE, title)
                                putExtra(CalendarContract.Events.DESCRIPTION, "Recommended by AI Coach")
                                putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, System.currentTimeMillis())
                                putExtra(CalendarContract.EXTRA_EVENT_END_TIME, System.currentTimeMillis() + duration * 60 * 1000)
                            }
                            context.startActivity(intent)
                        },
                        onCreateTemplate = { title, duration ->
                            viewModel.generateTemplateFromRecommendation(title, duration, message.text)
                        }
                    )
                }
            }
            if (isThinking) {
                item {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                        Surface(
                            shape = RoundedCornerShape(topStart = 4.dp, topEnd = 20.dp, bottomEnd = 20.dp, bottomStart = 20.dp),
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            modifier = Modifier.padding(vertical = 4.dp, horizontal = 8.dp)
                        ) {
                            Text(
                                text = "Coach is thinking...",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(16.dp),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }
        }

        // ── Input ────────────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 90.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = inputText,
                onValueChange = { inputText = it },
                modifier = Modifier.weight(1f),
                placeholder = {
                    Text(
                        text = "Ask your coach...",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium
                    )
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                    focusedTextColor = MaterialTheme.colorScheme.onSurface,
                    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer
                ),
                shape = RoundedCornerShape(28.dp),
                singleLine = true
            )
            IconButton(
                onClick = {
                    if (inputText.isNotBlank()) {
                        viewModel.sendMessage(inputText)
                        inputText = ""
                    }
                },
                modifier = Modifier
                    .size(48.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Send",
                    tint = MaterialTheme.colorScheme.onPrimary
                )
            }
        }
    }
}

// ── Memory Card ──────────────────────────────────────────────────────────────

@Composable
fun CoachMemoryCard(memory: CoachMemory, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "COACH KNOWS",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                ),
                color = MaterialTheme.colorScheme.primary
            )
            if (memory.goals.isNotBlank()) {
                Text("🎯 ${memory.goals}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
            }
            if (memory.preferences.isNotBlank()) {
                Text("⚙️ ${memory.preferences}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
            }
            if (memory.notes.isNotBlank()) {
                Text("📝 ${memory.notes}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

// ── Message Bubble ────────────────────────────────────────────────────────────

@Composable
fun MessageBubble(
    message: ChatMessage,
    onSchedule: (String, Int) -> Unit,
    onCreateTemplate: (String, Int) -> Unit
) {
    // Strip memory update tags from display
    val cleanText = message.text
        .replace(Regex("\\[MEMORY_UPDATE:[^\\]]*\\]"), "")
        .trim()

    val regex = Regex("\\*\\*\\[WORKOUT: (.*?) \\| (\\d+) min\\]\\*\\*")
    val match = regex.find(cleanText)

    val displayText = if (match != null) {
        cleanText.replace(match.value, "").trim()
    } else {
        cleanText
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.isUser) Arrangement.End else Arrangement.Start
    ) {
        Column(
            horizontalAlignment = if (message.isUser) Alignment.End else Alignment.Start,
            modifier = Modifier.fillMaxWidth(0.85f)
        ) {
            Surface(
                shape = if (message.isUser) {
                    RoundedCornerShape(topStart = 20.dp, topEnd = 4.dp, bottomEnd = 20.dp, bottomStart = 20.dp)
                } else {
                    RoundedCornerShape(topStart = 4.dp, topEnd = 20.dp, bottomEnd = 20.dp, bottomStart = 20.dp)
                },
                color = if (message.isUser) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainer
                },
                modifier = Modifier.padding(vertical = 4.dp)
            ) {
                Text(
                    text = displayText,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            if (!message.isUser && match != null) {
                val title = match.groupValues[1]
                val duration = match.groupValues[2].toIntOrNull() ?: 45

                val exercisesSummary = remember(message.text) {
                    val lines = message.text.lines()
                    val listItems = lines.map { it.trim() }.filter {
                        it.startsWith("-") || it.startsWith("*") || (it.isNotEmpty() && it.first().isDigit() && it.contains("."))
                    }.map { line ->
                        line.removePrefix("-").removePrefix("*").trim().replace(Regex("^\\d+\\.\\s*"), "")
                    }.filter { it.isNotBlank() }

                    if (listItems.isNotEmpty()) listItems.joinToString(" · ") else "Custom Workout Plan"
                }

                Card(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "$title — $duration min",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = exercisesSummary,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(bottom = 8.dp)
                ) {
                    OutlinedButton(
                        onClick = { onSchedule(title, duration) },
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        shape = RoundedCornerShape(20.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Text("Schedule", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold))
                    }

                    OutlinedButton(
                        onClick = { onCreateTemplate(title, duration) },
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        shape = RoundedCornerShape(20.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Text("Create template", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold))
                    }
                }
            }
        }
    }
}
