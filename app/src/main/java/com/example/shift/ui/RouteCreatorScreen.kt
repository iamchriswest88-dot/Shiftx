package com.example.shift.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class RouteCreatorWebAppInterface(private val onLocationSelected: (Double, Double) -> Unit) {
    @JavascriptInterface
    fun onStartLocationSet(lat: Double, lng: Double) {
        onLocationSelected(lat, lng)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RouteCreatorScreen(viewModel: RouteCreatorViewModel, onBack: () -> Unit = {}) {
    val startLat by viewModel.startLat.collectAsState()
    val startLng by viewModel.startLng.collectAsState()
    val targetDistanceMiles by viewModel.targetDistanceMiles.collectAsState()
    val terrain by viewModel.terrain.collectAsState()
    val terrainRoutes by viewModel.terrainRoutes.collectAsState()
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
            if (generatedRoute!!.hadSpurWarning && generatedRoute!!.spurCoordinates != null) {
                val spurJson = generatedRoute!!.spurCoordinates!!.map { "[${it.first},${it.second}]" }.joinToString(",", "[", "]")
                webViewRef?.evaluateJavascript("highlightSpur('$spurJson');", null)
            }
        } else {
            webViewRef?.evaluateJavascript("clearRoute();", null)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxSize()
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 24.dp, top = 16.dp, bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Route Planner",
                    style = MaterialTheme.typography.displayMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            // Map
            Box(modifier = Modifier
                .fillMaxWidth()
                .height(380.dp)) {
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

                // Status overlay
                if (statusMessage.isNotEmpty() || errorMessage != null) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(16.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (errorMessage != null) MaterialTheme.colorScheme.errorContainer else Color(0xCC252B2E))
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = errorMessage ?: statusMessage,
                            color = if (errorMessage != null) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }

            // Controls
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(24.dp)
            ) {
                if (startLat == null) {
                    Text(
                        "Tap the map to set a start location",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )
                } else {
                    Text(
                        "Start point set. Tap or drag the marker to adjust.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )
                    Text(
                        "Target Distance: ${targetDistanceMiles.toInt()} mi",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    // ORS caps round trips at 100km, so 60mi is the honest ceiling —
                    // the old 100mi top could never generate.
                    Slider(
                        value = targetDistanceMiles,
                        onValueChange = { viewModel.setTargetDistance(it) },
                        valueRange = 5f..60f,
                        steps = 10,
                        modifier = Modifier.fillMaxWidth(),
                        colors = SliderDefaults.colors(
                            thumbColor = Color(0xFFFFC700),
                            activeTrackColor = Color(0xFFFFC700),
                            inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            activeTickColor = Color(0xFFFFC700),
                            inactiveTickColor = Color(0xFFFFC700)
                        ),
                        thumb = {
                            Box(
                                modifier = Modifier
                                    .width(3.dp)
                                    .height(28.dp)
                                    .background(Color(0xFFFFC700))
                            )
                        }
                    )

                    Spacer(modifier = Modifier.height(20.dp))

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
                                                selectedContainerColor = Color(0xFFFFC700),
                                                selectedLabelColor = Color(0xFF0F1417)
                                            )
                                        )
                                    }
                                }
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    "Actual: %.1f mi".format(route.distanceMeters * 0.000621371),
                                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    "Est. time: ${route.durationSeconds.toInt() / 60} min · ↗ %,.0f ft".format(route.ascentMeters * 3.28084),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                // The tournament's own verdict on retracing —
                                // under 2% is a clean loop.
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
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
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
                                        containerColor = Color(0xFFFFC700),
                                        contentColor = Color(0xFF0F1417)
                                    ),
                                    shape = RoundedCornerShape(24.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Share,
                                        contentDescription = "Export GPX",
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        "Export",
                                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                                    )
                                }
                            }
                        }
                    } else {
                        Button(
                            onClick = { viewModel.generateRoute() },
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                            enabled = !isGenerating,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isGenerating) MaterialTheme.colorScheme.surfaceContainerHigh else Color(0xFFFFC700),
                                contentColor = if (isGenerating) MaterialTheme.colorScheme.onSurfaceVariant else Color(0xFF0F1417),
                                disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                            ),
                            shape = RoundedCornerShape(24.dp)
                        ) {
                            Text(
                                text = if (isGenerating) "Generating..." else "Generate Routes",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                            )
                        }
                    }
                }
            }
        }
    }
}
