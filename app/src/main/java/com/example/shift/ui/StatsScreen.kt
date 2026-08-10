package com.example.shift.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Spa
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.shift.data.WeeklyTargets
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import kotlin.math.roundToInt

private val CyanColor     = Color(0xFF5CD5FA)
private val CardBg        = Color(0xFF1A1A1A)
private val LabelColor    = Color(0xFF888888)
private val AtlColor      = Color(0xFFCCCCCC)
private val GreenColor    = Color(0xFF4CAF50)
private val AmberColor    = Color(0xFFFF9800)
private val RedColor      = Color(0xFFE53935)

private fun feelColor(score: Int): Color = when {
    score >= 70 -> GreenColor
    score >= 40 -> AmberColor
    else        -> RedColor
}

private fun targetColor(current: Double, target: Double, dayOfWeek: Int): Color {
    if (target <= 0) return LabelColor
    val expectedProgress = dayOfWeek / 7.0
    val actualProgress = current / target
    return when {
        actualProgress >= expectedProgress * 0.9 -> GreenColor
        actualProgress >= expectedProgress * 0.6 -> AmberColor
        else -> RedColor
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(
    viewModel: MainViewModel,
    onNavigateToCoach: ((String) -> Unit)? = null,
    onCreateTemplate: ((title: String, durationMin: Int, exercises: List<String>, isFlow: Boolean) -> Unit)? = null,
    onNavigateToGym: (() -> Unit)? = null
) {
    val activities          by viewModel.activities.collectAsState()
    val isLoading           by viewModel.isLoading.collectAsState()
    val userVitals          by viewModel.userVitals.collectAsState()
    val latestWellness      by viewModel.latestWellness.collectAsState()
    val weeklyTargets       by viewModel.weeklyTargets.collectAsState()
    val hubBriefing         by viewModel.hubBriefing.collectAsState()
    val isBriefingLoading   by viewModel.isHubBriefingLoading.collectAsState()

    val gymSessionsThisWeek     by viewModel.gymSessionsThisWeek.collectAsState()
    val strengthSessionsThisWeek by viewModel.strengthSessionsThisWeek.collectAsState()
    val flowSessionsThisWeek     by viewModel.flowSessionsThisWeek.collectAsState()
    val lastGymSessionText      by viewModel.lastGymSessionText.collectAsState()
    val gymRecommendation       by viewModel.gymRecommendation.collectAsState()

    var showEditTargets by remember { mutableStateOf(false) }

    val now            = remember { LocalDate.now() }
    val startOfWeek    = remember { now.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) }
    val startOfPrevWeek = remember { startOfWeek.minusWeeks(1) }
    val dayOfWeek      = remember { now.dayOfWeek.value }

    // Trigger briefing generation
    LaunchedEffect(Unit) {
        viewModel.generateHubBriefing()
    }

    // ── Weekly stats ──────────────────────────────────────────────────────
    val weekActs = remember(activities) {
        activities.filter {
            try {
                val d = LocalDate.parse(it.start_date_local.take(10))
                !d.isBefore(startOfWeek)
            } catch (e: Exception) { false }
        }
    }
    val weeklyMiles = remember(weekActs) { weekActs.sumOf { it.distance ?: 0.0 } * 0.000621371 }
    val weeklyRides = remember(weekActs) { weekActs.size }
    val weeklyTss   = remember(weekActs) { weekActs.sumOf { it.icu_training_load ?: 0.0 } }
    val weeklyElev  = remember(weekActs) { weekActs.sumOf { it.total_elevation_gain ?: 0.0 } * 3.28084 }

    // ── CTL / ATL / TSB ──────────────────────────────────────────────────
    val today  = LocalDate.now()
    val days   = 42
    val dailyData = remember(activities) {
        val data   = mutableListOf<Pair<Double, Double>>()
        val seed   = activities.filter {
            try {
                LocalDate.parse(it.start_date_local.take(10)).isBefore(today.minusDays(days.toLong()))
            } catch (e: Exception) { false }
        }.maxByOrNull { it.start_date_local }
        var ctl    = seed?.icu_ctl ?: 0.0
        var atl    = seed?.icu_atl ?: 0.0
        val ctlD   = kotlin.math.exp(-1.0 / 42.0)
        val ctlI   = 1.0 - ctlD
        val atlD   = kotlin.math.exp(-1.0 / 7.0)
        val atlI   = 1.0 - atlD

        for (i in (days - 1) downTo 0) {
            val date = today.minusDays(i.toLong())
            val dateStr = date.toString()
            val dayActs = activities.filter {
                try { it.start_date_local.take(10) == dateStr } catch (e: Exception) { false }
            }
            val tss     = dayActs.sumOf { it.icu_training_load ?: 0.0 }
            ctl = ctl * ctlD + tss * ctlI
            atl = atl * atlD + tss * atlI
            val anchor = dayActs.lastOrNull { it.icu_ctl != null && it.icu_atl != null }
            if (anchor != null) { ctl = anchor.icu_ctl!!; atl = anchor.icu_atl!! }
            data.add(Pair(ctl, atl))
        }
        data
    }

    val currentCtl = dailyData.lastOrNull()?.first?.roundToInt() ?: 0
    val currentAtl = dailyData.lastOrNull()?.second?.roundToInt() ?: 0
    val currentTsb = currentCtl - currentAtl

    Scaffold(

        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Hub",
                        style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold)
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        }
    ) { pad ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Spacer(modifier = Modifier.height(4.dp))

            // 1. FEEL SCORE HERO
            val feelScore = userVitals?.feelScore ?: 50
            val scoreColor by animateColorAsState(feelColor(feelScore), label = "feel")

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier.size(140.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        val sweepAngle by animateFloatAsState(
                            targetValue = feelScore / 100f * 360f,
                            animationSpec = tween(1000, easing = FastOutSlowInEasing),
                            label = "sweep"
                        )

                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val strokeWidth = 12.dp.toPx()
                            val padding = strokeWidth / 2
                            drawArc(
                                color = Color.White.copy(alpha = 0.08f),
                                startAngle = -90f,
                                sweepAngle = 360f,
                                useCenter = false,
                                topLeft = Offset(padding, padding),
                                size = Size(size.width - strokeWidth, size.height - strokeWidth),
                                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                            )
                            drawArc(
                                color = scoreColor,
                                startAngle = -90f,
                                sweepAngle = sweepAngle,
                                useCenter = false,
                                topLeft = Offset(padding, padding),
                                size = Size(size.width - strokeWidth, size.height - strokeWidth),
                                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                            )
                        }

                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "$feelScore",
                                style = MaterialTheme.typography.displayMedium.copy(fontWeight = FontWeight.Bold),
                                color = scoreColor
                            )
                            Text(
                                text = "FEEL",
                                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 2.sp),
                                color = LabelColor
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        val hrv   = userVitals?.avgHRV?.roundToInt()?.toString() ?: "--"
                        val rhr   = userVitals?.avgRestingHR?.roundToInt()?.toString() ?: "--"
                        val sleep = userVitals?.avgSleepHours?.let {
                            val h = it.toInt()
                            val m = ((it - h) * 60).roundToInt()
                            "%d:%02d".format(h, m)
                        } ?: "--"

                        MiniStat(value = hrv, label = "HRV")
                        MiniStat(value = rhr, label = "RHR")
                        MiniStat(value = sleep, label = "Sleep")
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FitnessPill(label = "Fitness", value = "$currentCtl", color = CyanColor)
                        Spacer(modifier = Modifier.width(8.dp))
                        FitnessPill(label = "Fatigue", value = "$currentAtl", color = AtlColor)
                        Spacer(modifier = Modifier.width(8.dp))
                        val tsbColor = if (currentTsb >= 0) GreenColor else RedColor
                        FitnessPill(label = "Form", value = "%+d".format(currentTsb), color = tsbColor)
                    }
                }
            }

            // 2. AI DAILY BRIEFING
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = null,
                            tint = CyanColor,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "DAILY BRIEFING",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.8.sp
                            ),
                            color = LabelColor
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))

                    if (isBriefingLoading) {
                        repeat(3) { i ->
                            val infiniteTransition = rememberInfiniteTransition(label = "shimmer$i")
                            val alpha by infiniteTransition.animateFloat(
                                initialValue = 0.15f,
                                targetValue = 0.35f,
                                animationSpec = infiniteRepeatable(
                                    animation = tween(800, delayMillis = i * 100),
                                    repeatMode = RepeatMode.Reverse
                                ),
                                label = "shimmerAlpha$i"
                            )
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(if (i == 2) 0.7f else 1f)
                                    .height(14.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(Color.White.copy(alpha = alpha))
                            )
                            if (i < 2) Spacer(modifier = Modifier.height(8.dp))
                        }
                    } else {
                        Text(
                            text = hubBriefing ?: "Tap to generate your daily briefing.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f),
                            lineHeight = 22.sp
                        )
                    }
                }
            }

            // 3. RECOMMENDED WORKOUT & RECOVERY STRETCHES
            RecommendedWorkoutCard(
                recommendation = gymRecommendation,
                onAskCoach = { prompt -> onNavigateToCoach?.invoke(prompt) },
                onCreateTemplate = { title, durationMin, exercises, isFlow ->
                    onCreateTemplate?.invoke(title, durationMin, exercises, isFlow)
                },
                onOpenGym = { onNavigateToGym?.invoke() }
            )

            // 4. GYM & STRENGTH STATS
            GymSummaryCard(
                strengthSessionsThisWeek = strengthSessionsThisWeek,
                flowSessionsThisWeek = flowSessionsThisWeek,
                lastGymSessionText = lastGymSessionText,
                onOpenGym = { onNavigateToGym?.invoke() }
            )

            // 5. WEEKLY TARGETS
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "WEEKLY TARGETS",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.8.sp
                            ),
                            color = LabelColor
                        )
                        IconButton(
                            onClick = { showEditTargets = true },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = "Edit Targets",
                                tint = LabelColor,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    TargetProgressRow(
                        label = "Miles",
                        current = weeklyMiles,
                        target = weeklyTargets.mileTarget,
                        format = "%.1f",
                        dayOfWeek = dayOfWeek
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    TargetProgressRow(
                        label = "Rides",
                        current = weeklyRides.toDouble(),
                        target = weeklyTargets.rideTarget.toDouble(),
                        format = "%.0f",
                        dayOfWeek = dayOfWeek
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    TargetProgressRow(
                        label = "TSS",
                        current = weeklyTss,
                        target = weeklyTargets.tssTarget.toDouble(),
                        format = "%.0f",
                        dayOfWeek = dayOfWeek
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    TargetProgressRow(
                        label = "Gym",
                        current = gymSessionsThisWeek.toDouble(),
                        target = 4.0,
                        format = "%.0f",
                        dayOfWeek = dayOfWeek
                    )
                }
            }

            // 6. FITNESS & FATIGUE CHART
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "6-WEEK LOAD TREND",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.8.sp
                            ),
                            color = LabelColor
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Box(modifier = Modifier.size(16.dp, 2.dp).background(CyanColor))
                                Text(
                                    text = "CTL $currentCtl",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = CyanColor
                                )
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Canvas(modifier = Modifier.size(16.dp, 2.dp)) {
                                    drawLine(
                                        color = AtlColor,
                                        start = Offset(0f, size.height / 2),
                                        end = Offset(size.width, size.height / 2),
                                        strokeWidth = 2.dp.toPx(),
                                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 3f), 0f)
                                    )
                                }
                                Text(
                                    text = "ATL $currentAtl",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = AtlColor
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    FitnessChartCompact(dailyData = dailyData)
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    if (showEditTargets) {
        EditTargetsSheet(
            current = weeklyTargets,
            onDismiss = { showEditTargets = false },
            onSave = { targets ->
                viewModel.saveWeeklyTargets(targets)
                showEditTargets = false
            }
        )
    }
}

