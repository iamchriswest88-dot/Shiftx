package com.example.shift.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.shift.data.FormPoint
import com.example.shift.data.HubPeriod
import com.example.shift.data.HubStats
import com.example.shift.data.HubVerdict
import com.example.shift.data.PeriodTotals
import com.example.shift.data.WeeklyTargets
import com.example.shift.theme.*
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

private const val FORM_TRACK_DAYS = 42

/**
 * The Hub: where today's form sits, what to do about it, and the record behind it.
 *
 * Three bands, top to bottom. Form and the call on it come first because that is the
 * question a rider opens the app with. The period switch and the stats below it are
 * the record — the same six numbers over a week, a month, a year, or everything.
 */
@Composable
fun HubScreen(viewModel: MainViewModel) {
    val activities by viewModel.activities.collectAsState()
    val weeklyTargets by viewModel.weeklyTargets.collectAsState()
    val segmentCounts by viewModel.segmentCounts.collectAsState()

    var period by rememberSaveable { mutableStateOf(HubPeriod.WEEK) }
    var showEditTargets by remember { mutableStateOf(false) }

    // Reading the match cache is a file hit, so it happens once and then only when
    // the segment counts move — which is the signal that the cache itself changed.
    var personalBestDates by remember { mutableStateOf<List<LocalDate>>(emptyList()) }
    LaunchedEffect(segmentCounts) {
        personalBestDates = viewModel.segmentPrDates()
    }

    val today = remember { LocalDate.now() }

    val formTrack = remember(activities) { HubStats.formTrack(activities, today, FORM_TRACK_DAYS) }
    val fitness = formTrack.lastOrNull()?.ctl?.roundToInt() ?: 0
    val fatigue = formTrack.lastOrNull()?.atl?.roundToInt() ?: 0
    val form = fitness - fatigue
    val ramp = remember(formTrack) { HubStats.rampPerWeek(formTrack) }

    val totals = remember(activities, period, personalBestDates) {
        HubStats.totals(activities, HubStats.windowFor(period, today), personalBestDates)
    }
    val previousTotals = remember(activities, period, personalBestDates) {
        HubStats.previousWindowFor(period, today)?.let {
            HubStats.totals(activities, it, personalBestDates)
        }
    }
    val monthlyLoad = remember(activities) { HubStats.monthlyLoad(activities, today.year) }
    val firstRide = remember(activities) { HubStats.firstRideDate(activities) }

    val verdict = remember(period, totals, previousTotals, weeklyTargets, form, ramp, firstRide) {
        HubStats.verdict(
            period = period,
            totals = totals,
            previous = previousTotals,
            targets = weeklyTargets,
            trainingStressBalance = form,
            rampPerWeek = ramp,
            firstRide = firstRide
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ShiftBg)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    text = today.format(DateTimeFormatter.ofPattern("EEEE d MMMM")).uppercase(),
                    style = MicroLabelStyle,
                    color = ShiftTextMuted
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(text = "Hub", style = ScreenTitleStyle)
            }
            IconButton(
                onClick = { showEditTargets = true },
                modifier = Modifier
                    .size(40.dp)
                    .background(ShiftCardInset, CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = "Edit weekly targets",
                    tint = ShiftTextSecondary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        FormCard(
            form = form,
            fitness = fitness,
            fatigue = fatigue,
            ramp = ramp,
            track = formTrack
        )

        Spacer(modifier = Modifier.height(12.dp))
        VerdictStrip(verdict)

        Spacer(modifier = Modifier.height(12.dp))
        PeriodSwitch(selected = period, onSelect = { period = it })

        Spacer(modifier = Modifier.height(12.dp))
        StatsCard(
            period = period,
            totals = totals,
            previous = previousTotals,
            targets = weeklyTargets
        )

        Spacer(modifier = Modifier.height(12.dp))
        MonthlyLoadCard(monthlyLoad = monthlyLoad, year = today.year, currentMonth = today.monthValue)

        // Clear of the floating nav pill.
        Spacer(modifier = Modifier.height(110.dp))
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
private fun FormCard(
    form: Int,
    fitness: Int,
    fatigue: Int,
    ramp: Double,
    track: List<FormPoint>
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(ShiftDarkSurface)
            .padding(horizontal = 20.dp, vertical = 18.dp)
    ) {
        Text(text = "FORM · TODAY", style = MicroLabelStyle, color = ShiftTextMuted)
        Spacer(modifier = Modifier.height(10.dp))

        Row(verticalAlignment = Alignment.Top) {
            Text(
                text = "%+d".format(form),
                style = StatNumeralHero.copy(fontSize = 46.sp, lineHeight = 48.sp),
                color = ShiftTextOnDark
            )
            Spacer(modifier = Modifier.width(12.dp))
            Box(
                modifier = Modifier
                    .padding(top = 4.dp)
                    .background(ShiftAccent, RoundedCornerShape(999.dp))
                    .padding(horizontal = 11.dp, vertical = 4.dp)
            ) {
                Text(
                    text = HubStats.zoneFor(form).label,
                    style = MicroLabelStyle.copy(fontWeight = FontWeight.Bold),
                    color = Color.White
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            FormFigure(value = "$fitness", label = "FITNESS")
            FormFigure(value = "$fatigue", label = "FATIGUE")
            FormFigure(value = "%+.1f".format(ramp), label = "RAMP/WK")
        }

        Spacer(modifier = Modifier.height(14.dp))
        FormChart(track)
        Spacer(modifier = Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = "6 WEEKS", style = MicroLabelStyle, color = ShiftTextMuted)
            Text(text = "FITNESS / FATIGUE", style = MicroLabelStyle, color = ShiftTextMuted)
        }
    }
}

@Composable
private fun FormFigure(value: String, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text = value, style = StatNumeralSmall, color = ShiftTextOnDark)
        Spacer(modifier = Modifier.width(5.dp))
        Text(text = label, style = MicroLabelStyle, color = ShiftTextMuted)
    }
}

/**
 * Six weeks of fitness against fatigue. Fitness is the solid line, fatigue the dashed
 * one that swings above and below it; the dot marks today.
 */
@Composable
private fun FormChart(track: List<FormPoint>) {
    if (track.size < 2) {
        Box(modifier = Modifier.fillMaxWidth().height(64.dp)) {
            Text(
                text = "NOT ENOUGH HISTORY YET",
                style = MicroLabelStyle,
                color = ShiftTextMuted,
                modifier = Modifier.align(Alignment.CenterStart)
            )
        }
        return
    }

    val values = track.flatMap { listOf(it.ctl, it.atl) }
    val lowest = (values.minOrNull() ?: 0.0) * 0.92
    val highest = (values.maxOrNull() ?: 1.0) * 1.08
    val span = (highest - lowest).coerceAtLeast(1.0)

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
    ) {
        val stepX = size.width / (track.size - 1).toFloat()
        fun yFor(value: Double) =
            (size.height - ((value - lowest) / span * size.height).toFloat()).coerceIn(0f, size.height)

        val fitnessPath = Path()
        val fatiguePath = Path()
        track.forEachIndexed { index, point ->
            val x = index * stepX
            val fitnessY = yFor(point.ctl)
            val fatigueY = yFor(point.atl)
            if (index == 0) {
                fitnessPath.moveTo(x, fitnessY)
                fatiguePath.moveTo(x, fatigueY)
            } else {
                fitnessPath.lineTo(x, fitnessY)
                fatiguePath.lineTo(x, fatigueY)
            }
        }

        drawPath(
            path = fitnessPath,
            color = RouteLineColor,
            style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
        )
        drawPath(
            path = fatiguePath,
            color = RouteLineColor.copy(alpha = 0.42f),
            style = Stroke(
                width = 1.6.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f), 0f)
            )
        )
        val dotRadius = 3.4.dp.toPx()
        drawCircle(
            color = ShiftAccent,
            radius = dotRadius,
            center = Offset(size.width - dotRadius, yFor(track.last().ctl))
        )
    }
}

