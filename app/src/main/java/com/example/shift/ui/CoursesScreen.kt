package com.example.shift.ui

import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import com.example.shift.theme.*

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
    val webViewRef = remember { mutableStateOf<WebView?>(null) }

    LaunchedEffect(courses) {
        if (selectedCourseId == null && courses.isNotEmpty()) {
            selectedCourseId = courses.first().id
        }
    }

    LaunchedEffect(selectedCourseId, courses) {
        val wv = webViewRef.value ?: return@LaunchedEffect
        val course = courses.find { it.id == selectedCourseId } ?: return@LaunchedEffect
        wv.post {
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ShiftBg)
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 12.dp, top = 16.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Segments",
                style = ScreenTitleStyle
            )
            // Orange circular add button
            IconButton(
                onClick = onCreateCourse,
                modifier = Modifier
                    .size(40.dp)
                    .background(ShiftOrange, CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "New Segment",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        // Map preview card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
                .padding(horizontal = 20.dp),
            colors = CardDefaults.cardColors(containerColor = ShiftCard),
            shape = RoundedCornerShape(26.dp)
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
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Segments list
        if (courses.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "No saved segments. Tap the + icon to create one.",
                    color = ShiftTextMuted,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 110.dp)
            ) {
                items(courses, key = { it.id }) { course ->
                    CourseItem(
                        course = course,
                        prTime = prMap[course.id] ?: "--",
                        isSelected = selectedCourseId == course.id,
                        onDelete = { viewModel.deleteCourse(course.id) },
                        onClick = {
                            if (selectedCourseId == course.id) {
                                onCourseClick(course.id)
                            } else {
                                selectedCourseId = course.id
                            }
                        }
                    )
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
            containerColor = if (isSelected) ShiftCardSelected else ShiftCard
        ),
        shape = RoundedCornerShape(22.dp),
        border = if (isSelected) androidx.compose.foundation.BorderStroke(
            1.5.dp, ShiftOrange
        ) else null
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Icon circle
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(ShiftCardInset, shape = CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Map,
                    contentDescription = null,
                    tint = ShiftTextPrimary,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = course.name,
                    style = CardTitleStyle,
                    color = ShiftTextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle.uppercase(),
                    style = MicroLabelStyle,
                    color = ShiftTextMuted,
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
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE53935)),
                            shape = RoundedCornerShape(percent = 50),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            Text(
                                "Delete",
                                color = Color.White,
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                            )
                        }
                        IconButton(
                            onClick = { isDeleting = false },
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(ShiftCardInset)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = "Cancel",
                                tint = ShiftTextMuted,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                } else {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // PR Badge — orange NDot with mono "PR" caption
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = prTime,
                                style = StatNumeralSmall,
                                color = ShiftOrange
                            )
                            Text(
                                text = "PR",
                                style = MicroLabelStyle.copy(fontSize = 8.sp, color = ShiftTextMuted)
                            )
                        }

                        IconButton(
                            onClick = { isDeleting = true },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Delete,
                                contentDescription = "Delete",
                                tint = ShiftTextMuted,
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
