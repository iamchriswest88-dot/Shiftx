package com.example.shift.ui.gym

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Spa
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.shift.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GymScreen(
    onNewWorkout: () -> Unit,
    onEditWorkout: (String) -> Unit,
    onRunWorkout: (String) -> Unit,
    vm: GymViewModel = viewModel(factory = GymViewModel.factory())
) {
    val workouts by vm.workouts.collectAsStateWithLifecycle()
    var selectedCategoryFilter by remember { mutableStateOf("All") }

    val filteredWorkouts = remember(workouts, selectedCategoryFilter) {
        when (selectedCategoryFilter) {
            "Strength" -> workouts.filter { w ->
                val isFlow = w.category == "flow" || w.name.contains("Yoga", ignoreCase = true) || w.name.contains("Stretch", ignoreCase = true) || w.name.contains("Flow", ignoreCase = true) || w.name.contains("Mobility", ignoreCase = true)
                !isFlow
            }
            "Flow" -> workouts.filter { w ->
                w.category == "flow" || w.name.contains("Yoga", ignoreCase = true) || w.name.contains("Stretch", ignoreCase = true) || w.name.contains("Flow", ignoreCase = true) || w.name.contains("Mobility", ignoreCase = true)
            }
            else -> workouts
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ShiftBg)
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 12.dp, top = 16.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Gym & Flow",
                style = ScreenTitleStyle
            )
            // Orange circular add button
            IconButton(
                onClick = onNewWorkout,
                modifier = Modifier
                    .size(40.dp)
                    .background(ShiftAccent, CircleShape)
            ) {
                Icon(
                    Icons.Default.Add,
                    "New Workout",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(top = 8.dp, bottom = 110.dp)
        ) {
            // Category filter chips — pill style
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf("All", "Strength", "Flow").forEach { filter ->
                        val isActive = selectedCategoryFilter == filter
                        Surface(
                            modifier = Modifier.clickable { selectedCategoryFilter = filter },
                            shape = RoundedCornerShape(999.dp),
                            color = if (isActive) ShiftDarkSurface else ShiftCard,
                            contentColor = if (isActive) ShiftTextOnDark else ShiftTextSecondary
                        ) {
                            Text(
                                text = when (filter) {
                                    "All" -> "All (${workouts.size})"
                                    "Strength" -> "STRENGTH"
                                    "Flow" -> "FLOW"
                                    else -> filter
                                },
                                style = MicroLabelStyle.copy(
                                    fontSize = 11.sp,
                                    color = if (isActive) ShiftTextOnDark else ShiftTextSecondary,
                                    fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal
                                ),
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                            )
                        }
                    }
                }
            }

            // Section label
            item {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "SAVED WORKOUTS",
                    style = MicroLabelStyle.copy(letterSpacing = 2.sp),
                    modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
                )
            }

            if (filteredWorkouts.isEmpty()) {
                item {
                    Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        Text(
                            text = if (workouts.isEmpty()) "No saved workouts yet." else "No $selectedCategoryFilter workouts found.",
                            color = ShiftTextMuted,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            } else {
                items(filteredWorkouts, key = { it.id }) { workout ->
                    val isFlow = workout.category == "flow" || workout.name.contains("Yoga", ignoreCase = true) || workout.name.contains("Stretch", ignoreCase = true) || workout.name.contains("Flow", ignoreCase = true) || workout.name.contains("Mobility", ignoreCase = true) || workout.name.contains("Pilates", ignoreCase = true)

                    Card(
                        onClick = { onEditWorkout(workout.id) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = ShiftCard),
                        shape = RoundedCornerShape(22.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                // Icon circle
                                Box(
                                    modifier = Modifier
                                        .size(42.dp)
                                        .background(ShiftCardInset, CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = if (isFlow) Icons.Default.Spa else Icons.Default.FitnessCenter, 
                                        contentDescription = null, 
                                        tint = ShiftTextPrimary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                Spacer(Modifier.width(14.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text(
                                            text = workout.name,
                                            style = CardTitleStyle,
                                            color = ShiftTextPrimary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f, fill = false)
                                        )
                                        // Dotted badge
                                        Box(
                                            modifier = Modifier
                                                .border(1.dp, ShiftDotBorder, RoundedCornerShape(999.dp))
                                                .padding(horizontal = 8.dp, vertical = 3.dp)
                                        ) {
                                            Text(
                                                text = if (isFlow) "FLOW" else "STRENGTH",
                                                style = MicroLabelStyle.copy(
                                                    fontSize = 8.5.sp,
                                                    letterSpacing = 1.5.sp,
                                                    color = ShiftTextMuted
                                                )
                                            )
                                        }
                                    }
                                    if (workout.description.isNotBlank()) {
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = workout.description,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = ShiftTextMuted,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.width(8.dp))
                            // Orange circular play button
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .background(ShiftAccent, CircleShape)
                                    .clickable { onRunWorkout(workout.id) },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PlayArrow,
                                    contentDescription = "Run Workout",
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