@Composable
private fun VerdictStrip(verdict: HubVerdict) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(ShiftCard)
            .border(1.5.dp, ShiftAccent, RoundedCornerShape(16.dp))
            .padding(horizontal = 16.dp, vertical = 13.dp)
    ) {
        Box(
            modifier = Modifier
                .padding(top = 5.dp)
                .size(8.dp)
                .background(ShiftAccent, CircleShape)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = buildAnnotatedString {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(verdict.lead) }
                append(". ")
                append(verdict.detail)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = ShiftTextPrimary
        )
    }
}

@Composable
private fun PeriodSwitch(selected: HubPeriod, onSelect: (HubPeriod) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(999.dp))
            .background(ShiftCard)
            .border(1.dp, ShiftHairline, RoundedCornerShape(999.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        HubPeriod.entries.forEach { entry ->
            val isSelected = entry == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(999.dp))
                    .background(if (isSelected) ShiftAccent else Color.Transparent)
                    .clickable { onSelect(entry) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = entry.name,
                    style = MicroLabelStyle.copy(fontWeight = FontWeight.Bold),
                    color = if (isSelected) Color.White else ShiftTextSecondary
                )
            }
        }
    }
}

/** One number in the stats card, with either a target it is chasing or a comparison. */
private data class StatCell(
    val value: String,
    val label: String,
    val fraction: String? = null,
    val changePercent: Double? = null,
    val progress: Float? = null
)

