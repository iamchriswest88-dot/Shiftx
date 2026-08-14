package com.example.shift.ui

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.shift.theme.MicroLabelStyle
import com.example.shift.theme.RouteLineColor
import com.example.shift.theme.ShiftTextMuted
import com.example.shift.theme.ShiftTextPrimary
import com.example.shift.theme.StatNumeralHero
import androidx.compose.ui.graphics.nativeCanvas
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import android.Manifest
import android.content.pm.PackageManager
import kotlinx.coroutines.launch

class WebAppInterface(
    private val onMapClick: (Double, Double) -> Unit,
    private val onMarkerMoved: (Boolean, Double, Double) -> Unit
) {
    @JavascriptInterface
    fun onMapClick(lat: Double, lng: Double) {
        onMapClick.invoke(lat, lng)
    }

    @JavascriptInterface
    fun onMarkerMoved(isStart: Boolean, lat: Double, lng: Double) {
        onMarkerMoved.invoke(isStart, lat, lng)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MapScreen(
    activityId: String,
    courseId: String? = null,
    viewModel: CourseViewModel,
    mainViewModel: MainViewModel,
    onBack: () -> Unit,
    isCreationMode: Boolean = false,
    onDelete: (() -> Unit)? = null,
    onCourseClick: ((String) -> Unit)? = null
) {

    LaunchedEffect(Unit) {
        if (isCreationMode && courseId == null) {
            viewModel.resetGates()
        }
    }

    LaunchedEffect(activityId) {
        if (!activityId.startsWith("hc_")) {
            viewModel.loadActivity(activityId)
        }
    }
    
    LaunchedEffect(courseId) {
        if (courseId != null) {
            viewModel.loadCourseForEdit(courseId)
        }
    }

    val context = LocalContext.current
    var hasLocationPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { isGranted -> hasLocationPermission = isGranted }
    )

    LaunchedEffect(isCreationMode) {
        if (isCreationMode && !hasLocationPermission) {
            permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    val stream by viewModel.stream.collectAsState()
    val activity by viewModel.activity.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val startMarker by viewModel.startMarker.collectAsState()
    val endMarker by viewModel.endMarker.collectAsState()
    val courseTime by viewModel.courseTime.collectAsState()
    val matchedCourses by viewModel.matchedCourses.collectAsState()

    val courseNameForEdit by viewModel.courseNameForEdit.collectAsState()
    var courseName by remember { mutableStateOf("") }
    
    LaunchedEffect(courseNameForEdit) {
        if (courseNameForEdit.isNotBlank()) {
            courseName = courseNameForEdit
        }
    }

    val gateStep = when {
        startMarker == null -> 1
        endMarker == null -> 2
        else -> 3
    }

    if (isLoading) {
        com.example.shift.ui.components.FullScreenLoading()
    } else {
        val sheetState = rememberStandardBottomSheetState(
            initialValue = SheetValue.PartiallyExpanded,
            skipHiddenState = true
        )
        val scaffoldState = rememberBottomSheetScaffoldState(bottomSheetState = sheetState)
        var selectedTab by remember { mutableIntStateOf(0) }

        // A fresh creation starts from a blank map. The view model is shared
        // across screens, so on the first frame `stream` still holds whatever
        // activity was open last — it gets cleared by loadActivity("new"), but
        // by then the old route has already been drawn. Ignore it at the source.
        val isBlankCreation = isCreationMode && activityId == "new"
        val latlngs = if (isBlankCreation) emptyList() else stream?.latlng ?: emptyList()
        val watts = if (isBlankCreation) emptyList() else stream?.watts ?: emptyList()
        val jsonArray = remember(latlngs, watts) {
            latlngs.mapIndexedNotNull { index, pt ->
                if (pt[0] == 0.0 && pt[1] == 0.0) return@mapIndexedNotNull null
                val w = if (index < watts.size) watts[index] else null
                if (w != null) "[${pt[0]},${pt[1]},$w]" else "[${pt[0]},${pt[1]}]"
            }.joinToString(",", "[", "]")
        }

        BottomSheetScaffold(
            scaffoldState = scaffoldState,
            sheetContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
            sheetContentColor = MaterialTheme.colorScheme.onSurface,
            sheetShape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp, bottomStart = 0.dp, bottomEnd = 0.dp),
            // Taller so the second stats row (avg mph, avg W, TSS) is visible without
            // dragging the drawer open.
            sheetPeekHeight = if (isCreationMode) 320.dp else 320.dp,
            sheetDragHandle = { BottomSheetDefaults.DragHandle(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)) },
            sheetContent = {
                if (isCreationMode) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 20.dp, vertical = 8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                StepDot(number = "1", label = "Start", isActive = gateStep == 1, isComplete = gateStep > 1, color = MaterialTheme.colorScheme.primary)
                                Box(modifier = Modifier.width(24.dp).height(1.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f)))
                                StepDot(number = "2", label = "Finish", isActive = gateStep == 2, isComplete = gateStep > 2, color = MaterialTheme.colorScheme.primary)
                                Box(modifier = Modifier.width(24.dp).height(1.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f)))
                                StepDot(number = "3", label = "Save", isActive = gateStep == 3, isComplete = false, color = MaterialTheme.colorScheme.primary)
                            }
                            if (startMarker != null || endMarker != null) {
                                IconButton(onClick = { viewModel.resetGates() }, modifier = Modifier.size(32.dp)) {
                                    Icon(Icons.Default.Refresh, contentDescription = "Reset", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                                }
                            }
                        }

                        OutlinedTextField(
                            value = courseName,
                            onValueChange = { courseName = it },
                            placeholder = { Text("Segment name", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                unfocusedContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f),
                                focusedContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                                unfocusedBorderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f),
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                cursorColor = MaterialTheme.colorScheme.primary,
                                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                                unfocusedTextColor = MaterialTheme.colorScheme.onSurface
                            ),
                            singleLine = true
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        Button(
                            onClick = {
                                if (courseName.isNotBlank() && startMarker != null && endMarker != null) {
                                    viewModel.saveCourse(courseName, courseId)
                                    onBack()
                                }
                            },
                            enabled = courseName.isNotBlank() && startMarker != null && endMarker != null,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                disabledContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                                contentColor = MaterialTheme.colorScheme.onSurface,
                                disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(vertical = 14.dp)
                        ) {
                            Text("SAVE SEGMENT", fontWeight = FontWeight(200), letterSpacing = 1.sp)
                        }
                        Spacer(modifier = Modifier.height(24.dp))
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 20.dp, vertical = 8.dp)
                            .navigationBarsPadding()
                    ) {
                        // Title and segment count badge row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = activity?.name ?: "Ride",
                                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f)
                            )
                            if (matchedCourses.isNotEmpty()) {
                                Spacer(modifier = Modifier.width(8.dp))
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                    contentColor = MaterialTheme.colorScheme.onSurface
                                ) {
                                    androidx.compose.foundation.layout.Row(
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Icon(
                                            imageVector = androidx.compose.material.icons.Icons.Default.Layers,
                                            contentDescription = "Matched Routes",
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Text(
                                            text = "${matchedCourses.size}",
                                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold)
                                        )
                                    }
                                }
                            }
                        }
                        
                        // Subtitle: Date
                        activity?.start_date_local?.let { raw ->
                            val dateFormatted = try {
                                val parsed = java.time.ZonedDateTime.parse(raw)
                                parsed.format(java.time.format.DateTimeFormatter.ofPattern("MMM dd, yyyy · h:mm a"))
                            } catch (e: Exception) {
                                try {
                                    val parsed = java.time.LocalDateTime.parse(raw)
                                    parsed.format(java.time.format.DateTimeFormatter.ofPattern("MMM dd, yyyy · h:mm a"))
                                } catch (e2: Exception) {
                                    raw.take(16)
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            // Same voice as the monthly card's month header.
                            Text(
                                text = dateFormatted.uppercase(),
                                style = MicroLabelStyle.copy(
                                    letterSpacing = 2.sp,
                                    color = ShiftTextMuted
                                )
                            )
                        }
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        // Stats Row (4 Columns)
                        activity?.let { act ->
                            val distMi = (act.distance ?: 0.0) * 0.000621371
                            val movingMins = (act.moving_time ?: 0) / 60
                            val hrs = movingMins / 60
                            val mins = movingMins % 60
                            val movingStr = if (hrs > 0) "%d:%02d".format(hrs, mins) else "%d".format(mins)
                            
                            val elevAlt = (act.total_elevation_gain ?: 0.0) * 3.28084
                            val avgWattsVal = act.icu_average_watts
                            // average_speed is m/s from Intervals.
                            val avgMph = act.average_speed?.let { it * 2.23694 }
                            val tss = act.icu_training_load

                            // Two rows of three, matching the monthly summary's columns —
                            // MOVING carries the accent there, so it carries it here.
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                StatCard("MILES", "%.1f".format(distMi), Modifier.weight(1f))
                                StatCard("MOVING", movingStr, Modifier.weight(1f), accent = true)
                                StatCard("ELEV FT", "%,.0f".format(elevAlt), Modifier.weight(1f))
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                StatCard("AVG MPH", avgMph?.let { "%.1f".format(it) } ?: "--", Modifier.weight(1f))
                                StatCard("AVG W", if (avgWattsVal != null) "$avgWattsVal" else "--", Modifier.weight(1f))
                                StatCard("TSS", tss?.let { "%,.0f".format(it) } ?: "--", Modifier.weight(1f))
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(28.dp))

                        var elevationProfile by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<List<Pair<Double, Double>>?>(null) }
                        var isLoadingProfile by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(true) }

                        androidx.compose.runtime.LaunchedEffect(activityId) {
                            isLoadingProfile = true
                            elevationProfile = mainViewModel.fetchElevationProfile(activityId)
                            isLoadingProfile = false
                        }

                        if (isLoadingProfile) {
                            Box(modifier = Modifier.fillMaxWidth().height(100.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                            }
                        } else if (elevationProfile != null && elevationProfile!!.isNotEmpty()) {
                            val profile = elevationProfile!!
                            // Same colour as the route on the map, so the profile reads as
                            // the same line seen side-on.
                            val primaryColor = com.example.shift.theme.RouteLineColor
                            // Same ground as the sheet, so the graph sits in the drawer
                            // rather than looking like a card dropped onto it.
                            val surfaceColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)
                            val onSurfaceVariantColor = MaterialTheme.colorScheme.onSurfaceVariant
                            
                            androidx.compose.foundation.Canvas(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(100.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(surfaceColor)
                            ) {
                                val path = androidx.compose.ui.graphics.Path()
                                val width = size.width
                                val height = size.height
                                
                                val minAlt = profile.minOf { it.second }
                                val maxAlt = profile.maxOf { it.second }
                                val maxDist = profile.last().first
                                
                                val altRange = if (maxAlt - minAlt == 0.0) 1.0 else maxAlt - minAlt
                                val distRange = if (maxDist == 0.0) 1.0 else maxDist
                                
                                path.moveTo(0f, height)
                                
                                profile.forEach { (dist, alt) ->
                                    val x = ((dist / distRange) * width).toFloat()
                                    val y = height - (((alt - minAlt) / altRange) * height).toFloat()
                                    path.lineTo(x, y)
                                }
                                path.lineTo(width, height)
                                path.close()
                                
                                drawPath(
                                    path = path,
                                    brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                                        colors = listOf(primaryColor.copy(alpha = 0.5f), androidx.compose.ui.graphics.Color.Transparent)
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
                                    color = primaryColor,
                                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx())
                                )
                                
                                drawContext.canvas.nativeCanvas.apply {
                                    val textPaint = android.graphics.Paint().apply {
                                        color = android.graphics.Color.argb(
                                            (onSurfaceVariantColor.alpha * 255).toInt(),
                                            (onSurfaceVariantColor.red * 255).toInt(),
                                            (onSurfaceVariantColor.green * 255).toInt(),
                                            (onSurfaceVariantColor.blue * 255).toInt()
                                        )
                                        textSize = 30f
                                        isAntiAlias = true
                                    }
                                    
                                    val maxAltFt = maxAlt * 3.28084
                                    val minAltFt = minAlt * 3.28084
                                    drawText("%.0f ft".format(maxAltFt), 16f, 40f, textPaint)
                                    drawText("%.0f ft".format(minAltFt), 16f, height - 16f, textPaint)
                                    
                                    val maxDistMi = maxDist * 0.000621371
                                    val distText = "%.1f mi".format(maxDistMi)
                                    val textWidth = textPaint.measureText(distText)
                                    drawText(distText, width - textWidth - 16f, height - 16f, textPaint)
                                }
                            }
                        }
                        
                        // Matched Segments List
                        if (matchedCourses.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(28.dp))
                            Text(
                                text = "MATCHED SEGMENTS",
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.sp
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                            
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                matchedCourses.forEach { matchInfo ->
                                    val mins = matchInfo.timeSeconds / 60
                                    val secs = matchInfo.timeSeconds % 60
                                    val timeFormatted = "%d:%02d".format(mins, secs)
                                    val isPr = matchInfo.rank == 1
                                    
                                    val timeLabel = if (isPr) {
                                        "$timeFormatted · PR"
                                    } else {
                                        timeFormatted
                                    }
                                    
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable(enabled = onCourseClick != null) {
                                                onCourseClick?.invoke(matchInfo.course.id)
                                            }
                                            .padding(vertical = 12.dp, horizontal = 4.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Icon(
                                                imageVector = if (isPr) androidx.compose.material.icons.Icons.Default.EmojiEvents else androidx.compose.material.icons.Icons.Default.Timer,
                                                contentDescription = if (isPr) "PR" else "Time",
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(20.dp)
                                            )
                                            Spacer(modifier = Modifier.width(12.dp))
                                            Text(
                                                text = matchInfo.course.name,
                                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                                color = MaterialTheme.colorScheme.onSurface,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = timeLabel,
                                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                                color = if (isPr) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = "›",
                                                style = MaterialTheme.typography.titleLarge,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                            )
                                        }
                                    }

                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(24.dp))
                    }
                }
            },
            content = { paddingValues ->
                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    // The map view itself stays full-screen — resizing it looked like
                    // the map shrinking. Instead the ROUTE is re-framed: while the
                    // drawer is up it zooms out to fit the whole course in the strip
                    // above the sheet, and expands again as the drawer drops.
                    // requireOffset throws before first layout, hence the guard.
                    val density = LocalDensity.current.density
                    val containerHeightPx = with(LocalDensity.current) { maxHeight.toPx() }
                    val sheetTopPx = runCatching { scaffoldState.bottomSheetState.requireOffset() }
                        .getOrDefault(containerHeightPx)
                    // Leaflet padding is in CSS pixels, not device pixels.
                    val sheetOverlapCssPx =
                        ((containerHeightPx - sheetTopPx).coerceAtLeast(0f) / density).toInt()

                    var mapView by remember { mutableStateOf<WebView?>(null) }
                    LaunchedEffect(sheetOverlapCssPx) {
                        kotlinx.coroutines.delay(120)
                        mapView?.evaluateJavascript(
                            "if (typeof refit === 'function') refit($sheetOverlapCssPx);",
                            null
                        )
                    }

                    Box(modifier = Modifier.fillMaxSize()) {
                    AndroidView(
                        factory = { ctx ->
                            WebView(ctx).apply {
                                setBackgroundColor(0) // Transparent
                                layoutParams = android.view.ViewGroup.LayoutParams(
                                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                    android.view.ViewGroup.LayoutParams.MATCH_PARENT
                                )
                                settings.javaScriptEnabled = true
                                settings.allowFileAccess = true
                                settings.domStorageEnabled = true
                                settings.setGeolocationEnabled(true)
                                webChromeClient = object : WebChromeClient() {
                                    override fun onGeolocationPermissionsShowPrompt(
                                        origin: String?,
                                        callback: android.webkit.GeolocationPermissions.Callback?
                                    ) {
                                        callback?.invoke(origin, hasLocationPermission, false)
                                    }
                                    override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage?): Boolean {
                                        android.util.Log.i("ShiftMapConsole", "${consoleMessage?.message()} -- From line ${consoleMessage?.lineNumber()} of ${consoleMessage?.sourceId()}")
                                        return super.onConsoleMessage(consoleMessage)
                                    }
                                }
                                addJavascriptInterface(WebAppInterface(
                                    onMapClick = { lat, lng -> if(isCreationMode) viewModel.onMapClick(lat, lng) },
                                    onMarkerMoved = { isStart, lat, lng -> if(isCreationMode) viewModel.onMarkerMoved(isStart, lat, lng) }
                                ), "Android")

                                webViewClient = object : WebViewClient() {
                                    override fun onPageFinished(view: WebView?, url: String?) {
                                        if (latlngs.isNotEmpty()) {
                                            view?.evaluateJavascript("if (typeof drawRoute === 'function') drawRoute($jsonArray, false);", null)
                                        } else {
                                            view?.evaluateJavascript("if (typeof map !== 'undefined') map.setView([53.382094, -2.538581], 14);", null)
                                        }
                                        // Draw matched segment overlays in purple
                                        view?.evaluateJavascript("if (typeof clearSegmentOverlays === 'function') clearSegmentOverlays();", null)
                                        matchedCourses.forEach { matchInfo ->
                                            val poly = matchInfo.course.encodedPolyline
                                            if (poly != null) {
                                                val escaped = poly.replace("\\", "\\\\").replace("'", "\\'")
                                                view?.evaluateJavascript("if (typeof drawSegmentOverlay === 'function') drawSegmentOverlay('$escaped');", null)
                                            }
                                        }
                                    }
                                }

                                loadUrl("file:///android_asset/leaflet_map.html")
                            }.also { mapView = it }
                        },
                        update = { view ->
                            val sLat = startMarker?.latitude
                            val sLng = startMarker?.longitude
                            val eLat = endMarker?.latitude
                            val eLng = endMarker?.longitude
                            view.evaluateJavascript("if (typeof updateMarkers === 'function') updateMarkers($sLat, $sLng, $eLat, $eLng);", null)
                            if (latlngs.isNotEmpty()) {
                                view.evaluateJavascript("if (typeof drawRoute === 'function') drawRoute($jsonArray, false);", null)
                            } else {
                                // No route this composition — take down anything a
                                // previous one drew, or it lingers on the map.
                                view.evaluateJavascript("if (typeof routeLayer !== 'undefined') routeLayer.clearLayers();", null)
                            }
                            // Redraw segment overlays in purple
                            view.evaluateJavascript("if (typeof clearSegmentOverlays === 'function') clearSegmentOverlays();", null)
                            matchedCourses.forEach { matchInfo ->
                                val poly = matchInfo.course.encodedPolyline
                                if (poly != null) {
                                    val escaped = poly.replace("\\", "\\\\").replace("'", "\\'")
                                    view.evaluateJavascript("if (typeof drawSegmentOverlay === 'function') drawSegmentOverlay('$escaped');", null)
                                }
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(start = 24.dp, end = 24.dp, top = 36.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            IconButton(
                                onClick = onBack, 
                                modifier = Modifier
                                    .size(40.dp)
                                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f), CircleShape)
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack, 
                                    contentDescription = "Back", 
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            if (isCreationMode) {
                                Text(
                                    text = if (courseId != null) "Edit segment" else "New segment",
                                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.padding(horizontal = 16.dp)
                                )
                                if (onDelete != null) {
                                    IconButton(
                                        onClick = onDelete, 
                                        modifier = Modifier
                                            .size(40.dp)
                                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f), CircleShape)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.Delete, 
                                            contentDescription = "Delete", 
                                            tint = MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                }
                            } else {
                                IconButton(
                                    onClick = { /* Toggle map layers */ }, 
                                    modifier = Modifier
                                        .size(40.dp)
                                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f), CircleShape)
                                ) {
                                    Icon(
                                        imageVector = androidx.compose.material.icons.Icons.Default.Layers, 
                                        contentDescription = "Map Style", 
                                        tint = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                    }
                }
            }
        )
    }
}

