package com.example.shift.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import com.example.shift.theme.MicroLabelStyle
import com.example.shift.theme.RouteLineColor
import com.example.shift.theme.ShiftDarkSurface
import com.example.shift.theme.ShiftOrange
import com.example.shift.theme.ShiftTextMuted

class RouteCreatorWebAppInterface(private val onLocationSelected: (Double, Double) -> Unit) {
    @JavascriptInterface
    fun onStartLocationSet(lat: Double, lng: Double) {
        onLocationSelected(lat, lng)
    }
}

/**
 * The route planner, dressed like the activity map screen: full-screen light
 * map behind a bottom-sheet drawer of controls, orange accents, and the same
 * dark stats box the activity drawer and rides list use.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RouteCreatorScreen(viewModel: RouteCreatorViewModel, onBack: () -> Unit = {}) {
    val startLat by viewModel.startLat.collectAsState()
    val startLng by viewModel.startLng.collectAsState()
    val targetDistanceMiles by viewModel.targetDistanceMiles.collectAsState()
    val terrain by viewModel.terrain.collectAsState()
    val terrainRoutes by viewModel.terrainRoutes.collectAsState()
    val direction by viewModel.direction.collectAsState()
    val generatedRoute by viewModel.generatedRoute.collectAsState()
    val isGenerating by viewModel.isGenerating.collectAsState()
    val statusMessage by viewModel.statusMessage.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val context = LocalContext.current

    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    // When route generates, update map
    LaunchedEffect(generatedRoute) {
        if (generatedRoute != null) {
            val jsonArray = generatedRoute!!.coordinates.map { "[${it.first},${it.second}]" }.joinToString(",", "[", "]")
            webViewRef?.evaluateJavascript("displayRoute('$jsonArray');", null)
        } else {
            webViewRef?.evaluateJavascript("clearRoute();", null)
        }
    }

    val sheetState = rememberStandardBottomSheetState(
        initialValue = SheetValue.PartiallyExpanded,
        skipHiddenState = true
    )
    val scaffoldState = rememberBottomSheetScaffoldState(bottomSheetState = sheetState)

    BottomSheetScaffold(
        scaffoldState = scaffoldState,
        sheetContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
        sheetContentColor = MaterialTheme.colorScheme.onSurface,
        sheetShape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        sheetPeekHeight = 360.dp,
        sheetContent = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 8.dp)
                    .navigationBarsPadding()
            ) {
                Text(
                    text = "Route Planner",
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (startLat == null) "TAP THE MAP TO SET A START"
                        else "START SET · DRAG THE MARKER TO ADJUST",
                    style = MicroLabelStyle.copy(letterSpacing = 2.sp, color = ShiftTextMuted)
                )

                if (startLat != null && startLng != null) {
                    Spacer(modifier = Modifier.height(20.dp))

                    Text("DIRECTION", style = MicroLabelStyle.copy(color = ShiftTextMuted))
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = direction == null,
                            onClick = { viewModel.setDirection(null) },
                            label = { Text("Any", fontWeight = if (direction == null) FontWeight.Bold else FontWeight.Normal) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = ShiftOrange,
                                selectedLabelColor = Color.White
                            )
                        )
                        com.example.shift.data.RideDirection.entries.forEach { dir ->
                            val selected = direction == dir
                            FilterChip(
                                selected = selected,
                                onClick = { viewModel.setDirection(dir) },
                                label = { Text(dir.arrow, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = ShiftOrange,
                                    selectedLabelColor = Color.White
                                )
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        "TARGET DISTANCE · ${targetDistanceMiles.toInt()} MI",
                        style = MicroLabelStyle.copy(color = ShiftTextMuted)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    // ORS caps round trips at 100km, so 60mi is the honest ceiling.
                    Slider(
                        value = targetDistanceMiles,
                        onValueChange = { viewModel.setTargetDistance(it) },
                        valueRange = 5f..60f,
                        steps = 10,
                        modifier = Modifier.fillMaxWidth(),
                        colors = SliderDefaults.colors(
                            thumbColor = ShiftOrange,
                            activeTrackColor = ShiftOrange,
                            inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            activeTickColor = ShiftOrange,
                            inactiveTickColor = MaterialTheme.colorScheme.surfaceContainerHighest
                        ),
                        thumb = {
                            Box(
                                modifier = Modifier
                                    .width(3.dp)
                                    .height(28.dp)
                                    .background(ShiftOrange)
                            )
                        }
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    if (generatedRoute != null) {
                        val route = generatedRoute!!

                        // One ride per terrain came back — pick the flavour.
                        terrainRoutes?.let { trio ->
                            if (trio.size > 1) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    trio.forEach { (pref, r) ->
                                        val selected = terrain == pref
                                        val name = when (pref) {
                                            com.example.shift.data.TerrainPreference.FLAT -> "Flat"
                                            com.example.shift.data.TerrainPreference.ROLLING -> "Rolling"
                                            com.example.shift.data.TerrainPreference.HILLY -> "Hilly"
                                        }
                                        FilterChip(
                                            selected = selected,
                                            onClick = { viewModel.selectTerrain(pref) },
                                            label = {
                                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                                    Text(
                                                        name,
                                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                                                    )
                                                    Text(
                                                        "%.0fmi ↗%,.0fft".format(
                                                            r.distanceMeters * 0.000621371,
                                                            r.ascentMeters * 3.28084
                                                        ),
                                                        style = MaterialTheme.typography.labelSmall
                                                    )
                                                }
                                            },
                                            colors = FilterChipDefaults.filterChipColors(
                                                selectedContainerColor = ShiftOrange,
                                                selectedLabelColor = Color.White
                                            )
                                        )
                                    }
                                }
                            }
                        }

                        // Selected ride's numbers, in the monthly summary's dark box —
                        // the same component family as the activity drawer.
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(ShiftDarkSurface, RoundedCornerShape(24.dp))
                                .padding(20.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                StatCard("MILES", "%.1f".format(route.distanceMeters * 0.000621371), Modifier.weight(1f))
                                StatCard("EST MIN", "${route.durationSeconds.toInt() / 60}", Modifier.weight(1f), accent = true)
                                StatCard("ELEV FT", "%,.0f".format(route.ascentMeters * 3.28084), Modifier.weight(1f))
                            }
                        }

                        // Elevation profile of the selected ride — same voice as the
                        // activity drawer's graph: route-blue line on the sheet ground.
                        route.elevationProfile?.takeIf { it.size > 2 }?.let { profile ->
                            Spacer(modifier = Modifier.height(10.dp))
                            val surfaceColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)
                            androidx.compose.foundation.Canvas(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(90.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(surfaceColor)
                            ) {
                                val w = size.width
                                val h = size.height
                                val minE = profile.minOf { it.second }
                                val maxE = profile.maxOf { it.second }
                                val range = if (maxE - minE < 1.0) 1.0 else maxE - minE
                                val maxD = profile.last().first.coerceAtLeast(1.0)

                                val fill = androidx.compose.ui.graphics.Path()
                                fill.moveTo(0f, h)
                                for ((d, e) in profile) {
                                    fill.lineTo(
                                        ((d / maxD) * w).toFloat(),
                                        (h - ((e - minE) / range) * h * 0.85f).toFloat()
                                    )
                                }
                                fill.lineTo(w, h)
                                fill.close()
                                drawPath(
                                    fill,
                                    brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                                        colors = listOf(
                                            RouteLineColor.copy(alpha = 0.5f),
                                            androidx.compose.ui.graphics.Color.Transparent
                                        )
                                    )
                                )
                                val line = androidx.compose.ui.graphics.Path()
                                profile.forEachIndexed { idx, (d, e) ->
                                    val x = ((d / maxD) * w).toFloat()
                                    val y = (h - ((e - minE) / range) * h * 0.85f).toFloat()
                                    if (idx == 0) line.moveTo(x, y) else line.lineTo(x, y)
                                }
                                drawPath(
                                    line,
                                    color = RouteLineColor,
                                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx())
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            if (route.retraceFraction < 0.02) "Clean loop — no doubling back"
                            else "Doubles back for %.0f%% of the route".format(route.retraceFraction * 100),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (route.retraceFraction < 0.02) Color(0xFF3E8E4E)
                                else MaterialTheme.colorScheme.error
                        )
                        route.familiarity?.let { fam ->
                            Text(
                                "%.0f%% on roads you've ridden".format(fam * 100),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Spacer(modifier = Modifier.height(14.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(onClick = { viewModel.generateRoute() }) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = "Regenerate",
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            Button(
                                onClick = { viewModel.exportGpx(context) },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = ShiftOrange,
                                    contentColor = Color.White
                                ),
                                shape = RoundedCornerShape(24.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Share,
                                    contentDescription = "Export GPX",
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    "EXPORT GPX",
                                    style = MaterialTheme.typography.labelLarge.copy(
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 1.sp
                                    )
                                )
                            }
                        }
                    } else {
                        Button(
                            onClick = { viewModel.generateRoute() },
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                            enabled = !isGenerating,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = ShiftOrange,
                                contentColor = Color.White,
                                disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                            ),
                            shape = RoundedCornerShape(24.dp)
                        ) {
                            Text(
                                text = if (isGenerating) "GENERATING..." else "GENERATE ROUTES",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.sp
                                )
                            )
                        }
                    }
                }

                // Clear the floating nav pill when embedded as a tab.
                Spacer(modifier = Modifier.height(96.dp))
            }
        }
    ) { _ ->
        Box(modifier = Modifier.fillMaxSize()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    object : WebView(ctx) {
                        init {
                            setBackgroundColor(0)
                        }
                        override fun onTouchEvent(event: android.view.MotionEvent?): Boolean {
                            requestDisallowInterceptTouchEvent(true)
                            return super.onTouchEvent(event)
                        }
                    }.apply {
                        layoutParams = android.view.ViewGroup.LayoutParams(
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        settings.javaScriptEnabled = true
                        settings.allowFileAccess = true
                        settings.domStorageEnabled = true
                        settings.setGeolocationEnabled(true)
                        webChromeClient = object : android.webkit.WebChromeClient() {
                            override fun onGeolocationPermissionsShowPrompt(
                                origin: String?,
                                callback: android.webkit.GeolocationPermissions.Callback?
                            ) {
                                callback?.invoke(origin, true, false)
                            }
                            override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage?): Boolean {
                                android.util.Log.i("RouteCreatorMap", "${consoleMessage?.message()} -- From line ${consoleMessage?.lineNumber()} of ${consoleMessage?.sourceId()}")
                                return super.onConsoleMessage(consoleMessage)
                            }
                        }
                        webViewClient = WebViewClient()
                        addJavascriptInterface(
                            RouteCreatorWebAppInterface { lat, lng ->
                                viewModel.setStartLocation(lat, lng)
                            },
                            "Android"
                        )
                        loadUrl("file:///android_asset/route_creator_map.html")
                        webViewRef = this
                    }
                }
            )

            // Status / error banner over the map, same voice as the activity map.
            if (statusMessage.isNotEmpty() || errorMessage != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .statusBarsPadding()
                        .padding(16.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (errorMessage != null) MaterialTheme.colorScheme.errorContainer else Color(0xE61B1B19))
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = errorMessage ?: statusMessage,
                        color = if (errorMessage != null) MaterialTheme.colorScheme.onErrorContainer else Color(0xFFF2F2ED),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}
