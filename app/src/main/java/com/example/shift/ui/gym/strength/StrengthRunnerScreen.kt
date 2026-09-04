package com.example.shift.ui.gym.strength

import android.view.WindowManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.shift.data.gym.Gear
import com.example.shift.data.gym.LoggedSet
import com.example.shift.data.gym.RunnerEngine
import com.example.shift.data.gym.RunnerStep
import com.example.shift.data.gym.StepKind
import com.example.shift.theme.MicroLabelStyle
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
import com.example.shift.theme.StatNumeralHero
import com.example.shift.theme.ndotFamily
import kotlinx.coroutines.delay

/**
 * The session runner. Shows one step at a time and leans on the ViewModel's
 * snapshot for everything, so a refresh or a locked screen lands back here on
 * the same step with the same countdown.
 */
@Composable
fun StrengthRunnerScreen(
    onExit: () -> Unit,
    vm: StrengthRunnerViewModel = viewModel(factory = StrengthRunnerViewModel.factory())
) {
    val state by vm.state.collectAsStateWithLifecycle()

    val context = LocalContext.current
    DisposableEffect(Unit) {
        val window = (context as? android.app.Activity)?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    // The clock only runs while the screen is in front. In the background the
    // snapshot's wall-clock stamps keep counting on their own; the first tick
    // on return settles the runner onto the right step.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                vm.tick()
                delay(250)
            }
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = ShiftBg) {
        when {
            !state.loaded -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = ShiftAccent)
            }
            state.snapshot == null -> Column(
                Modifier.fillMaxSize().systemBarsPadding().padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("No session in progress", style = MaterialTheme.typography.headlineSmall, color = ShiftTextPrimary)
                Spacer(Modifier.height(24.dp))
                Button(onClick = onExit, colors = ButtonDefaults.buttonColors(containerColor = ShiftAccent)) { Text("Back") }
            }
            state.finished || state.saved -> FinishScreen(state, vm, onExit)
            else -> ActiveStepScreen(state, vm, onExit)
        }
    }
}

/**
 * Two looks for the runner. Work steps sit on the app's light ground; the
 * countdowns (rest, swap, hold) go black with the dot face, so a glance
 * across the room tells work from waiting.
 */
private data class RunnerPalette(
    val bg: Color,
    val text: Color,
    val muted: Color,
    val card: Color,
    val border: Color,
    val track: Color
) {
    companion object {
        val light = RunnerPalette(ShiftBg, ShiftTextPrimary, ShiftTextSecondary, ShiftCard, ShiftDotBorder, ShiftCardInset)
        val dark = RunnerPalette(Color.Black, ShiftTextOnDark, Color(0xFF8A8A84), Color(0xFF1B1B19), Color(0xFF3A3A36), Color(0xFF262624))
    }
}

