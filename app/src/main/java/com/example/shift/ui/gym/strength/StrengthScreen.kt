package com.example.shift.ui.gym.strength

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.shift.data.gym.CachedRecommendation
import com.example.shift.data.gym.Gear
import com.example.shift.data.gym.GymSessionWithSets
import com.example.shift.data.gym.IntervalsGymPush
import com.example.shift.data.gym.PlannedExercise
import com.example.shift.data.gym.Recommender
import com.example.shift.data.gym.SessionPlans
import com.example.shift.theme.CardTitleStyle
import com.example.shift.theme.MicroLabelStyle
import com.example.shift.theme.ScreenTitleStyle
import com.example.shift.theme.ShiftAccent
import com.example.shift.theme.ShiftBg
import com.example.shift.theme.ShiftCard
import com.example.shift.theme.ShiftCardInset
import com.example.shift.theme.ShiftDarkSurface
import com.example.shift.theme.ShiftDotBorder
import com.example.shift.theme.ShiftTextMuted
import com.example.shift.theme.ShiftTextOnDark
import com.example.shift.theme.ShiftTextPrimary
import com.example.shift.theme.ShiftTextSecondary
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun StrengthScreen(
    onOpenRunner: () -> Unit,
    vm: StrengthViewModel = viewModel(factory = StrengthViewModel.factory())
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()

    // Coming back from the runner: the in-progress flag and the plan may both have changed.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) { vm.refresh() }
    }

    Column(Modifier.fillMaxSize().background(ShiftBg)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 16.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Strength", style = ScreenTitleStyle)
            IconButton(onClick = vm::replan, enabled = !state.loading, modifier = Modifier.size(40.dp).background(ShiftCard, CircleShape).border(1.dp, ShiftDotBorder, CircleShape)) {
                Icon(Icons.Default.Refresh, "Plan again", tint = ShiftTextPrimary, modifier = Modifier.size(20.dp))
            }
        }

        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(top = 8.dp, bottom = 110.dp)
        ) {
            if (state.sessionInProgress) {
                item { InProgressCard(onResume = onOpenRunner, onDiscard = vm::discardInProgress) }
            }

            item { SectionLabel("TODAY") }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = ShiftCard), shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        val rec = state.recommendation
                        when {
                            state.loading && rec == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(color = ShiftAccent, modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(12.dp))
                                Text("Working out today's session…", style = MaterialTheme.typography.bodyMedium, color = ShiftTextSecondary)
                            }
                            rec == null -> Text(state.error ?: "No plan yet.", style = MaterialTheme.typography.bodyMedium, color = ShiftTextSecondary)
                            else -> PlanCard(rec, state.loading, state.hasAnthropicKey, onStart = { vm.startSession(onOpenRunner) }, disabled = state.sessionInProgress)
                        }
                        state.error?.takeIf { state.recommendation != null }?.let {
                            Spacer(Modifier.height(8.dp))
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(6.dp)); SectionLabel("RECENT SESSIONS") }
            if (history.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        Text("Nothing logged yet.", color = ShiftTextMuted, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            } else {
                items(history, key = { it.session.id }) { s -> HistoryRow(s, onDelete = { vm.deleteSession(s.session.id) }) }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = MicroLabelStyle.copy(letterSpacing = 2.sp), modifier = Modifier.padding(start = 4.dp, bottom = 2.dp))
}

@Composable
private fun InProgressCard(onResume: () -> Unit, onDiscard: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Discard the unfinished session?") },
            text = { Text("Its sets will not be saved.") },
            confirmButton = { TextButton(onClick = { confirm = false; onDiscard() }) { Text("Discard", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Keep") } }
        )
    }
    Card(colors = CardDefaults.cardColors(containerColor = ShiftDarkSurface), shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("SESSION IN PROGRESS", style = MicroLabelStyle.copy(color = ShiftAccent, letterSpacing = 2.sp))
                Spacer(Modifier.height(4.dp))
                Text("Pick up where you left off.", style = MaterialTheme.typography.bodyMedium, color = ShiftTextOnDark)
                TextButton(onClick = { confirm = true }, contentPadding = PaddingValues(0.dp)) { Text("Discard", color = ShiftTextMuted, style = MaterialTheme.typography.bodySmall) }
            }
            Box(Modifier.size(44.dp).background(ShiftAccent, CircleShape).clickable(onClick = onResume), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.PlayArrow, "Resume", tint = Color.White)
            }
        }
    }
}

