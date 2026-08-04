package com.example.shift.ui.runner

import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.animation.core.animateFloat
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.shift.theme.*
import kotlinx.coroutines.launch

@Composable
fun RunnerScreen(
    workoutId: String,
    onBack:    () -> Unit,
    vm: RunnerViewModel = viewModel(
        key     = "runner_$workoutId",
        factory = RunnerViewModel.factory(workoutId)
    )
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var muted by remember { mutableStateOf(false) }

    // Keep screen on for the duration of the session
    val context = LocalContext.current
    DisposableEffect(Unit) {
        val window = (context as? android.app.Activity)?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    when {
        state.isLoading  -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        }
        state.showRpePrompt -> {
            RpePromptOverlay(
                onSubmit = { rpe -> vm.completeWorkoutWithRpe(rpe) },
                onSkip = { vm.dismissRpePrompt() }
            )
        }
        state.isFinished -> {
            CompletionScreen(
                category   = state.category,
                onDone     = onBack
            )
        }
        else -> ActiveRunnerScreen(state, muted,
            onPauseResume = { if (state.isPaused) vm.resume() else vm.pause() },
            onSkip        = vm::skip,
            onMute        = { muted = !muted; vm.audioMuted = muted },
            onExit        = onBack,
            onLogWeight   = vm::logWeight,
            onDismissWeightPrompt = vm::dismissWeightPrompt
        )
    }
}