@Composable
private fun ActiveStepScreen(state: RunnerUiState, vm: StrengthRunnerViewModel, onExit: () -> Unit) {
    val snapshot = state.snapshot ?: return
    val step = state.step ?: return
    val steps = state.steps
    var showDiscard by remember { mutableStateOf(false) }
    var muted by remember { mutableStateOf(vm.muted) }

    val elapsedSec = ((state.nowMs - snapshot.sessionStartedAtMs) / 1000L).coerceAtLeast(0)
    val planExercise = snapshot.plan.exercises.getOrNull(step.exerciseIndex)
    val remainingMs = state.remainingMs
    val remainingSec = remainingMs?.let { ((it + 999) / 1000).toInt() }
    val dark = step.isTimed
    val p = if (dark) RunnerPalette.dark else RunnerPalette.light

    if (showDiscard) {
        AlertDialog(
            onDismissRequest = { showDiscard = false },
            title = { Text("Discard session?") },
            text = { Text("Nothing from this session will be saved.") },
            confirmButton = {
                TextButton(onClick = { showDiscard = false; vm.discard(); onExit() }) { Text("Discard", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showDiscard = false }) { Text("Keep going") } }
        )
    }

    Column(Modifier.fillMaxSize().background(p.bg).systemBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp)) {
        // Top bar: step back, session clock, mute, discard.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = vm::back, enabled = snapshot.stepIndex > 0) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Previous step", tint = if (snapshot.stepIndex > 0) p.text else p.muted)
            }
            Spacer(Modifier.weight(1f))
            Text(formatClock(elapsedSec), style = MicroLabelStyle.copy(fontSize = 13.sp, color = p.muted, fontFamily = if (dark) ndotFamily else MicroLabelStyle.fontFamily))
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { muted = !muted; vm.muted = muted }) {
                Icon(if (muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp, "Mute", tint = p.muted)
            }
            IconButton(onClick = { showDiscard = true }) {
                Icon(Icons.Default.Close, "Discard", tint = p.muted)
            }
        }

        val total = RunnerEngine.workTotal(steps)
        val done = RunnerEngine.workDone(snapshot, steps)
        LinearProgressIndicator(
            progress = { if (total == 0) 0f else done.toFloat() / total },
            modifier = Modifier.fillMaxWidth().height(4.dp),
            color = ShiftAccent,
            trackColor = p.track
        )
        Spacer(Modifier.height(6.dp))
        Text("$done / $total sets", style = MicroLabelStyle.copy(color = p.muted), modifier = Modifier.align(Alignment.End))

        // Centre: what to do now.
        Column(
            Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            when (step.kind) {
                StepKind.REPS -> RepStep(state, step, planExercise?.note, vm)
                StepKind.HOLD -> TimedStep(
                    label = "HOLD", title = step.exerciseName, side = step.side,
                    setLine = "SET ${step.setNumber} OF ${step.totalSets}",
                    remainingSec = remainingSec ?: 0, totalSec = step.targetHoldSeconds ?: 0,
                    paused = snapshot.isPaused, note = planExercise?.note, upNext = null, vm = vm, showRestAdjust = false, restSeconds = 0, p = p
                )
                StepKind.SWAP, StepKind.REST -> {
                    val next = step.nextWorkIndex?.let { steps.getOrNull(it) }
                    TimedStep(
                        label = if (step.kind == StepKind.SWAP) "SWAP SIDES" else "REST",
                        title = if (step.kind == StepKind.SWAP) step.exerciseName else "Rest",
                        side = null,
                        setLine = if (step.kind == StepKind.SWAP) "SET ${step.setNumber} OF ${step.totalSets}" else "",
                        remainingSec = remainingSec ?: 0,
                        totalSec = (RunnerEngine.durationMs(snapshot, step) ?: 0L).toInt() / 1000,
                        paused = snapshot.isPaused,
                        note = null,
                        upNext = next?.let { upNextLine(snapshot, it) },
                        vm = vm,
                        showRestAdjust = step.kind == StepKind.REST,
                        restSeconds = RunnerEngine.restSecondsFor(snapshot, step),
                        p = p
                    )
                }
            }
        }

        // Bottom: the one big button, plus skip.
        Spacer(Modifier.height(12.dp))
        val primaryLabel = when (step.kind) {
            StepKind.REPS -> "DONE"
            StepKind.HOLD -> "END HOLD"
            StepKind.SWAP -> "READY"
            StepKind.REST -> "SKIP REST"
        }
        Button(
            onClick = vm::done,
            modifier = Modifier.fillMaxWidth().height(64.dp),
            shape = RoundedCornerShape(20.dp),
            colors = if (step.isWork) ButtonDefaults.buttonColors(containerColor = ShiftAccent, contentColor = Color.White)
                     else ButtonDefaults.buttonColors(containerColor = ShiftTextOnDark, contentColor = Color.Black)
        ) {
            Text(
                primaryLabel,
                style = if (dark) MaterialTheme.typography.titleMedium.copy(fontFamily = ndotFamily, fontSize = 18.sp, letterSpacing = 2.sp)
                        else MaterialTheme.typography.titleMedium.copy(letterSpacing = 1.5.sp)
            )
        }
        if (step.isWork) {
            TextButton(onClick = vm::skip, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text("Skip this set", color = ShiftTextMuted)
            }
        } else {
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun RepStep(state: RunnerUiState, step: RunnerStep, note: String?, vm: StrengthRunnerViewModel) {
    val snapshot = state.snapshot ?: return
    val weight = RunnerEngine.effectiveWeightKg(snapshot, step)
    val reps = RunnerEngine.effectiveReps(snapshot, step) ?: 0
    val bodyweight = vm.equipmentFor(step.exerciseName) == com.example.shift.data.gym.Equipment.BODYWEIGHT

    SetHeader(setLine = "SET ${step.setNumber} OF ${step.totalSets}", side = step.side)
    Spacer(Modifier.height(12.dp))
    Text(step.exerciseName, style = MaterialTheme.typography.headlineLarge, color = ShiftTextPrimary, textAlign = TextAlign.Center)
    if (!note.isNullOrBlank()) {
        Spacer(Modifier.height(8.dp))
        Text(note, style = MaterialTheme.typography.bodySmall, color = ShiftTextSecondary, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 12.dp))
    }
    Spacer(Modifier.height(28.dp))

    // Reps, then load. The plan is a suggestion; these are what get logged.
    Stepper(
        value = "$reps",
        unit = "reps",
        onMinus = { vm.setReps(step.exerciseIndex, reps - 1) },
        onPlus = { vm.setReps(step.exerciseIndex, reps + 1) }
    )
    Spacer(Modifier.height(16.dp))
    // Load is never capped here: the equipment list bounds what gets
    // recommended, not what gets logged. Below the lightest load is bodyweight.
    Stepper(
        value = weight?.let { Gear.fmt(it) } ?: "BW",
        unit = if (weight == null) (if (bodyweight) "bodyweight" else "no load") else "kg",
        onMinus = { vm.setWeightExact(step.exerciseIndex, vm.steppedWeight(step, weight, up = false)) },
        onPlus = { vm.setWeightExact(step.exerciseIndex, vm.steppedWeight(step, weight, up = true)) }
    )
    val planned = step.targetWeightKg
    if (planned != null && weight != planned) {
        Spacer(Modifier.height(6.dp))
        Text("plan said ${Gear.fmt(planned)}kg", style = MicroLabelStyle)
    }
}

