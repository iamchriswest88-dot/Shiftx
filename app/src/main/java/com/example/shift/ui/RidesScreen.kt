package com.example.shift.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Spa
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.Layers
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.shift.data.Activity
import com.example.shift.theme.*
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
    // Only one ride's segment results open at a time, so the list stays scannable.
    var expandedActivityId by remember { mutableStateOf<String?>(null) }
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ShiftBg)
    ) {
        // Header row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 12.dp, top = 16.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Activity",
                style = ScreenTitleStyle
            )
            // Circular refresh button
            IconButton(
                onClick = { viewModel.fetchActivities() },
                modifier = Modifier
                    .size(40.dp)
                    .background(ShiftCardInset, CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "Refresh",
                    tint = ShiftTextSecondary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

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
                    color = ShiftTextMuted
                )
            }
        } else {
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
                modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(top = 8.dp, bottom = 110.dp)
            ) {
                groupedActivities.forEach { (monthYear, monthActs) ->
                    val summary = monthlySummaries[monthYear]
                    if (summary != null) {
                        item(key = "summary_$monthYear") {
                            Spacer(modifier = Modifier.height(4.dp))
                            MonthlySummaryCard(
                                monthYear = monthYear.uppercase(),
                                totalMiles = summary.first,
                                totalElevationFeet = summary.second,
                                movingDurationStr = summary.third
                            )
                        }
                    }

                    items(monthActs, key = { it.id }) { activity ->
                        val count = segmentCounts[activity.id.toString()] ?: 0
                        ActivityItem(
                            activity = activity,
                            segmentCount = count,
                            expanded = expandedActivityId == activity.id,
                            onClick = {
                                onActivityClick(activity)
                            },
                            onSegmentClick = if (count > 0) {
                                {
                                    expandedActivityId =
                                        if (expandedActivityId == activity.id) null else activity.id
                                }
                            } else null
                        )
                        if (expandedActivityId == activity.id) {
                            SegmentResultsPanel(viewModel = viewModel, activityId = activity.id)
                        }
                    }
                }
            }
        }
    }
}

/**
 * How each segment inside a ride placed against every other attempt at it.
 *
 * Loaded on expand rather than up front — building this for every ride in the list
 * would read the whole match cache once per row.
 */
@Composable
fun SegmentResultsPanel(viewModel: MainViewModel, activityId: String) {
    var results by remember(activityId) { mutableStateOf<List<MainViewModel.SegmentResult>?>(null) }

    LaunchedEffect(activityId) {
        results = viewModel.segmentResultsFor(activityId)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, bottom = 8.dp)
    ) {
        val current = results
        when {
            current == null -> {
                Text(
                    text = "LOADING RESULTS",
                    style = MicroLabelStyle,
                    color = ShiftTextMuted,
                    modifier = Modifier.padding(vertical = 10.dp)
                )
            }
            current.isEmpty() -> {
                Text(
                    text = "NO SEGMENT RESULTS",
                    style = MicroLabelStyle,
                    color = ShiftTextMuted,
                    modifier = Modifier.padding(vertical = 10.dp)
                )
            }
            else -> current.forEach { result -> SegmentResultRow(result) }
        }
    }
}

@Composable
private fun SegmentResultRow(result: MainViewModel.SegmentResult) {
    val timeStr = "%d:%02d".format(result.timeSeconds / 60, result.timeSeconds % 60)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .background(ShiftCardInset, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = result.courseName,
                style = CardTitleStyle,
                color = ShiftTextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            // A rider wants the gap to their best more than the raw placing.
            val detail = when {
                result.isPr -> "PERSONAL BEST"
                result.deltaToPrSeconds > 0 -> "+${result.deltaToPrSeconds}s OFF BEST"
                else -> "MATCHED BEST"
            }
            Text(
                text = if (result.estimated) "$detail · ESTIMATED" else detail,
                style = MicroLabelStyle,
                color = if (result.isPr) ShiftOrange else ShiftTextMuted
            )
        }

        Spacer(modifier = Modifier.width(10.dp))

        Text(
            text = timeStr,
            style = CardTitleStyle,
            color = ShiftTextPrimary
        )

        Spacer(modifier = Modifier.width(10.dp))

        // Placing pill, filled when it is a win so a best stands out down the list.
        val isWin = result.position == 1
        Box(
            modifier = Modifier
                .then(
                    if (isWin) Modifier.background(ShiftOrange, RoundedCornerShape(999.dp))
                    else Modifier.border(1.dp, ShiftDotBorder, RoundedCornerShape(999.dp))
                )
                .padding(horizontal = 10.dp, vertical = 5.dp)
        ) {
            Text(
                text = "${ordinalOf(result.position)}/${result.fieldSize}",
                style = MicroLabelStyle.copy(fontWeight = FontWeight.Bold),
                color = if (isWin) Color.White else ShiftTextSecondary
            )
        }
    }
}