@Composable
private fun RecommendedWorkoutCard(
    recommendation: com.example.shift.ui.GymRecommendation,
    onAskCoach: (String) -> Unit,
    onCreateTemplate: (title: String, durationMin: Int, exercises: List<String>, isFlow: Boolean) -> Unit,
    onOpenGym: () -> Unit
) {
    val badgeColor = remember(recommendation.badgeColorHex) {
        try {
            Color(android.graphics.Color.parseColor(recommendation.badgeColorHex))
        } catch (e: Exception) {
            CyanColor
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = if (recommendation.isFlowType) Icons.Default.Spa else Icons.Default.FitnessCenter,
                        contentDescription = null,
                        tint = badgeColor,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "RECOMMENDED WORKOUT & RECOVERY",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.8.sp
                        ),
                        color = LabelColor
                    )
                }

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = badgeColor.copy(alpha = 0.15f)
                ) {
                    Text(
                        text = recommendation.categoryTag,
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = badgeColor,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = recommendation.title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = recommendation.reason,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 20.sp
            )

            Spacer(modifier = Modifier.height(14.dp))

            recommendation.recommendedRoutines.forEach { routine ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = routine.title,
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f)
                            )
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = Color.White.copy(alpha = 0.08f)
                            ) {
                                Text(
                                    text = "${routine.durationMin} min",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = LabelColor,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }

                        Text(
                            text = "Target: ${routine.targetArea}",
                            style = MaterialTheme.typography.labelSmall,
                            color = badgeColor,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )

                        routine.exercises.forEach { ex ->
                            Row(
                                verticalAlignment = Alignment.Top,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.padding(vertical = 2.dp)
                            ) {
                                Text(
                                    text = "•",
                                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                                    color = badgeColor
                                )
                                Text(
                                    text = ex,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Button(
                            onClick = { onCreateTemplate(routine.title, routine.durationMin, routine.exercises, recommendation.isFlowType) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = badgeColor, contentColor = Color.Black)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "MAKE AS TEMPLATE",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { onAskCoach(recommendation.promptForAi) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = badgeColor, contentColor = Color.Black)
                ) {
                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Build with AI",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                    )
                }

                OutlinedButton(
                    onClick = onOpenGym,
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.FitnessCenter,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Gym Tab",
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
        }
    }
}

@Composable
private fun GymSummaryCard(
    strengthSessionsThisWeek: Int,
    flowSessionsThisWeek: Int,
    lastGymSessionText: String,
    onOpenGym: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "GYM & RECOVERY SUMMARY",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.8.sp
                    ),
                    color = LabelColor
                )
                TextButton(onClick = onOpenGym) {
                    Text(
                        text = "View Workouts >",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = CyanColor
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(Icons.Default.FitnessCenter, contentDescription = null, tint = Color(0xFFCE93D8), modifier = Modifier.size(14.dp))
                        Text(text = "$strengthSessionsThisWeek", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.onSurface)
                    }
                    Text(text = "Strength Wk", style = MaterialTheme.typography.labelSmall, color = LabelColor)
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(Icons.Default.Spa, contentDescription = null, tint = Color(0xFF81C784), modifier = Modifier.size(14.dp))
                        Text(text = "$flowSessionsThisWeek", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.onSurface)
                    }
                    Text(text = "Stretch/Flow Wk", style = MaterialTheme.typography.labelSmall, color = LabelColor)
                }

                MiniStat(value = lastGymSessionText, label = "Last Workout")
            }
        }
    }
}