@Composable
private fun PlanCard(rec: CachedRecommendation, refreshing: Boolean, hasKey: Boolean, onStart: () -> Unit, disabled: Boolean) {
    val plan = rec.plan
    val minutes = SessionPlans.estimatedMinutes(plan)
    val sourceLabel = when (rec.source) {
        Recommender.Source.BASELINE.name -> "BASELINE"
        Recommender.Source.PROGRESSION.name -> "PROGRESSION"
        Recommender.Source.MODEL.name -> "PLANNED BY CLAUDE"
        Recommender.Source.FALLBACK_REPEAT.name -> "REPEAT"
        else -> rec.source
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(plan.sessionName, style = MaterialTheme.typography.titleLarge, color = ShiftTextPrimary, modifier = Modifier.weight(1f))
        if (refreshing) CircularProgressIndicator(color = ShiftAccent, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
    }
    Spacer(Modifier.height(6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.border(1.dp, ShiftDotBorder, RoundedCornerShape(999.dp)).padding(horizontal = 8.dp, vertical = 3.dp)) {
            Text(sourceLabel, style = MicroLabelStyle.copy(fontSize = 8.5.sp, letterSpacing = 1.5.sp))
        }
        Text("~$minutes min · ${plan.exercises.size} exercises", style = MicroLabelStyle.copy(fontSize = 10.sp))
    }
    if (plan.rationale.isNotBlank()) {
        Spacer(Modifier.height(10.dp))
        Text(plan.rationale, style = MaterialTheme.typography.bodyMedium, color = ShiftTextSecondary)
    }
    Spacer(Modifier.height(12.dp))
    plan.exercises.forEach { ex -> ExerciseRow(ex) }

    if (rec.reasons.isNotEmpty()) {
        Spacer(Modifier.height(10.dp))
        Text("WHY", style = MicroLabelStyle.copy(letterSpacing = 2.sp))
        rec.reasons.forEach { Text("· $it", style = MaterialTheme.typography.bodySmall, color = ShiftTextSecondary) }
    }
    rec.detail?.let {
        Spacer(Modifier.height(6.dp))
        Text(it, style = MaterialTheme.typography.bodySmall, color = ShiftTextMuted)
    }
    if (!hasKey && rec.reasons.isEmpty()) {
        Spacer(Modifier.height(6.dp))
        Text("Judgement calls (stalls, heavy weeks, gaps, netball) go to Claude once an Anthropic API key is set in Settings.", style = MaterialTheme.typography.bodySmall, color = ShiftTextMuted)
    }
    Spacer(Modifier.height(16.dp))
    Button(
        onClick = onStart,
        enabled = !disabled,
        modifier = Modifier.fillMaxWidth().height(52.dp),
        shape = RoundedCornerShape(18.dp),
        colors = ButtonDefaults.buttonColors(containerColor = ShiftAccent, contentColor = Color.White)
    ) {
        Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(if (disabled) "FINISH THE CURRENT SESSION FIRST" else "START SESSION", style = MaterialTheme.typography.titleMedium.copy(letterSpacing = 1.5.sp))
    }
}

@Composable
private fun ExerciseRow(ex: PlannedExercise) {
    Surface(shape = RoundedCornerShape(14.dp), color = ShiftCardInset, modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(ex.name, style = CardTitleStyle, color = ShiftTextPrimary, modifier = Modifier.weight(1f))
                if (ex.unilateral) {
                    Text("L/R", style = MicroLabelStyle.copy(color = ShiftAccent, fontWeight = FontWeight.Bold), modifier = Modifier.padding(end = 8.dp))
                }
                val target = if (ex.isHold) "${ex.holdSeconds}s" else "${ex.reps}"
                val weight = ex.weightKg?.let { " · ${Gear.fmt(it)}kg" } ?: ""
                Text("${ex.sets} × $target$weight", style = MaterialTheme.typography.bodyMedium, color = ShiftTextPrimary)
            }
            if (!ex.note.isNullOrBlank()) {
                Text(ex.note, style = MaterialTheme.typography.bodySmall, color = ShiftTextMuted)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistoryRow(s: GymSessionWithSets, onDelete: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Delete this session?") },
            text = { Text("${s.session.name} on ${s.session.date}. This does not remove it from intervals.icu.") },
            confirmButton = { TextButton(onClick = { confirm = false; onDelete() }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Keep") } }
        )
    }
    val dateLabel = runCatching { LocalDate.parse(s.session.date).format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK)) }.getOrDefault(s.session.date)
    Card(
        onClick = { expanded = !expanded },
        colors = CardDefaults.cardColors(containerColor = ShiftCard),
        shape = RoundedCornerShape(22.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(dateLabel.uppercase(), style = MicroLabelStyle)
                    Spacer(Modifier.height(2.dp))
                    Text(s.session.name, style = CardTitleStyle, color = ShiftTextPrimary)
                    val effort = s.session.perceivedEffort?.let { " · effort $it/5" } ?: ""
                    val done = s.sets.count { it.completed }
                    Text("${s.session.durationMinutes} min · $done/${s.sets.size} sets$effort", style = MaterialTheme.typography.bodySmall, color = ShiftTextMuted)
                }
                IconButton(onClick = { confirm = true }) {
                    Icon(Icons.Default.Delete, "Delete", tint = ShiftTextMuted, modifier = Modifier.size(18.dp))
                }
            }
            if (expanded) {
                Spacer(Modifier.height(8.dp))
                IntervalsGymPush.summary(s.sets).lines().forEach { line ->
                    Text(line, style = MaterialTheme.typography.bodySmall, color = ShiftTextSecondary)
                }
                s.session.notes?.takeIf { it.isNotBlank() }?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = ShiftTextMuted)
                }
            }
        }
    }
}
