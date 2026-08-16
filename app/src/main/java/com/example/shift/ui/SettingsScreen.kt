package com.example.shift.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons

import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DirectionsBike


import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.activity.compose.rememberLauncherForActivityResult
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import android.util.Log
import android.content.Intent
import androidx.compose.ui.graphics.Color

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: MainViewModel) {
    val currentApiKey by viewModel.apiKey.collectAsState()
    val currentAthleteId by viewModel.athleteId.collectAsState()
    val currentOrsApiKey by viewModel.orsApiKey.collectAsState()
    val currentFirebaseUrl by viewModel.firebaseUrl.collectAsState()
    val currentGeminiApiKey by viewModel.geminiApiKey.collectAsState()
    val autoOpenSegmentPage by viewModel.autoOpenSegmentPage.collectAsState()
    val currentEndRideFanfare by viewModel.endRideFanfare.collectAsState()

    var endRideFanfare by remember { mutableStateOf(currentEndRideFanfare) }
    var apiKey by remember { mutableStateOf(currentApiKey) }
    var athleteId by remember { mutableStateOf(currentAthleteId) }
    var orsApiKey by remember { mutableStateOf(currentOrsApiKey) }
    var firebaseUrl by remember { mutableStateOf(currentFirebaseUrl) }
    var geminiApiKey by remember { mutableStateOf(currentGeminiApiKey) }

    LaunchedEffect(currentApiKey, currentAthleteId, currentOrsApiKey, currentFirebaseUrl, currentGeminiApiKey) {
        apiKey = currentApiKey
        athleteId = currentAthleteId
        orsApiKey = currentOrsApiKey
        firebaseUrl = currentFirebaseUrl
        geminiApiKey = currentGeminiApiKey
    }

    LaunchedEffect(currentEndRideFanfare) {
        endRideFanfare = currentEndRideFanfare
    }

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val requiredPermissions = setOf(
        androidx.health.connect.client.permission.HealthPermission.getReadPermission(androidx.health.connect.client.records.ExerciseSessionRecord::class),
        androidx.health.connect.client.permission.HealthPermission.getReadPermission(androidx.health.connect.client.records.DistanceRecord::class),
        androidx.health.connect.client.permission.HealthPermission.getReadPermission(androidx.health.connect.client.records.ActiveCaloriesBurnedRecord::class),
        androidx.health.connect.client.permission.HealthPermission.getReadPermission(androidx.health.connect.client.records.HeartRateRecord::class),
        androidx.health.connect.client.permission.HealthPermission.getReadPermission(androidx.health.connect.client.records.StepsRecord::class),
        androidx.health.connect.client.permission.HealthPermission.getReadPermission(androidx.health.connect.client.records.ElevationGainedRecord::class),
        androidx.health.connect.client.permission.HealthPermission.getReadPermission(androidx.health.connect.client.records.SpeedRecord::class),
        androidx.health.connect.client.permission.HealthPermission.getReadPermission(androidx.health.connect.client.records.SleepSessionRecord::class),
        androidx.health.connect.client.permission.HealthPermission.getReadPermission(androidx.health.connect.client.records.RestingHeartRateRecord::class),
        androidx.health.connect.client.permission.HealthPermission.getReadPermission(androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord::class),
        "android.permission.health.READ_EXERCISE_ROUTES"
    )

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = androidx.health.connect.client.PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        viewModel.fetchActivities()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Settings", style = MaterialTheme.typography.headlineMedium) },
                actions = {
                    IconButton(onClick = { }) {
                        Icon(Icons.Default.Person, contentDescription = "Profile")
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        modifier = Modifier.fillMaxSize()
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = "",
                onValueChange = {},
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Search settings") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search") },
                shape = CircleShape,
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedBorderColor = Color.Transparent,
                    focusedBorderColor = MaterialTheme.colorScheme.primary
                )
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text("Live Tracking", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Auto-open segment page", style = MaterialTheme.typography.bodyLarge)
                    Text("Automatically page to Segment Page on start gate entry", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                }
                Switch(
                    checked = autoOpenSegmentPage,
                    onCheckedChange = { viewModel.setAutoOpenSegmentPage(it) }
                )
            }

            // Individual switches for what gets drawn on the Karoo's own map at
            // segment start. Also the diagnostic kit for a ride app that
            // misbehaves on segment entry: turn them off one at a time.
            val showSegmentLine by viewModel.showSegmentLine.collectAsState()
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Segment line on map", style = MaterialTheme.typography.bodyLarge)
                    Text("Draw the purple segment line on the Karoo map", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                }
                Switch(
                    checked = showSegmentLine,
                    onCheckedChange = { viewModel.setShowSegmentLine(it) }
                )
            }

            val showGhostArrows by viewModel.showGhostArrows.collectAsState()
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Ghost arrows on map", style = MaterialTheme.typography.bodyLarge)
                    Text("Draw each racer's arrow on the Karoo map", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                }
                Switch(
                    checked = showGhostArrows,
                    onCheckedChange = { viewModel.setShowGhostArrows(it) }
                )
            }

            // The race drawer is an overlay window; without this permission it
            // silently draws nothing, which reads as "the race never appeared".
            Spacer(modifier = Modifier.height(12.dp))
            var overlayGranted by remember {
                mutableStateOf(android.provider.Settings.canDrawOverlays(context))
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Race drawer overlay", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (overlayGranted) "Permission granted — drawer can appear during segments"
                        else "PERMISSION MISSING — the race drawer cannot appear until this is allowed",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (overlayGranted) Color.Gray else MaterialTheme.colorScheme.error
                    )
                }
                if (!overlayGranted) {
                    Column(horizontalAlignment = Alignment.End) {
                        Button(onClick = {
                            try {
                                context.startActivity(
                                    Intent(
                                        android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                        android.net.Uri.parse("package:${context.packageName}")
                                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            } catch (e: Exception) {
                                android.widget.Toast.makeText(context, "Could not open overlay settings", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }) {
                            Text("ALLOW")
                        }
                        TextButton(onClick = {
                            overlayGranted = android.provider.Settings.canDrawOverlays(context)
                        }) { Text("RECHECK") }
                    }
                } else {
                    TextButton(onClick = {
                        overlayGranted = android.provider.Settings.canDrawOverlays(context)
                    }) { Text("RECHECK") }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Text("End Ride Fanfare", style = MaterialTheme.typography.bodyLarge)
            Text(
                "Melody played on the Karoo when you complete a segment. " +
                    "Enter frequency:milliseconds pairs, using 0 for a rest. " +
                    "Leave blank for the built-in fanfare.",
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = endRideFanfare,
                onValueChange = { endRideFanfare = it },
                label = { Text("Notes") },
                placeholder = { Text("523:90, 0:40, 523:90, 784:300, 1047:420") },
                leadingIcon = { Icon(Icons.Default.MusicNote, contentDescription = null) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                maxLines = 4
            )
            Spacer(modifier = Modifier.height(8.dp))

            // Buzzer preview, Karoo only — the buzzer is the actual instrument,
            // so previewing anywhere else would misrepresent the sound. Plays
            // what's typed right now, saved or not, so tuning is edit → tap.
            val isKaroo = remember {
                android.os.Build.MANUFACTURER.contains("Hammerhead", ignoreCase = true) ||
                    android.os.Build.MODEL.contains("Karoo", ignoreCase = true)
            }
            val karooSystem = remember { io.hammerhead.karooext.KarooSystemService(context.applicationContext) }
            DisposableEffect(isKaroo) {
                if (isKaroo) karooSystem.connect { }
                onDispose { if (isKaroo) karooSystem.disconnect() }
            }

            Row(
                modifier = Modifier.align(Alignment.End),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (isKaroo) {
                    OutlinedButton(onClick = {
                        val pattern = com.example.shift.extension.VictoryFanfare.parse(endRideFanfare)
                            ?: com.example.shift.extension.VictoryFanfare.forFinish(false)
                        karooSystem.dispatch(pattern)
                    }) {
                        Text("Preview")
                    }
                }
                Button(onClick = { viewModel.saveEndRideFanfare(endRideFanfare) }) {
                    Text("Save fanfare")
                }
            }


            Spacer(modifier = Modifier.height(32.dp))

            Text("API Keys & Sync", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = athleteId ?: "",
                onValueChange = { athleteId = it },
                label = { Text("Athlete ID") },
                leadingIcon = { Icon(Icons.Default.DirectionsBike, contentDescription = null) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = apiKey ?: "",
                onValueChange = { apiKey = it },
                label = { Text("Intervals.icu API Key") },
                leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = orsApiKey ?: "",
                onValueChange = { orsApiKey = it },
                label = { Text("ORS API Key") },
                leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = firebaseUrl ?: "",
                onValueChange = { firebaseUrl = it },
                label = { Text("Firebase DB URL") },
                leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = geminiApiKey ?: "",
                onValueChange = { geminiApiKey = it },
                label = { Text("Gemini API Key") },
                leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = { 
                    viewModel.saveSettings(apiKey ?: "", orsApiKey ?: "", athleteId, firebaseUrl ?: "")
                    viewModel.updateGeminiApiKey(geminiApiKey ?: "")
                },
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium
            ) {
                Text("SAVE API KEYS")
            }

            Spacer(modifier = Modifier.height(32.dp))

            Text("Cloud Sync", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(16.dp))

            // One two-way sync: pull, merge newest-wins, push back. The old
            // separate PUSH/PULL buttons let a stale device clobber the cloud.
            Button(
                onClick = { viewModel.syncNow() },
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium
            ) {
                Text("SYNC NOW")
            }

            Spacer(modifier = Modifier.height(32.dp))

            Text("Health Connect", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(16.dp))

            FilledTonalButton(
                onClick = {
                    val status = androidx.health.connect.client.HealthConnectClient.getSdkStatus(context)
                    Log.d("HealthConnect", "Connect clicked, status: $status")
                    if (status == androidx.health.connect.client.HealthConnectClient.SDK_AVAILABLE) {
                        val client = androidx.health.connect.client.HealthConnectClient.getOrCreate(context)
                        coroutineScope.launch {
                            val granted = client.permissionController.getGrantedPermissions()
                            if (!granted.containsAll(requiredPermissions)) {
                                try {
                                    permissionLauncher.launch(requiredPermissions)
                                } catch (e: Exception) {
                                    val intent = Intent(androidx.health.connect.client.HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS)
                                    context.startActivity(intent)
                                }
                            } else {
                                android.widget.Toast.makeText(context, "Health Connect permissions already granted. Syncing...", android.widget.Toast.LENGTH_SHORT).show()
                                viewModel.fetchActivities()
                            }
                        }
                    } else {
                        android.widget.Toast.makeText(context, "Health Connect is not available on this device", android.widget.Toast.LENGTH_LONG).show()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium
            ) {
                Text("CONNECT HEALTH DATA")
            }

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedButton(
                onClick = {
                    try {
                        val intent = Intent(androidx.health.connect.client.HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS)
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        android.widget.Toast.makeText(context, "Cannot open Health Connect settings: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium
            ) {
                Text("OPEN HEALTH SETTINGS")
            }

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = {
                    viewModel.pushRunsToIntervals { _, msg ->
                        android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFD54F), contentColor = Color(0xFF261C00))
            ) {
                Icon(Icons.Default.DirectionsRun, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("PUSH RUNS TO INTERVALS.ICU", fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
            }

            Spacer(modifier = Modifier.height(24.dp))
            Text("Maintenance", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(8.dp))
            
            Button(
                onClick = {
                    viewModel.purgeDeletedRoutesAndRescan(clearScannedHistory = true) { purged, reHomed ->
                        android.widget.Toast.makeText(
                            context,
                            "Purged $purged deleted matches, re-homed $reHomed strays. Rescanning all rides...",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary)
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("PURGE DELETED ROUTES & RESCAN ALL RIDES")
            }

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedButton(
                onClick = {
                    viewModel.clearEntireCacheAndRescan {
                        android.widget.Toast.makeText(
                            context,
                            "Cleared cache of every ride and segment! Rescanning...",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) {
                Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("CLEAR CACHE OF EVERY RIDE & SEGMENT")
            }


            val orphanedCount by viewModel.orphanedCount.collectAsState()
            if (orphanedCount > 0) {
                Spacer(modifier = Modifier.height(12.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("$orphanedCount orphaned attempt(s) from deleted segments", style = MaterialTheme.typography.bodyMedium)
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = {
                                viewModel.repairOrphanedMatches { deleted ->
                                    android.widget.Toast.makeText(context, "Cleaned up $deleted orphaned attempt(s)", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        ) {
                            Text("Clean Up Orphaned Attempts")
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
            Text("Diagnostics", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    viewModel.exportDiagnostics(context) { fileName ->
                        android.widget.Toast.makeText(context, "Exported $fileName", android.widget.Toast.LENGTH_LONG).show()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium
            ) {
                Icon(Icons.Default.DirectionsRun, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("EXPORT DIAGNOSTICS")
            }

            // Readable in place — on the Karoo there is no share sheet to export
            // through, so this is the way a crash gets seen on the device itself.
            Spacer(modifier = Modifier.height(12.dp))
            var crashLog by remember { mutableStateOf<List<String>?>(null) }
            OutlinedButton(
                onClick = {
                    crashLog = if (crashLog == null) {
                        com.example.shift.data.CrashLogger.getEntries(context).filter { it.isNotBlank() }
                    } else null
                },
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium
            ) {
                Text(if (crashLog == null) "VIEW CRASH LOG" else "HIDE CRASH LOG")
            }
            crashLog?.let { lines ->
                Spacer(modifier = Modifier.height(8.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        if (lines.isEmpty()) {
                            Text("No crashes recorded.", style = MaterialTheme.typography.bodyMedium)
                        } else {
                            Text(
                                lines.takeLast(120).joinToString("\n"),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            TextButton(onClick = {
                                com.example.shift.data.CrashLogger.clear(context)
                                crashLog = emptyList()
                            }) {
                                Text("CLEAR CRASH LOG")
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}