@Composable
private fun MiniStat(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = LabelColor
        )
    }
}

@Composable
private fun FitnessPill(label: String, value: String, color: Color) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = color.copy(alpha = 0.15f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = color.copy(alpha = 0.8f)
            )
            Text(
                text = value,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                color = color
            )
        }
    }
}

@Composable
private fun TargetProgressRow(
    label: String,
    current: Double,
    target: Double,
    format: String,
    dayOfWeek: Int
) {
    val progress = if (target > 0) (current / target).toFloat().coerceIn(0f, 1f) else 0f
    val barColor = targetColor(current, target, dayOfWeek)
    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(800, easing = FastOutSlowInEasing),
        label = "progress"
    )

    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = LabelColor
            )
            Text(
                text = "${format.format(current)} / ${format.format(target)}",
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color.White.copy(alpha = 0.08f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(animatedProgress)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(4.dp))
                    .background(barColor)
            )
        }
    }
}

@Composable
fun WeekStat(value: String, label: String, isUp: Boolean, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.Start) {
        Text(
            text = value,
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurface
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            Icon(
                imageVector = if (isUp) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
                contentDescription = null,
                tint = CyanColor,
                modifier = Modifier.size(12.dp)
            )
            Text(text = label, style = MaterialTheme.typography.bodySmall, color = LabelColor)
        }
    }
}

