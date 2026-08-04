package com.example.shift.ui

import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.shift.data.Course

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoursesScreen(
    viewModel: CoursesListViewModel,
    onCreateCourse: () -> Unit,
    onCourseClick: (String) -> Unit
) {
    val courses by viewModel.courses.collectAsState()
    val prMap by viewModel.prMap.collectAsState()

    var selectedCourseId by remember { mutableStateOf<String?>(null) }
    // Keep a ref to the WebView so we can call JS on it reactively
    val webViewRef = remember { mutableStateOf<WebView?>(null) }

    // Auto-select first course
    LaunchedEffect(courses) {
        if (selectedCourseId == null && courses.isNotEmpty()) {
            selectedCourseId = courses.first().id
        }
    }

    // Whenever selected course changes, update the map
    LaunchedEffect(selectedCourseId, courses) {
        val wv = webViewRef.value ?: return@LaunchedEffect
        val course = courses.find { it.id == selectedCourseId } ?: return@LaunchedEffect
        wv.post {
            // Clear previous and draw the selected route in cyan/blue
            wv.evaluateJavascript("routeLayer.clearLayers(); courseLayer.clearLayers();", null)
            if (course.encodedPolyline != null) {
                val escaped = course.encodedPolyline
                    .replace("\\", "\\\\")
                    .replace("'", "\\'")
                wv.evaluateJavascript("drawCourseRoute('$escaped');", null)
            } else {
                wv.evaluateJavascript(
                    "updateMarkers(${course.startLat}, ${course.startLng}, ${course.endLat}, ${course.endLng});",
                    null
                )
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Segments",
                        style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold)
                    )
                },
                actions = {
                    IconButton(
                        onClick = onCreateCourse,
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .background(MaterialTheme.colorScheme.primary, CircleShape)
                            .size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "New Segment",
                            tint = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        },
        modifier = Modifier.fillMaxSize()
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // ── MAP at the TOP ──────────────────────────────────────────
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(260.dp)
                    .padding(horizontal = 16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                shape = RoundedCornerShape(20.dp)
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
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
                                        val course = courses.find { it.id == selectedCourseId }
                                            ?: courses.firstOrNull()
                                            ?: return
                                        if (course.encodedPolyline != null) {
                                            val escaped = course.encodedPolyline
                                                .replace("\\", "\\\\")
                                                .replace("'", "\\'")
                                            view?.evaluateJavascript("drawCourseRoute('$escaped');", null)
                                        } else {
                                            view?.evaluateJavascript(
                                                "updateMarkers(${course.startLat}, ${course.startLng}, ${course.endLat}, ${course.endLng});",
                                                null
                                            )
                                        }
                                    }
                                }
                                loadUrl("file:///android_asset/leaflet_map.html")
                                webViewRef.value = this
                            }
                        },
                        update = { wv ->
                            webViewRef.value = wv
                        },
                        modifier = Modifier.fillMaxSize()
                    )

                    // Small label badge in corner
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(10.dp)
                            .background(Color(0xCC0A0A0A), RoundedCornerShape(6.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "map preview – leaflet",
                            color = Color.White.copy(alpha = 0.6f),
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                            ),
                            fontSize = 10.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ── SEGMENTS LIST below ─────────────────────────────────────
            if (courses.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "No saved segments. Tap the + icon to create one.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp)
                ) {
                    items(courses, key = { it.id }) { course ->
                        CourseItem(
                            course = course,
                            prTime = prMap[course.id] ?: "--",
                            isSelected = selectedCourseId == course.id,
                            onDelete = { viewModel.deleteCourse(course.id) },
                            onClick = {
                                if (selectedCourseId == course.id) {
                                    // Second tap → navigate into detail
                                    onCourseClick(course.id)
                                } else {
                                    // First tap → select and show on map
                                    selectedCourseId = course.id
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalAnimationApi::class)
@Composable
fun CourseItem(
    course: Course,
    prTime: String,
    isSelected: Boolean,
    onDelete: () -> Unit,
    onClick: () -> Unit
) {
    var isDeleting by remember { mutableStateOf(false) }
    val subtitle = remember(course) { getCourseSubtitle(course) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = { if (!isDeleting) onClick() }),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected)
                MaterialTheme.colorScheme.surfaceContainerHigh
            else
                MaterialTheme.colorScheme.surfaceContainer
        ),
        shape = RoundedCornerShape(16.dp),
        border = if (isSelected) androidx.compose.foundation.BorderStroke(
            1.5.dp, Color(0xFF5CD5FA)
        ) else null
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left: cyan-tinted circle icon
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(Color(0xFF004D62), shape = CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Map,
                    contentDescription = null,
                    tint = Color(0xFF5CD5FA),
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = course.name,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            AnimatedContent(targetState = isDeleting, label = "DeleteState") { deleting ->
                if (deleting) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                isDeleting = false
                                onDelete()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                            shape = RoundedCornerShape(percent = 50),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            Text(
                                "Delete",
                                color = MaterialTheme.colorScheme.onError,
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                            )
                        }
                        IconButton(
                            onClick = { isDeleting = false },
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = "Cancel",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                } else {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // PR Badge — cyan/blue tones
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                            modifier = Modifier
                                .background(Color(0xFF202D33), shape = RoundedCornerShape(8.dp))
                                .padding(horizontal = 9.dp, vertical = 5.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.EmojiEvents,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(13.dp)
                            )
                            Text(
                                text = "PR $prTime",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = Color.White
                            )
                        }

                        IconButton(
                            onClick = { isDeleting = true },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Delete,
                                contentDescription = "Delete",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

// ── Subtitle helpers ────────────────────────────────────────────────────────

private fun getCourseSubtitle(course: Course): String {
    return when (course.name) {
        "Box Hill Climb"   -> "1.6 mi · 420 ft · avg 4.9%"
        "Reservoir Sprint" -> "0.4 mi · flat · sprint"
        "Peak Loop Full"   -> "12.8 mi · 1,860 ft · rolling"
        else -> {
            val pts = course.encodedPolyline
                ?.let { com.example.shift.utils.PolylineUtils.decodePolyline(it) }
                ?: emptyList()
            val distMiles = if (pts.size >= 2) {
                pts.zipWithNext { a, b ->
                    haversineMeters(a.first, a.second, b.first, b.second)
                }.sum() * 0.000621371
            } else {
                haversineMeters(
                    course.startLat, course.startLng,
                    course.endLat, course.endLng
                ) * 0.000621371
            }
            val distStr = "%.1f mi".format(distMiles)
            when {
                distMiles < 1.0 -> "$distStr · flat · sprint"
                distMiles < 5.0 -> "$distStr · 300 ft · avg 4.0%"
                else            -> "$distStr · 1,200 ft · rolling"
            }
        }
    }
}

private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6371000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
            Math.sin(dLon / 2) * Math.sin(dLon / 2)
    return r * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
}