private fun ordinalOf(n: Int): String {
    val suffix = when {
        n % 100 in 11..13 -> "th"
        n % 10 == 1 -> "st"
        n % 10 == 2 -> "nd"
        n % 10 == 3 -> "rd"
        else -> "th"
    }
    return "$n$suffix"
}

@Composable
fun MonthlySummaryCard(
    monthYear: String,
    totalMiles: Double,
    totalElevationFeet: Double,
    movingDurationStr: String
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = ShiftDarkSurface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            Text(
                text = monthYear,
                style = MicroLabelStyle.copy(
                    letterSpacing = 2.sp,
                    color = ShiftTextMuted
                )
            )
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Miles
                Column {
                    Text(
                        text = "%.1f".format(totalMiles),
                        style = StatNumeralHero,
                        color = ShiftTextOnDark
                    )
                    Text(
                        text = "MILES",
                        style = MicroLabelStyle.copy(color = ShiftTextMuted)
                    )
                }

                // Elevation
                Column {
                    Text(
                        text = "%,.0f".format(totalElevationFeet),
                        style = StatNumeralHero,
                        color = ShiftTextOnDark
                    )
                    Text(
                        text = "ELEV FT",
                        style = MicroLabelStyle.copy(color = ShiftTextMuted)
                    )
                }

                // Time — in orange
                Column {
                    Text(
                        text = movingDurationStr,
                        style = StatNumeralHero,
                        color = ShiftOrange
                    )
                    Text(
                        text = "MOVING",
                        style = MicroLabelStyle.copy(color = ShiftTextMuted)
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
    expanded: Boolean = false,
    onSegmentClick: (() -> Unit)? = null,
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

    // Icon circle: rides = orange circle + white bike icon; others = greige circle + dark icon
    val isRide = !isFlow && !isGym && !isRun
    val (icon, bgCircle, iconTint) = when {
        isRide -> Triple(Icons.AutoMirrored.Filled.DirectionsBike, ShiftOrange, Color.White)
        isFlow -> Triple(Icons.Default.Spa, ShiftCardInset, ShiftTextPrimary)
        isGym  -> Triple(Icons.Default.FitnessCenter, ShiftCardInset, ShiftTextPrimary)
        isRun  -> Triple(Icons.Default.DirectionsRun, ShiftCardInset, ShiftTextPrimary)
        else   -> Triple(Icons.AutoMirrored.Filled.DirectionsBike, ShiftOrange, Color.White)
    }
    
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = ShiftCard),
        shape = RoundedCornerShape(22.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .background(bgCircle, shape = CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = iconTint,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = activity.name,
                        style = CardTitleStyle,
                        color = ShiftTextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = subtitle.uppercase(),
                        style = MicroLabelStyle,
                        color = ShiftTextMuted
                    )
                }
            }
            if (segmentCount > 0) {
                Spacer(modifier = Modifier.width(8.dp))
                // Dotted-outline pill badge, and the way in to this ride's results.
                Box(
                    modifier = Modifier
                        .border(
                            width = 1.dp,
                            color = if (expanded) ShiftOrange else ShiftDotBorder,
                            shape = RoundedCornerShape(999.dp)
                        )
                        .then(
                            if (onSegmentClick != null) {
                                Modifier.clickable(onClick = onSegmentClick)
                            } else Modifier
                        )
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Layers,
                            contentDescription = "Matched Routes",
                            tint = ShiftTextMuted,
                            modifier = Modifier.size(13.dp)
                        )
                        Text(
                            text = "$segmentCount",
                            style = MicroLabelStyle.copy(
                                fontWeight = FontWeight.Bold,
                                color = ShiftTextSecondary
                            )
                        )
                    }
                }
            }
        }
    }
}