@Composable
fun FitnessChartCompact(dailyData: List<Pair<Double, Double>>) {
    if (dailyData.size < 2) return

    val allValues = dailyData.flatMap { listOf(it.first, it.second) }
    val minY = (allValues.minOrNull() ?: 0.0) * 0.92
    val maxY = (allValues.maxOrNull() ?: 1.0) * 1.08
    val range = (maxY - minY).coerceAtLeast(1.0)

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(110.dp)
    ) {
        val w = size.width
        val h = size.height
        val stepX = w / (dailyData.size - 1).toFloat()

        fun toY(v: Double) = (h - ((v - minY) / range * h).toFloat()).coerceIn(0f, h)

        // CTL path (solid cyan)
        val ctlPath = Path()
        dailyData.forEachIndexed { i, (ctl, _) ->
            val x = i * stepX
            val y = toY(ctl)
            if (i == 0) ctlPath.moveTo(x, y) else ctlPath.lineTo(x, y)
        }
        drawPath(ctlPath, color = CyanColor, style = Stroke(width = 2.dp.toPx()))

        // ATL path (dashed grey)
        val atlPath = Path()
        dailyData.forEachIndexed { i, (_, atl) ->
            val x = i * stepX
            val y = toY(atl)
            if (i == 0) atlPath.moveTo(x, y) else atlPath.lineTo(x, y)
        }
        drawPath(
            atlPath,
            color = AtlColor,
            style = Stroke(
                width = 1.5.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f), 0f)
            )
        )
    }
}

// ── Edit Targets Bottom Sheet ────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditTargetsSheet(
    current: WeeklyTargets,
    onDismiss: () -> Unit,
    onSave: (WeeklyTargets) -> Unit
) {
    var miles by remember { mutableStateOf(current.mileTarget.toString()) }
    var rides by remember { mutableStateOf(current.rideTarget.toString()) }
    var tss   by remember { mutableStateOf(current.tssTarget.toString()) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
        ) {
            Text(
                text = "Weekly Targets",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(20.dp))

            OutlinedTextField(
                value = miles,
                onValueChange = { miles = it },
                label = { Text("Miles target") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = rides,
                onValueChange = { rides = it },
                label = { Text("Rides target") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = tss,
                onValueChange = { tss = it },
                label = { Text("TSS target") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = {
                    onSave(
                        WeeklyTargets(
                            mileTarget = miles.toDoubleOrNull() ?: current.mileTarget,
                            rideTarget = rides.toIntOrNull() ?: current.rideTarget,
                            tssTarget = tss.toIntOrNull() ?: current.tssTarget
                        )
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Save", style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold))
            }
        }
    }
}
