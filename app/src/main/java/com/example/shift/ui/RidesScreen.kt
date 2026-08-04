package com.example.shift.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Spa
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.Layers
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.shift.data.Activity
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RidesScreen(
    viewModel: MainViewModel,
    onActivityClick: (Activity) -> Unit
) {
    val activities by viewModel.activities.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val segmentCounts by viewModel.segmentCounts.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.reloadSegmentCounts()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { 
                    Text(
                        text = "Activity", 
                        style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold)
                    ) 
                },
                actions = {
                    IconButton(onClick = { viewModel.fetchActivities() }) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Refresh",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        }
    ) { pad ->
        Box(modifier = Modifier.fillMaxSize().padding(pad)) {
            if (isLoading && activities.isEmpty()) {
                com.example.shift.ui.components.FullScreenLoading()
            } else if (activities.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No activities found.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                // Group activities by Month Year (e.g., "OCTOBER 2024")
                val groupedActivities = remember(activities) {
                    activities.groupBy { activity ->
                        try {
                            val parsed = java.time.ZonedDateTime.parse(activity.start_date_local)
                            parsed.format(DateTimeFormatter.ofPattern("MMMM yyyy"))
                        } catch (e: Exception) {
                            try {
                                val parsed = java.time.LocalDateTime.parse(activity.start_date_local)
                                parsed.format(DateTimeFormatter.ofPattern("MMMM yyyy"))
                            } catch (e2: Exception) {
                                try {
                                    val parsed = LocalDate.parse(activity.start_date_local.take(10))
                                    parsed.format(DateTimeFormatter.ofPattern("MMMM yyyy"))
                                } catch (e3: Exception) {
                                    "Unknown"
                                }
                            }
                        }
                    }
                }

                // Calculate monthly totals
                val monthlySummaries = remember(activities) {
                    groupedActivities.mapValues { (_, monthActs) ->
                        val totalMiles = monthActs.sumOf { (it.distance ?: 0.0) * 0.000621371 }
                        val totalElevFeet = monthActs.sumOf { (it.total_elevation_gain ?: 0.0) * 3.28084 }
                        val totalSecs = monthActs.sumOf { (it.moving_time ?: 0).toLong() }
                        val hours = totalSecs / 3600
                        val mins = (totalSecs % 3600) / 60
                        val durationStr = if (hours > 0) "${hours}h ${mins}m" else "${mins}m"
                        Triple(totalMiles, totalElevFeet, durationStr)
                    }
                }

                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    contentPadding = PaddingValues(top = 16.dp, bottom = 24.dp)
                ) {
                    groupedActivities.forEach { (monthYear, monthActs) ->
                        val summary = monthlySummaries[monthYear]
                        if (summary != null) {
                            item(key = "summary_$monthYear") {
                                Spacer(modifier = Modifier.height(8.dp))
                                MonthlySummaryCard(
                                    monthYear = monthYear.uppercase(),
                                    totalMiles = summary.first,
                                    totalElevationFeet = summary.second,
                                    movingDurationStr = summary.third
                                )
                            }
                        }

                        // Activities for this month
                        items(monthActs, key = { it.id }) { activity ->
                            val count = segmentCounts[activity.id.toString()] ?: 0
                            ActivityItem(
                                activity = activity,
                                segmentCount = count,
                                onClick = { 
                                    onActivityClick(activity)
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun MonthlySummaryCard(
    monthYear: String,
    totalMiles: Double,
    totalElevationFeet: Double,
    movingDurationStr: String
) {
    val bgColor    = Color(0xFF5CD5FA)   // cyan-blue
    val darkText   = Color(0xFF0D1B1E)   // near-black
    val mutedText  = Color(0xFF1E3A42)   // dark grey-blue

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = bgColor)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            Text(
                text = monthYear,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 1.2.sp
                ),
                color = darkText
            )
            Spacer(modifier = Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Miles
                Column {
                    Text(
                        text = "%.1f".format(totalMiles),
                        style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                        color = darkText
                    )
                    Text(
                        text = "miles",
                        style = MaterialTheme.typography.labelSmall,
                        color = mutedText
                    )
                }

                // Elevation
                Column {
                    Text(
                        text = "%,.0f".format(totalElevationFeet),
                        style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                        color = darkText
                    )
                    Text(
                        text = "elevation ft",
                        style = MaterialTheme.typography.labelSmall,
                        color = mutedText
                    )
                }

                // Time
                Column {
                    Text(
                        text = movingDurationStr,
                        style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                        color = darkText
                    )
                    Text(
                        text = "moving time",
                        style = MaterialTheme.typography.labelSmall,
                        color = mutedText
                    )
                }
            }
        }
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityItem(
    activity: Activity,
    segmentCount: Int = 0,
    onClick: () -> Unit
) {
    val distanceMiles = (activity.distance ?: 0.0) * 0.000621371
    val isFlow = activity.type == "Yoga" || activity.name.contains("Yoga", ignoreCase = true) || activity.name.contains("Stretch", ignoreCase = true) || activity.name.contains("Flow", ignoreCase = true) || activity.name.contains("Mobility", ignoreCase = true) || activity.name.contains("Pilates", ignoreCase = true)
    val isGym = !isFlow && (activity.type == "WeightTraining" || activity.type == "Workout" || activity.id.startsWith("gym_") || activity.name.contains("Gym", ignoreCase = true) || activity.name.contains("Strength", ignoreCase = true) || activity.name.contains("Weight", ignoreCase = true) || activity.name.contains("Workout", ignoreCase = true))
    val isRun = !isFlow && !isGym && (activity.type == "Run" || activity.type == "VirtualRun" || activity.id.startsWith("hc_") || activity.name.contains("Run", ignoreCase = true))
    
    val dateStr = try {
        val parsed = java.time.ZonedDateTime.parse(activity.start_date_local)
        parsed.format(DateTimeFormatter.ofPattern("MMM dd"))
    } catch (e: Exception) {
        try {
            val parsed = java.time.LocalDateTime.parse(activity.start_date_local)
            parsed.format(DateTimeFormatter.ofPattern("MMM dd"))
        } catch (e2: Exception) {
            try {
                val parsed = LocalDate.parse(activity.start_date_local.take(10))
                parsed.format(DateTimeFormatter.ofPattern("MMM dd"))
            } catch (e3: Exception) {
                activity.start_date_local.take(10)
            }
        }
    }

    val subtitle = when {
        isFlow || isGym -> {
            val durationMins = (activity.moving_time ?: 0) / 60
            val tss = activity.icu_training_load?.let { " · TSS ${it.toInt()}" } ?: ""
            if (durationMins > 0) "$dateStr · ${durationMins} min$tss" else dateStr
        }
        isRun -> {
            val durationMins = (activity.moving_time ?: 0) / 60
            val durationSecs = (activity.moving_time ?: 0) % 60
            "$dateStr · %.1f mi · %d:%02d".format(distanceMiles, durationMins, durationSecs)
        }
        else -> {
            val elevationFeet = (activity.total_elevation_gain ?: 0.0) * 3.28084
            "$dateStr · %.1f mi · %,.0f ft".format(distanceMiles, elevationFeet)
        }
    }

    val (icon, bgContainer, onContainer) = when {
        isFlow -> Triple(Icons.Default.Spa, Color(0xFF81C784).copy(alpha = 0.2f), Color(0xFF81C784))
        isGym  -> Triple(Icons.Default.FitnessCenter, Color(0xFFCE93D8).copy(alpha = 0.2f), Color(0xFFCE93D8))
        isRun  -> Triple(Icons.Default.DirectionsRun, Color(0xFFFFD54F).copy(alpha = 0.2f), Color(0xFFFFD54F))
        else   -> Triple(Icons.AutoMirrored.Filled.DirectionsBike, Color(0xFF5CD5FA).copy(alpha = 0.2f), Color(0xFF5CD5FA))
    }
    
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(bgContainer, shape = CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = onContainer,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = activity.name,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (segmentCount > 0) {
                Spacer(modifier = Modifier.width(8.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    contentColor = MaterialTheme.colorScheme.onSurface
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Layers,
                            contentDescription = "Matched Routes",
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = "$segmentCount",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold)
                        )
                    }
                }
            }
        }
    }
}
