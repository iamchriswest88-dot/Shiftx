package com.example.shift.ui.builder

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.shift.data.model.Exercise
import com.example.shift.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BuilderScreen(
    category:  String,
    workoutId: String?,
    onBack:    () -> Unit,
    onRun:     (String) -> Unit,
    vm: BuilderViewModel = viewModel(
        key     = "builder_${category}_$workoutId",
        factory = BuilderViewModel.factory(category, workoutId)
    )
) {
    val exercises by vm.exercises.collectAsStateWithLifecycle()
    val accentColor = if (vm.isFlow) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primary

    Scaffold(
        
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "Back", tint = MaterialTheme.colorScheme.onSurface)
                    }
                },
                title = {
                    Text(
                        (if (vm.isFlow) "flow" else "gym") + if (workoutId == null) " builder" else " edit",
                        style = MaterialTheme.typography.displayMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                },
                actions = {
                    if (workoutId != null) {
                        IconButton(onClick = { vm.deleteWorkout(onBack) }) {
                            Icon(Icons.Default.Delete, "Delete", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    IconButton(onClick = { vm.save(onBack) }) {
                        Icon(Icons.Default.Check, "Save", tint = accentColor)
                    }
                    if (workoutId != null) {
                        IconButton(onClick = { vm.save { onRun(workoutId) } }) {
                            Icon(Icons.Default.PlayArrow, "Run", tint = accentColor)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            item {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value         = vm.workoutName,
                        onValueChange = vm::setName,
                        label         = { Text(if (vm.isFlow) "Flow Name" else "Workout Name", style = MaterialTheme.typography.labelSmall) },
                        singleLine    = true,
                        modifier      = Modifier.weight(1f),
                        shape         = RoundedCornerShape(6.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor   = accentColor,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                            focusedTextColor     = MaterialTheme.colorScheme.onSurface,
                            unfocusedTextColor   = MaterialTheme.colorScheme.onSurface,
                            cursorColor          = accentColor,
                            focusedLabelColor    = accentColor,
                            unfocusedLabelColor  = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                    OutlinedTextField(
                        value         = vm.workoutRounds.toString(),
                        onValueChange = { vm.setRounds(it.toIntOrNull() ?: 1) },
                        label         = { Text("Rounds", style = MaterialTheme.typography.labelSmall) },
                        singleLine    = true,
                        modifier      = Modifier.width(80.dp),
                        shape         = RoundedCornerShape(6.dp),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor   = accentColor,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                            focusedTextColor     = MaterialTheme.colorScheme.onSurface,
                            unfocusedTextColor   = MaterialTheme.colorScheme.onSurface,
                            cursorColor          = accentColor,
                            focusedLabelColor    = accentColor,
                            unfocusedLabelColor  = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                }
            }
            if (vm.workoutDescription.isNotBlank()) {
                item {
                    OutlinedTextField(
                        value         = vm.workoutDescription,
                        onValueChange = vm::setDescription,
                        label         = { Text("AI Overview", style = MaterialTheme.typography.labelSmall) },
                        modifier      = Modifier.fillMaxWidth(),
                        shape         = RoundedCornerShape(6.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor   = accentColor,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                            focusedTextColor     = MaterialTheme.colorScheme.onSurface,
                            unfocusedTextColor   = MaterialTheme.colorScheme.onSurfaceVariant,
                            cursorColor          = accentColor,
                            focusedLabelColor    = accentColor,
                            unfocusedLabelColor  = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                }
            }
            itemsIndexed(vm.steps, key = { _, s -> s.id }) { index, step ->
                StepCard(
                    step        = step,
                    isFlow      = vm.isFlow,
                    exercises   = exercises,
                    accentColor = accentColor,
                    suggestedWeight = step.exercise?.let { vm.suggestedWeights[it.id] },
                    onUpdate    = { vm.updateStep(index, it) },
                    onDelete    = { vm.removeStep(index) },
                    onAddCustom = { name, area, cb -> vm.addCustomExercise(name, area, cb) },
                    onAiSwap    = { vm.swapExerciseAI(index) }
                )
            }
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .clickable { vm.addStep() }
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Add, null, tint = MaterialTheme.colorScheme.onSurface)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "Add Step", 
                            style = MaterialTheme.typography.labelMedium, 
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun StepCard(
    step:        StepDraft,
    isFlow:      Boolean,
    exercises:   List<Exercise>,
    accentColor: Color,
    suggestedWeight: Float?,
    onUpdate:    (StepDraft) -> Unit,
    onDelete:    () -> Unit,
    onAddCustom: (String, String, (Exercise) -> Unit) -> Unit,
    onAiSwap:    () -> Unit,
) {
    var showPicker   by remember { mutableStateOf(false) }
    var pickerSearch by remember { mutableStateOf("") }

    if (showPicker) {
        ExercisePickerDialog(
            exercises      = exercises,
            search         = pickerSearch,
            accentColor    = accentColor,
            onSearchChange = { pickerSearch = it },
            onSelect       = { ex -> onUpdate(step.copy(exercise = ex)); showPicker = false; pickerSearch = "" },
            onDismiss      = { showPicker = false; pickerSearch = "" }
        )
    }

    Surface(
        color    = MaterialTheme.colorScheme.surfaceContainer,
        shape    = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { showPicker = true }
                        .padding(vertical = 8.dp)
                ) {
                    Column {
                        Text(
                            text = step.exercise?.name ?: "Tap to select exercise",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (step.exercise != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (suggestedWeight != null) {
                            Text(
                                "Suggested: ${String.format("%.1f", suggestedWeight)}kg",
                                style = MaterialTheme.typography.labelSmall,
                                color = accentColor
                            )
                        }
                    }
                }
                if (step.isSwapping) {
                    Box(modifier = Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = accentColor, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    }
                } else {
                    IconButton(onClick = onAiSwap, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.AutoAwesome, "AI Swap", tint = accentColor, modifier = Modifier.size(16.dp))
                    }
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Delete, "Remove", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StepCounter(
                    label   = if (isFlow) "ROUNDS" else "SETS",
                    value   = step.sets,
                    onMinus = { if (step.sets > 1) onUpdate(step.copy(sets = step.sets - 1)) },
                    onPlus  = { onUpdate(step.copy(sets = step.sets + 1)) },
                    modifier = Modifier.weight(1f)
                )
                StepCounter(
                    label   = if (isFlow) "HOLD S" else "WORK S",
                    value   = step.workSec,
                    onMinus = { if (step.workSec > 5) onUpdate(step.copy(workSec = step.workSec - 5)) },
                    onPlus  = { onUpdate(step.copy(workSec = step.workSec + 5)) },
                    modifier = Modifier.weight(1f)
                )
                StepCounter(
                    label   = "REST S",
                    value   = step.restSec,
                    onMinus = { if (step.restSec >= 5) onUpdate(step.copy(restSec = step.restSec - 5)) },
                    onPlus  = { onUpdate(step.copy(restSec = step.restSec + 5)) },
                    modifier = Modifier.weight(1f)
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("BOTH SIDES", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Switch(
                    checked         = step.sides,
                    onCheckedChange = { onUpdate(step.copy(sides = it)) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = accentColor, 
                        checkedTrackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        uncheckedBorderColor = Color.Transparent,
                        checkedBorderColor = Color.Transparent
                    )
                )
            }
            if (step.sides) {
                StepCounter(
                    label    = "SWAP S",
                    value    = step.swapSec,
                    onMinus  = { if (step.swapSec >= 5) onUpdate(step.copy(swapSec = step.swapSec - 5)) },
                    onPlus   = { onUpdate(step.copy(swapSec = step.swapSec + 5)) },
                    modifier = Modifier.fillMaxWidth(0.38f)
                )
            }
            Text(step.summary(isFlow), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun StepCounter(label: String, value: Int, onMinus: () -> Unit, onPlus: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onMinus, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.Remove, "-", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                "$value",
                style    = MaterialTheme.typography.labelLarge.copy(fontSize = 16.sp),
                color    = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.width(36.dp),
                textAlign = TextAlign.Center
            )
            IconButton(onClick = onPlus, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.Add, "+", tint = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

@Composable
fun ExercisePickerDialog(
    exercises:      List<Exercise>,
    search:         String,
    accentColor:    Color,
    onSearchChange: (String) -> Unit,
    onSelect:       (Exercise) -> Unit,
    onDismiss:      () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor   = MaterialTheme.colorScheme.surfaceContainer,
        shape            = RoundedCornerShape(6.dp),
        title = {
            OutlinedTextField(
                value         = search,
                onValueChange = onSearchChange,
                label         = { Text("SEARCH", style = MaterialTheme.typography.labelSmall) },
                singleLine    = true,
                modifier      = Modifier.fillMaxWidth(),
                shape         = RoundedCornerShape(4.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor   = accentColor,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                    focusedTextColor     = MaterialTheme.colorScheme.onSurface,
                    unfocusedTextColor   = MaterialTheme.colorScheme.onSurface,
                    cursorColor          = accentColor,
                    focusedLabelColor    = accentColor,
                    unfocusedLabelColor  = MaterialTheme.colorScheme.onSurfaceVariant
                )
            )
        },
        text = {
            val filtered = exercises.filter { it.name.contains(search, ignoreCase = true) }
            androidx.compose.foundation.lazy.LazyColumn(
                modifier = Modifier.heightIn(max = 360.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                items(filtered) { ex ->
                    TextButton(onClick = { onSelect(ex) }, modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(ex.name, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
                            Text(ex.area, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("CANCEL", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    )
}