@Composable
private fun StatsCard(
    period: HubPeriod,
    totals: PeriodTotals,
    previous: PeriodTotals?,
    targets: WeeklyTargets
) {
    // A week is measured against what it set out to do; longer periods have nothing
    // to aim at, so they are measured against the period before them instead.
    val isWeek = period == HubPeriod.WEEK
    fun change(current: Double, earlier: Double?): Double? =
        earlier?.let { HubStats.deltaPercent(current, it) }

    val cells = listOf(
        StatCell(
            value = formatMiles(totals.miles),
            label = "MILES",
            changePercent = change(totals.miles, previous?.miles)
        ),
        StatCell(
            value = formatDuration(totals.movingSeconds),
            label = "HOURS",
            changePercent = change(totals.movingSeconds.toDouble(), previous?.movingSeconds?.toDouble())
        ),
        StatCell(
            value = formatElevation(totals.elevationFeet),
            label = "ELEV FT",
            changePercent = change(totals.elevationFeet, previous?.elevationFeet)
        ),
        StatCell(
            value = "%,.0f".format(totals.tss),
            label = "TSS",
            fraction = if (isWeek && targets.tssTarget > 0) "/${targets.tssTarget}" else null,
            changePercent = if (isWeek) null else change(totals.tss, previous?.tss),
            progress = if (isWeek && targets.tssTarget > 0) {
                (totals.tss / targets.tssTarget).toFloat().coerceIn(0f, 1f)
            } else null
        ),
        StatCell(
            value = "${totals.rides}",
            label = "RIDES",
            fraction = if (isWeek && targets.rideTarget > 0) "/${targets.rideTarget}" else null,
            changePercent = if (isWeek) null else change(totals.rides.toDouble(), previous?.rides?.toDouble())
        ),
        StatCell(value = "${totals.segmentPrs}", label = "SEGMENT PRS")
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(ShiftDarkSurface)
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        cells.chunked(3).forEach { row ->
            Row(modifier = Modifier.fillMaxWidth()) {
                row.forEachIndexed { index, cell ->
                    StatCellView(
                        cell = cell,
                        alignment = when (index) {
                            0 -> Alignment.Start
                            1 -> Alignment.CenterHorizontally
                            else -> Alignment.End
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun StatCellView(cell: StatCell, alignment: Alignment.Horizontal, modifier: Modifier) {
    // The column's own alignment places both rows, so they only need to wrap content.
    Column(modifier = modifier, horizontalAlignment = alignment) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(text = cell.value, style = StatNumeralTile, color = ShiftTextOnDark)
            if (cell.fraction != null) {
                Text(text = cell.fraction, style = StatNumeralSmall, color = ShiftTextMuted)
            }
        }
        Spacer(modifier = Modifier.height(3.dp))
        Row {
            Text(text = cell.label, style = MicroLabelStyle, color = ShiftTextMuted)
            if (cell.changePercent != null) {
                Spacer(modifier = Modifier.width(5.dp))
                val rounded = cell.changePercent.roundToInt()
                Text(
                    text = if (rounded == 0) "LEVEL" else "%+d%%".format(rounded),
                    style = MicroLabelStyle,
                    color = when {
                        rounded == 0 -> ShiftTextMuted
                        rounded > 0 -> FeelGoodColor
                        else -> FeelLowColor
                    }
                )
            }
        }
        if (cell.progress != null) {
            Spacer(modifier = Modifier.height(7.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.86f)
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(ShiftTextOnDark.copy(alpha = 0.14f))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(cell.progress)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(2.dp))
                        .background(ShiftAccent)
                )
            }
        }
    }
}

/**
 * Load month by month across the current year. The current month is solid, months
 * still to come are ghosted so the year reads as unfinished rather than as a collapse.
 */
@Composable
private fun MonthlyLoadCard(monthlyLoad: List<Double>, year: Int, currentMonth: Int) {
    val peak = monthlyLoad.maxOrNull() ?: 0.0

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(ShiftCard)
            .border(1.dp, ShiftDivider, RoundedCornerShape(24.dp))
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        Text(text = "MONTHLY LOAD · $year", style = MicroLabelStyle, color = ShiftTextMuted)
        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            monthlyLoad.forEachIndexed { index, load ->
                val month = index + 1
                val hasLoad = load > 0.0 && peak > 0.0
                val fraction = if (hasLoad) (load / peak).toFloat().coerceIn(0.08f, 1f) else 0.12f
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(fraction)
                        .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                        .background(
                            when {
                                !hasLoad -> ElevProfileFill
                                month == currentMonth -> ShiftAccent
                                else -> RouteLineColor
                            }
                        )
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf("J", "F", "M", "A", "M", "J", "J", "A", "S", "O", "N", "D").forEach { initial ->
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text(
                        text = initial,
                        style = MicroLabelStyle.copy(fontSize = 9.sp, letterSpacing = 0.sp),
                        color = ShiftTextMuted
                    )
                }
            }
        }
    }
}

// ── Formatting ───────────────────────────────────────────────────────────────
// Long periods run to five and six figures, so the units give way rather than let
// the number wrap: whole miles past a hundred, thousands of feet past six figures,
// and whole hours once minutes stop mattering.

private fun formatMiles(miles: Double): String =
    if (miles >= 100) "%,.0f".format(miles) else "%.1f".format(miles)

private fun formatElevation(feet: Double): String =
    if (feet >= 100_000) "%,.0fk".format(feet / 1000) else "%,.0f".format(feet)

private fun formatDuration(seconds: Long): String {
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    return if (hours >= 100) "%,d".format(hours) else "%d:%02d".format(hours, minutes)
}

// ── Weekly targets ───────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditTargetsSheet(
    current: WeeklyTargets,
    onDismiss: () -> Unit,
    onSave: (WeeklyTargets) -> Unit
) {
    var miles by remember { mutableStateOf(current.mileTarget.toString()) }
    var rides by remember { mutableStateOf(current.rideTarget.toString()) }
    var tss by remember { mutableStateOf(current.tssTarget.toString()) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = ShiftCard,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
        ) {
            Text(text = "WEEKLY TARGETS", style = MicroLabelStyle, color = ShiftTextMuted)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "What the week is aiming at",
                style = MaterialTheme.typography.titleLarge,
                color = ShiftTextPrimary
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
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = ShiftAccent,
                    contentColor = Color.White
                )
            ) {
                Text(text = "Save", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}
