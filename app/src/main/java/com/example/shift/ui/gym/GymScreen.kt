package com.example.shift.ui.gym

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Spa
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.AutoAwesome
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GymScreen(
    onNewWorkout: () -> Unit,
    onEditWorkout: (String) -> Unit,
    onRunWorkout: (String) -> Unit,
    vm: GymViewModel = viewModel(factory = GymViewModel.factory())
) {
    val workouts by vm.workouts.collectAsStateWithLifecycle()
    var selectedCategoryFilter by remember { mutableStateOf("All") } // "All", "Strength", "Flow"

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

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { 
                    Text(
                        text = "Gym & Flow", 
                        style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold)
                    ) 
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onNewWorkout,
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                shape = CircleShape
            ) {
                Icon(Icons.Default.Add, "New Workout")
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(top = 16.dp, bottom = 80.dp)
        ) {
            // Category Filter Chips
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = selectedCategoryFilter == "All",
                        onClick = { selectedCategoryFilter = "All" },
                        label = { Text("All (${workouts.size})") }
                    )
                    FilterChip(
                        selected = selectedCategoryFilter == "Strength",
                        onClick = { selectedCategoryFilter = "Strength" },
                        label = { Text("Strength 🏋️") },
                        leadingIcon = { Icon(Icons.Default.FitnessCenter, null, modifier = Modifier.size(16.dp)) }
                    )
                    FilterChip(
                        selected = selectedCategoryFilter == "Flow",
                        onClick = { selectedCategoryFilter = "Flow" },
                        label = { Text("Flow & Stretch 🪷") },
                        leadingIcon = { Icon(Icons.Default.Spa, null, modifier = Modifier.size(16.dp)) }
                    )
                }
            }

            item {
                Text(
                    text = "SAVED WORKOUTS", 
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.sp), 
                    color = MaterialTheme.colorScheme.onSurfaceVariant, 
                    modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
                )
            }

            if (filteredWorkouts.isEmpty()) {
                item {
                    Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        Text(
                            text = if (workouts.isEmpty()) "No saved workouts yet." else "No $selectedCategoryFilter workouts found.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            } else {
                items(filteredWorkouts, key = { it.id }) { workout ->
                    val isFlow = workout.category == "flow" || workout.name.contains("Yoga", ignoreCase = true) || workout.name.contains("Stretch", ignoreCase = true) || workout.name.contains("Flow", ignoreCase = true) || workout.name.contains("Mobility", ignoreCase = true) || workout.name.contains("Pilates", ignoreCase = true)
                    val itemColor = if (isFlow) Color(0xFF81C784) else Color(0xFFCE93D8)

                    Card(
                        onClick = { onEditWorkout(workout.id) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .background(itemColor.copy(alpha = 0.2f), CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = if (isFlow) Icons.Default.Spa else Icons.Default.FitnessCenter, 
                                        contentDescription = null, 
                                        tint = itemColor,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                Spacer(Modifier.width(16.dp))
                                Column {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text(
                                            text = workout.name,
                                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f, fill = false)
                                        )
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = itemColor.copy(alpha = 0.25f)
                                        ) {
                                            Text(
                                                text = if (isFlow) "FLOW" else "STRENGTH",
                                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold),
                                                color = itemColor,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                    if (workout.description.isNotBlank()) {
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = workout.description,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.width(8.dp))
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .background(itemColor.copy(alpha = 0.2f), CircleShape)
                                    .clickable { onRunWorkout(workout.id) },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PlayArrow,
                                    contentDescription = "Run Workout",
                                    tint = itemColor,
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