@Composable
fun TabToggle(
    selectedTab: Int,
    onTabSelected: (Int) -> Unit,
    tabs: List<String>
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f), shape = RoundedCornerShape(24.dp))
            .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f), shape = RoundedCornerShape(24.dp))
            .padding(4.dp)
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            tabs.forEachIndexed { index, title ->
                val isSelected = selectedTab == index
                val animAlpha by animateFloatAsState(targetValue = if (isSelected) 1f else 0f, label="tabAlpha")
                
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(20.dp))
                        .background(MaterialTheme.colorScheme.primary)
                        .clickable { onTabSelected(index) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight(200),
                            letterSpacing = 1.sp
                        ),
                        color = if (isSelected) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * One stat in the activity drawer, styled to mirror MonthlySummaryCard on the
 * rides list: hero numeral over a micro label, flat on the sheet rather than
 * boxed. Same recipe with the ink swapped for the white ground — black where
 * the card is white, and the accent value blue where the card's is orange.
 */
@Composable
fun StatCard(label: String, value: String, modifier: Modifier = Modifier, accent: Boolean = false) {
    Column(modifier = modifier) {
        Text(
            text = value,
            style = StatNumeralHero,
            color = if (accent) RouteLineColor else ShiftTextPrimary
        )
        Text(
            text = label,
            style = MicroLabelStyle.copy(color = ShiftTextMuted)
        )
    }
}