@Composable
private fun TimedStep(
    label: String, title: String, side: String?, setLine: String,
    remainingSec: Int, totalSec: Int, paused: Boolean, note: String?, upNext: String?,
    vm: StrengthRunnerViewModel, showRestAdjust: Boolean, restSeconds: Int, p: RunnerPalette
) {
    SetHeader(setLine = setLine, side = side, label = label, p = p)
    Spacer(Modifier.height(12.dp))
    Text(title, style = MaterialTheme.typography.displaySmall, color = p.text, textAlign = TextAlign.Center)
    if (!note.isNullOrBlank()) {
        Spacer(Modifier.height(8.dp))
        Text(note, style = MaterialTheme.typography.bodySmall.copy(fontFamily = ndotFamily, fontSize = 14.sp), color = p.muted, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 12.dp))
    }
    Spacer(Modifier.height(20.dp))
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(220.dp)) {
        CircularProgressIndicator(
            progress = { if (totalSec <= 0) 0f else 1f - remainingSec.toFloat() / totalSec },
            modifier = Modifier.fillMaxSize(),
            color = ShiftAccent,
            trackColor = p.track,
            strokeWidth = 8.dp
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(formatClock(remainingSec.toLong()), style = StatNumeralHero.copy(fontSize = 56.sp, lineHeight = 60.sp), color = p.text)
            if (paused) Text("PAUSED", style = MicroLabelStyle.copy(color = ShiftAccent, fontFamily = ndotFamily))
        }
    }
    Spacer(Modifier.height(16.dp))
    if (upNext != null) {
        Text("UP NEXT", style = MicroLabelStyle.copy(color = ShiftAccent, letterSpacing = 2.sp, fontFamily = ndotFamily))
        Spacer(Modifier.height(4.dp))
        Text(upNext, style = MaterialTheme.typography.displaySmall.copy(fontSize = 22.sp, lineHeight = 28.sp), color = p.text, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (showRestAdjust) {
            val adjustColors = ButtonDefaults.outlinedButtonColors(contentColor = p.text)
            val adjustBorder = BorderStroke(1.dp, p.border)
            OutlinedButton(onClick = { vm.adjustRest(-15) }, shape = RoundedCornerShape(999.dp), colors = adjustColors, border = adjustBorder) {
                Text("−15s", fontFamily = ndotFamily)
            }
            Text("rest ${restSeconds}s", style = MicroLabelStyle.copy(fontSize = 11.sp, color = p.muted, fontFamily = ndotFamily))
            OutlinedButton(onClick = { vm.adjustRest(+15) }, shape = RoundedCornerShape(999.dp), colors = adjustColors, border = adjustBorder) {
                Text("+15s", fontFamily = ndotFamily)
            }
        }
        IconButton(
            onClick = vm::pauseResume,
            modifier = Modifier.size(48.dp).background(p.card, CircleShape).border(1.dp, p.border, CircleShape)
        ) {
            Icon(if (paused) Icons.Default.PlayArrow else Icons.Default.Pause, if (paused) "Resume" else "Pause", tint = p.text)
        }
    }
}

