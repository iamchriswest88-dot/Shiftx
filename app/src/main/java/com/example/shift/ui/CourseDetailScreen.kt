package com.example.shift.ui

import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun CourseDetailScreen(
    viewModel: CourseDetailViewModel,
    onBack: () -> Unit,
    onEditCourse: (String, String) -> Unit,
    onMatchClick: (String) -> Unit
) {
    val course by viewModel.course.collectAsState()
    var matchToDelete by remember { mutableStateOf<com.example.shift.data.CourseMatch?>(null) }


    if (matchToDelete != null) {
        val m = matchToDelete!!
        val mins = m.timeSeconds / 60
        val secs = m.timeSeconds % 60
        val timeFormatted = "%02d:%02d".format(mins, secs)
        AlertDialog(
            onDismissRequest = { matchToDelete = null },
            title = { Text("Delete Match Attempt") },
            text = { Text("Are you sure you want to remove this attempt (${m.date} - $timeFormatted) from the leaderboard?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteMatch(m)
                        matchToDelete = null
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { matchToDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    val matches by viewModel.filteredMatches.collectAsState()

    val availableYears by viewModel.availableYears.collectAsState()
    val selectedYear by viewModel.selectedYear.collectAsState()
    val isScanning by viewModel.isScanning.collectAsState()
    val scanStatus by viewModel.scanStatus.collectAsState()

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.loadAndMaybeScan()
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
            CenterAlignedTopAppBar(
                title = { Text(course?.name ?: "Loading...", style = MaterialTheme.typography.headlineMedium) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    val currentCourse = course
                    if (currentCourse != null && currentCourse.encodedPolyline != null) {
                        IconButton(onClick = { onEditCourse(currentCourse.id, currentCourse.encodedPolyline) }) {
                            Icon(Icons.Default.Edit, contentDescription = "Edit Segments")
                        }
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            Spacer(modifier = Modifier.height(16.dp))
            
            // Map
            course?.let { crs ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp)
                        .padding(horizontal = 24.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .shadow(2.dp)
                ) {
                    AndroidView(
                        factory = { ctx ->
                            WebView(ctx).apply {
                                layoutParams = android.view.ViewGroup.LayoutParams(
                                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                    android.view.ViewGroup.LayoutParams.MATCH_PARENT
                                )
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                
                                webViewClient = object : WebViewClient() {
                                    override fun onPageFinished(view: WebView?, url: String?) {
                                        view?.evaluateJavascript("updateMarkers(${crs.startLat}, ${crs.startLng}, ${crs.endLat}, ${crs.endLng});", null)
                                        if (crs.encodedPolyline != null) {
                                            val escaped = crs.encodedPolyline.replace("\\", "\\\\").replace("\"", "\\\"")
                                            view?.evaluateJavascript("drawCourseRoute(\"${escaped}\");", null)
                                        } else {
                                            val midLat = (crs.startLat + crs.endLat) / 2
                                            val midLng = (crs.startLng + crs.endLng) / 2
                                            view?.evaluateJavascript("map.setView([${midLat}, ${midLng}], 12);", null)
                                        }
                                    }
                                }
                                loadUrl("file:///android_asset/leaflet_map.html")
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Scan Action
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                if (isScanning) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = MaterialTheme.colorScheme.primary,
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = scanStatus,
                            style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                        )
                    }
                } else {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilledTonalButton(
                            onClick = { viewModel.scanActivities(forceRescan = false) },
                            shape = MaterialTheme.shapes.large,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = "Scan", modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Scan Recent Rides")
                        }

                        OutlinedButton(
                            onClick = { viewModel.scanActivities(forceRescan = true) },
                            shape = MaterialTheme.shapes.large,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Text("Force Rescan All")
                        }
                    }
                }
            }


            Spacer(modifier = Modifier.height(16.dp))

            // Year Filter
            if (availableYears.size > 1) {
                LazyRow(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(availableYears) { year ->
                        FilterChip(
                            selected = year == selectedYear,
                            onClick = { viewModel.setSelectedYear(year) },
                            label = { Text(year) },
                            colors = FilterChipDefaults.filterChipColors(),
                            shape = MaterialTheme.shapes.small
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            // Leaderboard List
            if (matches.isEmpty() && !isScanning) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                        Text("No attempts found for this segment.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = { viewModel.scanActivities(forceRescan = true) },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Some rides may not have been scanned — Rescan all")
                        }
                    }
                }
            } else {

                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    itemsIndexed(matches) { index, match ->
                        ElevatedCard(
                            modifier = Modifier
                                .fillMaxWidth()
                                .combinedClickable(
                                    onClick = { onMatchClick(match.activityId) },
                                    onLongClick = { matchToDelete = match }
                                ),
                            colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                            shape = RoundedCornerShape(12.dp)
                        ) {

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Left: Gold square trophy
                                Box(
                                    modifier = Modifier
                                        .defaultMinSize(minWidth = 40.dp)
                                        .height(40.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (index == 0) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest)
                                        .padding(horizontal = 4.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "#${index + 1}/${matches.size}",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = if (index == 0) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1
                                    )
                                }
                                
                                Spacer(modifier = Modifier.width(16.dp))
                                
                                // Center: Time and Date
                                val mins = match.timeSeconds / 60
                                val secs = match.timeSeconds % 60
                                val timeText = "%02d:%02d".format(mins, secs)
                                val displayTime = if (match.estimatedTime) "~$timeText" else timeText
                                val timeColor = if (match.estimatedTime) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurface

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = displayTime,
                                        style = MaterialTheme.typography.titleMedium.copy(
                                            color = timeColor
                                        )
                                    )
                                    Text(
                                        text = if (match.estimatedTime) "${match.date} (est.)" else match.date,
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        ),
                                        maxLines = 1
                                    )
                                }

                                
                                // Right: Power Output
                                Text(
                                    text = "⚡ ${match.avgWatts ?: "--"}w",
                                    style = MaterialTheme.typography.labelLarge.copy(
                                        color = MaterialTheme.colorScheme.secondary
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