@Composable
fun ElevationGraph(altitude: List<Double>, time: List<Int>?, distance: List<Double>?, modifier: Modifier = Modifier) {
    if (altitude.isEmpty()) return

    Column(modifier = modifier) {
        val gainMeters = altitude.zipWithNext { a, b -> if (b > a) b - a else 0.0 }.sum()
        val gainFeet = gainMeters * 3.28084
        val maxFeet = (altitude.maxOrNull() ?: 0.0) * 3.28084
        val minFeet = (altitude.minOrNull() ?: 0.0) * 3.28084

        var timeClimbingSecs = 0
        if (time != null && distance != null && time.size == altitude.size && distance.size == altitude.size) {
            for (i in 1 until altitude.size) {
                val dAlt = altitude[i] - altitude[i-1]
                val dDist = distance[i] - distance[i-1]
                if (dDist > 0) {
                    val gradient = dAlt / dDist
                    if (gradient > 0.03) {
                        timeClimbingSecs += (time[i] - time[i-1])
                    }
                }
            }
        }
        val climbingHrs = timeClimbingSecs / 3600
        val climbingM = (timeClimbingSecs % 3600) / 60

        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("${gainFeet.toInt()} ft", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight(200)), color = MaterialTheme.colorScheme.onSurface)
                Text("Total Climbing", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha=0.6f))
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (climbingHrs > 0) "${climbingHrs}h ${climbingM}m" else "${climbingM}m", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight(200)), color = MaterialTheme.colorScheme.onSurface)
                Text("Time Climbing (>3%)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha=0.6f))
            }
        }

        val textPaint = remember {
            android.graphics.Paint().apply {
                color = android.graphics.Color.parseColor("#8A9296")
                textSize = 32f
                textAlign = android.graphics.Paint.Align.RIGHT
                isAntiAlias = true
            }
        }
        val textPaintCenter = remember {
            android.graphics.Paint().apply {
                color = android.graphics.Color.parseColor("#8A9296")
                textSize = 32f
                textAlign = android.graphics.Paint.Align.CENTER
                isAntiAlias = true
            }
        }

        val primaryColor = MaterialTheme.colorScheme.primary
        val primaryContainerColor = MaterialTheme.colorScheme.primaryContainer

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(280.dp)
        ) {
            androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                val minAlt = altitude.minOrNull() ?: 0.0
                val maxAlt = altitude.maxOrNull() ?: 1.0
                val range = (maxAlt - minAlt).coerceAtLeast(1.0)

                val yStep = if (range > 1000) 500 else if (range > 500) 100 else if (range > 100) 50 else if (range > 50) 25 else if (range > 20) 10 else 5
                val minAltInt = (minAlt / yStep).toInt() * yStep
                val maxAltInt = (maxAlt / yStep).toInt() * yStep + yStep
                val adjustedRange = (maxAltInt - minAltInt).coerceAtLeast(1).toFloat()

                val maxDist = distance?.lastOrNull()?.div(1000.0) ?: 0.0
                val xStep = if (maxDist > 100) 20 else if (maxDist > 50) 10 else if (maxDist > 20) 5 else if (maxDist > 5) 2 else 1

                val width = size.width
                val height = size.height
                
                val paddingLeft = 110f
                val paddingBottom = 70f
                val graphWidth = width - paddingLeft
                val graphHeight = height - paddingBottom
                val localGridColor = primaryColor.copy(alpha=0.1f) // fallback to avoid more errors

                // Y-axis grid lines and labels
                for (alt in minAltInt..maxAltInt step yStep) {
                    val y = graphHeight - ((alt - minAltInt) / adjustedRange * graphHeight).toFloat()
                    drawLine(localGridColor, start = androidx.compose.ui.geometry.Offset(paddingLeft, y), end = androidx.compose.ui.geometry.Offset(width, y), strokeWidth = 2f)
                    drawContext.canvas.nativeCanvas.drawText("$alt", paddingLeft - 20f, y + 10f, textPaint)
                }
                drawContext.canvas.nativeCanvas.drawText("m", paddingLeft - 20f, graphHeight + 40f, textPaint)

                // X-axis grid lines and labels
                if (maxDist > 0) {
                    val maxDistInt = (maxDist / xStep).toInt() * xStep
                    for (d in xStep..maxDistInt step xStep) {
                        val x = paddingLeft + ((d / maxDist) * graphWidth).toFloat()
                        drawLine(localGridColor, start = androidx.compose.ui.geometry.Offset(x, 0f), end = androidx.compose.ui.geometry.Offset(x, graphHeight), strokeWidth = 2f)
                        drawContext.canvas.nativeCanvas.drawText("$d km", x, graphHeight + 50f, textPaintCenter)
                    }
                }

                val path = androidx.compose.ui.graphics.Path()

                altitude.forEachIndexed { index, alt ->
                    val x = if (distance != null && maxDist > 0) {
                        paddingLeft + ((distance[index] / 1000.0) / maxDist * graphWidth).toFloat()
                    } else {
                        paddingLeft + (index.toFloat() / (altitude.size - 1) * graphWidth)
                    }
                    val y = graphHeight - ((alt - minAltInt) / adjustedRange * graphHeight).toFloat()
                    
                    if (index == 0) {
                        path.moveTo(x, y)
                    } else {
                        path.lineTo(x, y)
                    }
                }

                drawPath(
                    path = path,
                    color = primaryColor,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 4f)
                )

                val fillPath = androidx.compose.ui.graphics.Path().apply {
                    addPath(path)
                    lineTo(width, graphHeight)
                    lineTo(paddingLeft, graphHeight)
                    close()
                }

                drawPath(
                    path = fillPath,
                    color = primaryContainerColor
                )
            }
        }
    }
}

@Composable
fun StepDot(number: String, label: String, isActive: Boolean, isComplete: Boolean, color: Color) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(horizontal = 4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(
                    if (isActive || isComplete) color else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = if (isComplete) "âœ“" else number,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight(200),
                    fontSize = 10.sp
                ),
                color = if (isActive || isComplete) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
            color = if (isActive) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