@Composable
private fun SetHeader(setLine: String, side: String?, label: String? = null, p: RunnerPalette = RunnerPalette.light) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (label != null) Pill(label, p = p, filled = true)
        if (setLine.isNotBlank()) Pill(setLine, p = p)
        if (side != null) Pill(side.uppercase(), p = p, accent = true)
    }
}

/** [filled] inverts the pill against its ground; [accent] paints it purple. */
@Composable
private fun Pill(text: String, p: RunnerPalette, filled: Boolean = false, accent: Boolean = false) {
    val bg = when { accent -> ShiftAccent; filled -> p.text; else -> p.card }
    val fg = when { accent -> ShiftTextOnDark; filled -> p.bg; else -> p.muted }
    val dotted = p === RunnerPalette.dark
    Surface(shape = RoundedCornerShape(999.dp), color = bg, contentColor = fg) {
        Text(
            text,
            style = MicroLabelStyle.copy(color = fg, fontWeight = FontWeight.Bold, fontSize = 10.sp, fontFamily = if (dotted) ndotFamily else MicroLabelStyle.fontFamily),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}

@Composable
private fun Stepper(value: String, unit: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        IconButton(onClick = onMinus, modifier = Modifier.size(52.dp).background(ShiftCard, CircleShape).border(1.dp, ShiftDotBorder, CircleShape)) {
            Icon(Icons.Default.Remove, "Less", tint = ShiftTextPrimary)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(120.dp)) {
            Text(value, style = StatNumeralHero.copy(fontSize = 44.sp, lineHeight = 48.sp), color = ShiftTextPrimary)
            Text(unit.uppercase(), style = MicroLabelStyle)
        }
        IconButton(onClick = onPlus, modifier = Modifier.size(52.dp).background(ShiftCard, CircleShape).border(1.dp, ShiftDotBorder, CircleShape)) {
            Icon(Icons.Default.Add, "More", tint = ShiftTextPrimary)
        }
    }
}

@Composable
private fun FinishScreen(state: RunnerUiState, vm: StrengthRunnerViewModel, onExit: () -> Unit) {
    val snapshot = state.snapshot ?: return
    var effort by remember { mutableStateOf<Int?>(null) }
    var notes by remember { mutableStateOf("") }
    var push by remember { mutableStateOf(state.canPushToIntervals) }
    val durationMin = ((state.nowMs - snapshot.sessionStartedAtMs) / 60_000L).coerceAtLeast(1)

    Column(Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp)) {
        Text("Session complete", style = MaterialTheme.typography.headlineLarge, color = ShiftTextPrimary)
        Spacer(Modifier.height(4.dp))
        Text("${snapshot.plan.sessionName} · $durationMin min", style = MaterialTheme.typography.bodyMedium, color = ShiftTextSecondary)
        Spacer(Modifier.height(20.dp))

        Surface(shape = RoundedCornerShape(22.dp), color = ShiftCard, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("WHAT YOU DID", style = MicroLabelStyle.copy(letterSpacing = 2.sp))
                summariseLogged(snapshot.logged).forEach { line ->
                    Text(line, style = MaterialTheme.typography.bodyMedium, color = ShiftTextPrimary)
                }
            }
        }

        if (!state.saved) {
            Spacer(Modifier.height(20.dp))
            Text("HOW HARD WAS IT?", style = MicroLabelStyle.copy(letterSpacing = 2.sp))
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (1..5).forEach { n ->
                    FilterChip(
                        selected = effort == n,
                        onClick = { effort = if (effort == n) null else n },
                        label = { Text("$n") },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = ShiftAccent, selectedLabelColor = Color.White)
                    )
                }
            }
            Text("1 easy · 5 all out", style = MicroLabelStyle, modifier = Modifier.padding(top = 6.dp))
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it },
                label = { Text("Notes") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2
            )
            if (state.canPushToIntervals) {
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Push to intervals.icu", style = MaterialTheme.typography.bodyMedium, color = ShiftTextPrimary)
                    Switch(checked = push, onCheckedChange = { push = it })
                }
            }
            state.saveError?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = { vm.finish(effort, notes, push && state.canPushToIntervals) },
                enabled = !state.saving,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(20.dp),
                colors = ButtonDefaults.buttonColors(containerColor = ShiftAccent, contentColor = Color.White)
            ) {
                Text(if (state.saving) "SAVING…" else "SAVE SESSION", style = MaterialTheme.typography.titleMedium.copy(letterSpacing = 1.5.sp))
            }
            TextButton(onClick = vm::back, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text("Back to the last set", color = ShiftTextMuted)
            }
        } else {
            Spacer(Modifier.height(16.dp))
            Text("Saved.", style = MaterialTheme.typography.bodyMedium, color = ShiftTextPrimary)
            state.intervalsMessage?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = ShiftTextSecondary)
            }
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = onExit,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(20.dp),
                colors = ButtonDefaults.buttonColors(containerColor = ShiftDarkSurface, contentColor = Color.White)
            ) { Text("DONE", style = MaterialTheme.typography.titleMedium.copy(letterSpacing = 1.5.sp)) }
        }
        Spacer(Modifier.height(32.dp))
    }
}

