package com.example.shift.ui

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.shift.data.GpxExporter
import com.example.shift.data.LoopRouteGenerator
import com.example.shift.data.LoopRouteResult
import com.example.shift.data.OpenRouteServiceClient
import com.example.shift.data.SettingsManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class RouteCreatorViewModel(
    private val settingsManager: SettingsManager
) : ViewModel() {

    private val orsClient = OpenRouteServiceClient()
    private val loopGenerator = LoopRouteGenerator(orsClient)

    // Personal heatmap, built off the rides list's summary polylines. Rebuilt
    // only when the ride count changes — building is O(total track length).
    private var heatmap: com.example.shift.data.RideHeatmap? = null
    private var heatmapRideCount = -1

    fun setRideHistory(encodedPolylines: List<String>) {
        if (encodedPolylines.size == heatmapRideCount) return
        heatmapRideCount = encodedPolylines.size
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            heatmap = com.example.shift.data.RideHeatmap.build(encodedPolylines)
        }
    }

    private val _startLat = MutableStateFlow<Double?>(null)
    val startLat: StateFlow<Double?> = _startLat

    private val _startLng = MutableStateFlow<Double?>(null)
    val startLng: StateFlow<Double?> = _startLng

    private val _targetDistanceMiles = MutableStateFlow(20f)
    val targetDistanceMiles: StateFlow<Float> = _targetDistanceMiles

    private val _terrain = MutableStateFlow(com.example.shift.data.TerrainPreference.ROLLING)
    val terrain: StateFlow<com.example.shift.data.TerrainPreference> = _terrain

    // One generation now produces a route per terrain; the rider picks after.
    private val _terrainRoutes =
        MutableStateFlow<Map<com.example.shift.data.TerrainPreference, LoopRouteResult>?>(null)
    val terrainRoutes: StateFlow<Map<com.example.shift.data.TerrainPreference, LoopRouteResult>?> = _terrainRoutes

    private val _generatedRoute = MutableStateFlow<LoopRouteResult?>(null)
    val generatedRoute: StateFlow<LoopRouteResult?> = _generatedRoute

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating

    private val _statusMessage = MutableStateFlow("")
    val statusMessage: StateFlow<String> = _statusMessage

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage

    fun setStartLocation(lat: Double, lng: Double) {
        _startLat.value = lat
        _startLng.value = lng
        _generatedRoute.value = null
        _terrainRoutes.value = null
        _errorMessage.value = null
    }

    fun setTargetDistance(miles: Float) {
        _targetDistanceMiles.value = miles
    }

    // Bumped whenever the inputs change; an in-flight generation whose token no
    // longer matches must not publish its result — it answers a stale question.
    private var generationToken = 0

    /** Post-generation selection: swap the displayed route between the trio. */
    fun selectTerrain(pref: com.example.shift.data.TerrainPreference) {
        val routes = _terrainRoutes.value ?: return
        val route = routes[pref] ?: return
        _terrain.value = pref
        _generatedRoute.value = route
    }

    fun generateRoute() {
        val lat = _startLat.value ?: return
        val lng = _startLng.value ?: return

        val token = ++generationToken
        viewModelScope.launch {
            _isGenerating.value = true
            _errorMessage.value = null
            _statusMessage.value = "Generating routes..."
            _generatedRoute.value = null
            _terrainRoutes.value = null

            // Settings key wins; otherwise the baked-in owner key — the same
            // two-tier pattern the Gemini key uses.
            val savedKey = settingsManager.orsApiKeyFlow.first()
            val apiKey = if (savedKey.isNullOrBlank()) com.example.shift.BuildConfig.ORS_API_KEY else savedKey
            if (apiKey.isBlank()) {
                _errorMessage.value = "Add your OpenRouteService API key in Settings (free at openrouteservice.org)"
                _isGenerating.value = false
                _statusMessage.value = ""
                return@launch
            }

            val distanceMeters = _targetDistanceMiles.value * 1609.34

            try {
                // One ride per terrain, sequentially — three parallel
                // tournaments would brush the API's per-minute limit.
                val labels = mapOf(
                    com.example.shift.data.TerrainPreference.FLAT to "Flat",
                    com.example.shift.data.TerrainPreference.ROLLING to "Rolling",
                    com.example.shift.data.TerrainPreference.HILLY to "Hilly"
                )
                val routes = LinkedHashMap<com.example.shift.data.TerrainPreference, LoopRouteResult>()
                for (pref in com.example.shift.data.TerrainPreference.entries) {
                    if (token != generationToken) break
                    val label = labels[pref] ?: pref.name
                    val result = loopGenerator.generateLoopRoute(
                        apiKey = apiKey,
                        startLat = lat,
                        startLng = lng,
                        targetDistanceMeters = distanceMeters.toDouble(),
                        terrain = pref,
                        heatmap = heatmap,
                        quick = true,
                        onStatusUpdate = { status -> _statusMessage.value = "$label: $status" }
                    )
                    if (result != null) routes[pref] = result
                }

                if (token == generationToken) {
                    if (routes.isNotEmpty()) {
                        _terrainRoutes.value = routes
                        // Preselect the last chosen terrain when it exists,
                        // else the first that generated.
                        val pick = routes[_terrain.value] ?: routes.entries.first().value
                        _terrain.value = routes.entries.first { it.value === pick }.key
                        _generatedRoute.value = pick
                        _statusMessage.value = ""
                    } else {
                        _errorMessage.value = "Could not generate a route. Try a different start location."
                        _statusMessage.value = ""
                    }
                } else {
                    _statusMessage.value = ""
                }
            } catch (e: Exception) {
                if (token == generationToken) {
                    _errorMessage.value = e.message ?: "Route generation failed"
                }
                _statusMessage.value = ""
            }

            _isGenerating.value = false
        }
    }

    fun exportGpx(context: Context) {
        val route = _generatedRoute.value ?: return
        val distMiles = route.distanceMeters * 0.000621371
        val name = "Shift_Route_%.0fmi".format(distMiles)
        val gpxContent = GpxExporter.generateGpx(name, route.coordinates)
        val uri = GpxExporter.saveGpxToFile(context, name, gpxContent)
        if (uri != null) {
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/gpx+xml"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(shareIntent, "Export GPX").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