@Composable
fun ActiveRunnerScreen(
    state:        RunnerState,
    muted:        Boolean,
    onPauseResume: () -> Unit,
    onSkip:       () -> Unit,
    onMute:       () -> Unit,
    onExit:       () -> Unit,
    onLogWeight:  (Float, Int) -> Unit,
    onDismissWeightPrompt: () -> Unit
) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier.fillMaxSize().systemBarsPadding().padding(24.dp), // 24dp screen edge padding
        ) {
            val isFlow = state.category == "flow" || state.workoutName.contains("Yoga", ignoreCase = true) || state.workoutName.contains("Stretch", ignoreCase = true) || state.workoutName.contains("Flow", ignoreCase = true) || state.workoutName.contains("Mobility", ignoreCase = true)
            val phaseColor = if (isFlow) Color(0xFF81C784) else Color(0xFFCE93D8)

            // Close button
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onExit) {
                    Icon(Icons.Default.Close, "Exit", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // Central clustered content
            Column(
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(320.dp)) {
                    val targetProgress = if (state.totalSeconds > 0) 1f - state.secondsLeft.toFloat() / state.totalSeconds else 0f
                    val progress by androidx.compose.animation.core.animateFloatAsState(
                        targetValue = targetProgress,
                        animationSpec = androidx.compose.animation.core.tween(durationMillis = 1000, easing = androidx.compose.animation.core.LinearEasing),
                        label = "progress"
                    )
                    
                    val infiniteTransition = androidx.compose.animation.core.rememberInfiniteTransition(label = "wave")
                    val phaseOffset by infiniteTransition.animateFloat(
                        initialValue = 0f,
                        targetValue = 1f,
                        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                            animation = androidx.compose.animation.core.tween(12000, easing = androidx.compose.animation.core.LinearEasing),
                            repeatMode = androidx.compose.animation.core.RepeatMode.Restart
                        ),
                        label = "phase"
                    )

                    val primaryColor = phaseColor
                    val trackColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    
                    androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                        val strokeWidth = 8.dp.toPx()
                        val radius = size.minDimension / 2 - strokeWidth
                        val center = androidx.compose.ui.geometry.Offset(size.width / 2, size.height / 2)
                        
                        val sweep = progress * 360f
                        if (sweep < 355f) {
                            drawArc(
                                color = trackColor,
                                startAngle = -90f + sweep + 5f,
                                sweepAngle = 360f - sweep - 5f,
                                useCenter = false,
                                style = androidx.compose.ui.graphics.drawscope.Stroke(width = strokeWidth, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                            )
                        }
                        
                        val path = androidx.compose.ui.graphics.Path()
                        val amplitude = 7.dp.toPx()
                        val waves = 14
                        
                        if (sweep > 0f) {
                            for (i in 0..sweep.toInt()) {
                                val angle = -90f + i
                                val rad = java.lang.Math.toRadians(angle.toDouble())
                                val waveOffset = kotlin.math.sin((i.toDouble() / 360.0 - phaseOffset) * waves * 2 * java.lang.Math.PI) * amplitude
                                val currentRadius = radius + waveOffset
                                val x = center.x + (currentRadius * kotlin.math.cos(rad)).toFloat()
                                val y = center.y + (currentRadius * kotlin.math.sin(rad)).toFloat()
                                
                                if (i == 0) {
                                    path.moveTo(x, y)
                                } else {
                                    path.lineTo(x, y)
                                }
                            }
                            if (sweep > sweep.toInt()) {
                                val angle = -90f + sweep
                                val rad = java.lang.Math.toRadians(angle.toDouble())
                                val waveOffset = kotlin.math.sin((sweep.toDouble() / 360.0 - phaseOffset) * waves * 2 * java.lang.Math.PI) * amplitude
                                val currentRadius = radius + waveOffset
                                val x = center.x + (currentRadius * kotlin.math.cos(rad)).toFloat()
                                val y = center.y + (currentRadius * kotlin.math.sin(rad)).toFloat()
                                path.lineTo(x, y)
                            }
                            drawPath(
                                path = path,
                                color = primaryColor,
                                style = androidx.compose.ui.graphics.drawscope.Stroke(width = strokeWidth, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round)
                            )
                        }
                    }
                    
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Surface(
                            shape = RoundedCornerShape(percent = 50),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                        ) {
                            Text(
                                state.phaseLabel,
                                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 2.sp),
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                            )
                        }
                        Text(
                            text = state.secondsLeft.toString(),
                            style = MaterialTheme.typography.displayLarge.copy(fontSize = 88.sp),
                            color = primaryColor
                        )
                        val setStr = if (state.totalRounds > 1) "RD ${state.currentRound}/${state.totalRounds} • SET ${state.currentSet}/${state.totalSets}" else "SET ${state.currentSet} / ${state.totalSets}"
                        Text(
                            setStr,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                
                Spacer(Modifier.height(32.dp))

                // Exercise name + side
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    if (state.phaseLabel == "REST" || state.phaseLabel == "SWAP") {
                        Text(
                            "UP NEXT",
                            style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 2.sp),
                            color = phaseColor
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    Text(
                        state.exerciseName,
                        style = MaterialTheme.typography.displayMedium,
                        color     = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center
                    )
                    if (state.suggestedWeight != null) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            String.format("Suggested: %.1fkg", state.suggestedWeight),
                            style = MaterialTheme.typography.labelSmall,
                            color = phaseColor
                        )
                    }
                    if (state.sideLabel.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(state.sideLabel, style = MaterialTheme.typography.labelMedium, color = phaseColor)
                    }
                    if (state.isInstructionLoading) {
                        Spacer(Modifier.height(16.dp))
                        CircularProgressIndicator(color = phaseColor, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else if (state.exerciseInstruction.isNotBlank()) {
                        Spacer(Modifier.height(16.dp))
                        Text(
                            state.exerciseInstruction, 
                            style = MaterialTheme.typography.labelSmall, 
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 24.dp)
                        )
                    }
                }

                Spacer(Modifier.height(40.dp))

                // Bottom grouped controls and progress
                Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
                    if (state.showWeightPrompt) {
                        var weightText by remember { mutableStateOf("") }
                        var feeling by remember { mutableStateOf(2) } // 2=Good

                        Card(
                            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
                        ) {
                            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text("Log Weight: ${state.promptExerciseName}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface)
                                
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    OutlinedTextField(
                                        value = weightText,
                                        onValueChange = { weightText = it },
                                        modifier = Modifier.weight(1f),
                                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                                            unfocusedBorderColor = MaterialTheme.colorScheme.outline
                                        ),
                                        shape = RoundedCornerShape(8.dp)
                                    )
                                    
                                    Button(
                                        onClick = {
                                            val w = weightText.toFloatOrNull()
                                            if (w != null) onLogWeight(w, feeling)
                                            else onDismissWeightPrompt()
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = phaseColor, contentColor = MaterialTheme.colorScheme.onPrimary)
                                    ) {
                                        Text("Save")
                                    }
                                }

                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    FilterChip(
                                        selected = feeling == 1,
                                        onClick = { feeling = 1 },
                                        label = { Text("Too Heavy", style = MaterialTheme.typography.labelSmall) },
                                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = phaseColor, selectedLabelColor = MaterialTheme.colorScheme.onPrimary)
                                    )
                                    FilterChip(
                                        selected = feeling == 2,
                                        onClick = { feeling = 2 },
                                        label = { Text("Good", style = MaterialTheme.typography.labelSmall) },
                                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = phaseColor, selectedLabelColor = MaterialTheme.colorScheme.onPrimary)
                                    )
                                    FilterChip(
                                        selected = feeling == 3,
                                        onClick = { feeling = 3 },
                                        label = { Text("Too Light", style = MaterialTheme.typography.labelSmall) },
                                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = phaseColor, selectedLabelColor = MaterialTheme.colorScheme.onPrimary)
                                    )
                                }
                            }
                        }
                    }

                    // Controls
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = onMute,
                            modifier = Modifier.size(56.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape)
                        ) {
                            Icon(
                                if (muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                                "Mute",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        
                        FilledIconButton(
                            onClick = onPauseResume,
                            colors  = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary),
                            modifier = Modifier.size(88.dp),
                            shape   = RoundedCornerShape(28.dp)
                        ) {
                            Icon(
                                if (state.isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                                if (state.isPaused) "Resume" else "Pause",
                                modifier = Modifier.size(40.dp)
                            )
                        }
                        
                        IconButton(
                            onClick = onSkip,
                            modifier = Modifier.size(56.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape)
                        ) {
                            Icon(Icons.Default.SkipNext, "Skip", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}
@Composable
fun CompletionScreen(
    category:   String,
    onDone:     () -> Unit
) {
    val accentColor = if (category == "gym") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primary

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(32.dp),
            verticalArrangement   = Arrangement.Center,
            horizontalAlignment   = Alignment.CenterHorizontally
        ) {
            Text("Session Complete", style = MaterialTheme.typography.displayMedium, color = accentColor, textAlign = TextAlign.Center)
            
            Spacer(Modifier.height(48.dp))
            Button(
                onClick = onDone, 
                shape   = RoundedCornerShape(6.dp),
                colors  = ButtonDefaults.buttonColors(containerColor = accentColor, contentColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Done", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
fun RpePromptOverlay(onSubmit: (Float) -> Unit, onSkip: () -> Unit) {
    var rpeValue by remember { mutableStateOf(5f) }

    Box(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                Text(
                    "Workout Complete",
                    style = MaterialTheme.typography.displayMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                
                Text(
                    "How hard was that session?",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )

                Text(
                    text = "${rpeValue.toInt()} / 10",
                    style = MaterialTheme.typography.displayLarge,
                    color = MaterialTheme.colorScheme.primary
                )

                Slider(
                    value = rpeValue,
                    onValueChange = { rpeValue = it },
                    valueRange = 1f..10f,
                    steps = 8,
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.primary,
                        activeTrackColor = MaterialTheme.colorScheme.primary,
                        inactiveTrackColor = MaterialTheme.colorScheme.outline
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    TextButton(onClick = onSkip) {
                        Text("Skip", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Button(
                        onClick = { onSubmit(rpeValue) },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Text("Save & Upload", color = MaterialTheme.colorScheme.surface)
                    }
                }
            }
        }
    }
}
