package com.example.shift.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Spa
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RunStatsScreen(
    activityId: String,
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    val activities by viewModel.activities.collectAsState()
    val activity = activities.find { it.id == activityId }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(activity?.name ?: "Run Stats", style = MaterialTheme.typography.headlineMedium) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = MaterialTheme.colorScheme.onSurface)
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { paddingValues ->
        if (activity == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Run not found", color = MaterialTheme.colorScheme.onSurface)
            }
            return@Scaffold
        }

        val scrollState = rememberScrollState()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 24.dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            val isFlow = activity.type == "Yoga" || activity.name.contains("Yoga", ignoreCase = true) || activity.name.contains("Stretch", ignoreCase = true) || activity.name.contains("Flow", ignoreCase = true) || activity.name.contains("Mobility", ignoreCase = true)
            val isGym  = !isFlow && (activity.type == "WeightTraining" || activity.type == "Workout" || activity.id.startsWith("gym_") || activity.name.contains("Gym", ignoreCase = true) || activity.name.contains("Strength", ignoreCase = true) || activity.name.contains("Workout", ignoreCase = true))
            val isRun  = !isFlow && !isGym && (activity.type == "Run" || activity.type == "VirtualRun" || activity.id.startsWith("hc_") || activity.name.contains("Run", ignoreCase = true))

            val (themeColor, icon) = when {
                isFlow -> Pair(Color(0xFF81C784), Icons.Default.Spa)
                isGym  -> Pair(Color(0xFFCE93D8), Icons.Default.FitnessCenter)
                isRun  -> Pair(Color(0xFFFFD54F), Icons.Default.DirectionsRun)
                else   -> Pair(Color(0xFFC084FC), Icons.AutoMirrored.Filled.DirectionsBike)
            }

            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 16.dp)) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(themeColor.copy(alpha = 0.2f))
                        .padding(6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icon, contentDescription = null, tint = themeColor, modifier = Modifier.size(32.dp))
                }
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    Text(activity.name, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
                    val formattedDate = try {
                        val parsed = LocalDateTime.parse(activity.start_date_local, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                        parsed.format(DateTimeFormatter.ofPattern("EEEE, MMM d, yyyy 'at' h:mm a"))
                    } catch (e: Exception) {
                        activity.start_date_local
                    }
                    Text(formattedDate, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
                val distanceText = activity.distance?.let { String.format("%.2f km", it / 1000) } ?: "--"
                RunStatCard("Distance", distanceText, Modifier.weight(1f))

                val timeText = activity.moving_time?.let {
                    val hrs = it / 3600
                    val mins = (it % 3600) / 60
                    val secs = it % 60
                    if (hrs > 0) String.format("%d:%02d:%02d", hrs, mins, secs) else String.format("%d:%02d", mins, secs)
                } ?: "--"
                RunStatCard("Time", timeText, Modifier.weight(1f))
            }

            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
                val distanceKm = (activity.distance ?: 0.0) / 1000.0
                val paceSecondsPerKm = if (distanceKm > 0 && activity.moving_time != null) (activity.moving_time / distanceKm).toInt() else 0
                val pace = if (paceSecondsPerKm > 0) {
                    val m = paceSecondsPerKm / 60
                    val s = paceSecondsPerKm % 60
                    String.format("%d:%02d /km", m, s)
                } else "--"
                RunStatCard("Avg Pace", pace, Modifier.weight(1f))

                val calsText = activity.calories?.let { String.format("%.0f kcal", it) } ?: "--"
                RunStatCard("Calories", calsText, Modifier.weight(1f))
            }

            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
                val stepsText = activity.steps?.toString() ?: "--"
                RunStatCard("Steps", stepsText, Modifier.weight(1f))

                val elevText = activity.total_elevation_gain?.let { String.format("%.0f m", it) } ?: "--"
                RunStatCard("Elevation", elevText, Modifier.weight(1f))
            }

            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
                val hrAvg = activity.average_heartrate?.let { String.format("%.0f bpm", it) } ?: "--"
                RunStatCard("Avg HR", hrAvg, Modifier.weight(1f))

                val hrMax = activity.max_heartrate?.let { String.format("%.0f bpm", it) } ?: "--"
                RunStatCard("Max HR", hrMax, Modifier.weight(1f))
            }

            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
                val speedAvg = activity.average_speed?.let { String.format("%.1f km/h", it * 3.6) } ?: "--"
                RunStatCard("Avg Speed", speedAvg, Modifier.weight(1f))

                val speedMax = activity.max_speed?.let { String.format("%.1f km/h", it * 3.6) } ?: "--"
                RunStatCard("Max Speed", speedMax, Modifier.weight(1f))
            }

            Spacer(modifier = Modifier.height(16.dp))
            
            var elevationProfile by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<List<Pair<Double, Double>>?>(null) }
            var isLoadingProfile by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(true) }

            androidx.compose.runtime.LaunchedEffect(activity.id) {
                isLoadingProfile = true
                elevationProfile = viewModel.fetchElevationProfile(activity.id)
                isLoadingProfile = false
            }

            if (isLoadingProfile) {
                Box(modifier = Modifier.fillMaxWidth().height(150.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = themeColor)
                }
            } else if (elevationProfile != null && elevationProfile!!.isNotEmpty()) {
                val profile = elevationProfile!!
                val maxElev = profile.maxOf { it.second }
                val minElev = profile.minOf { it.second }
                val elevRange = if (maxElev == minElev) 1.0 else maxElev - minElev

                Text("Elevation Profile", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.onSurface)
                androidx.compose.foundation.Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(150.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .padding(16.dp)
                ) {
                    val width = size.width
                    val height = size.height
                    
                    val minAlt = profile.minOf { it.second }
                    val maxAlt = profile.maxOf { it.second }
                    val maxDist = profile.last().first
                    
                    val altRange = if (maxAlt - minAlt == 0.0) 1.0 else maxAlt - minAlt
                    val distRange = if (maxDist == 0.0) 1.0 else maxDist
                    
                    val fillPath = androidx.compose.ui.graphics.Path()
                    fillPath.moveTo(0f, height)
                    
                    profile.forEach { (dist, alt) ->
                        val x = ((dist / distRange) * width).toFloat()
                        val y = height - (((alt - minAlt) / altRange) * height).toFloat()
                        fillPath.lineTo(x, y)
                    }
                    fillPath.lineTo(width, height)
                    fillPath.close()
                    
                    drawPath(
                        path = fillPath,
                        brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                            colors = listOf(themeColor.copy(alpha = 0.4f), Color.Transparent)
                        )
                    )
                    
                    val linePath = androidx.compose.ui.graphics.Path()
                    profile.forEachIndexed { index, (dist, alt) ->
                        val x = ((dist / distRange) * width).toFloat()
                        val y = height - (((alt - minAlt) / altRange) * height).toFloat()
                        if (index == 0) linePath.moveTo(x, y) else linePath.lineTo(x, y)
                    }
                    drawPath(
                        path = linePath,
                        color = themeColor,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx())
                    )
                }
            } else {
                Text("Elevation Profile", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.onSurface)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(150.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                    contentAlignment = Alignment.Center
                ) {
                    Text("No elevation data available", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun RunStatCard(title: String, value: String, modifier: Modifier = Modifier) {
    ElevatedCard(
        modifier = modifier,
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.Start
        ) {
            Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(4.dp))
            Text(value, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.onSurface)
        }
    }
}