private fun upNextLine(snapshot: com.example.shift.data.gym.RunnerSnapshot, next: RunnerStep): String {
    val weight = RunnerEngine.effectiveWeightKg(snapshot, next)?.let { " · ${Gear.fmt(it)}kg" } ?: ""
    val target = if (next.kind == StepKind.HOLD) " · ${next.targetHoldSeconds ?: 0}s" else " × ${RunnerEngine.effectiveReps(snapshot, next) ?: 0}"
    val side = next.side?.let { " · ${it.replaceFirstChar { c -> c.uppercase() }}" } ?: ""
    return "${next.exerciseName}$side\nset ${next.setNumber} of ${next.totalSets}$weight$target"
}

/** One line per exercise: "Cable row · L 12/12/12 R 12/12/11 · 8kg". */
fun summariseLogged(logged: List<LoggedSet>): List<String> {
    val order = logged.map { it.exerciseIndex }.distinct()
    return order.map { idx ->
        val sets = logged.filter { it.exerciseIndex == idx }.sortedWith(compareBy({ it.setIndex }, { it.side ?: "" }))
        val name = sets.first().exerciseName
        val isHold = sets.all { it.holdSeconds != null }
        val body = if (isHold) {
            sets.joinToString("/") { "${it.holdSeconds}s" }
        } else if (sets.any { it.side != null }) {
            listOf("left", "right").mapNotNull { side ->
                val s = sets.filter { it.side == side }
                if (s.isEmpty()) null else side.first().uppercase() + " " + s.joinToString("/") { (it.reps ?: 0).toString() }
            }.joinToString(" ")
        } else {
            sets.joinToString("/") { (it.reps ?: 0).toString() }
        }
        val weight = sets.mapNotNull { it.weightKg }.maxOrNull()?.let { " · ${Gear.fmt(it)}kg" } ?: ""
        val missed = sets.count { !it.completed }.let { if (it > 0) " · $it not completed" else "" }
        "$name · $body$weight$missed"
    }
}

private fun formatClock(totalSeconds: Long): String {
    val m = totalSeconds / 60
    val s = totalSeconds % 60
    return "%d:%02d".format(m, s)
}
