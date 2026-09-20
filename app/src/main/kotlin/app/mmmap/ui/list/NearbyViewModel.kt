package app.mmmap.ui.list

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.mmmap.data.repository.RestaurantRepository
import app.mmmap.domain.model.Restaurant
import app.mmmap.util.haversineKm
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.cos

// Near Me lists what is within roughly this far, sorted by true distance.
private const val NEARBY_RADIUS_KM = 55.7
private const val KM_PER_DEGREE_LAT = 111.32

// cos(latitude) floors out near the poles, where a degree of longitude approaches zero
// and the box would need to span every meridian. 1/cos(89.5°) already exceeds a half
// turn, so the clamp below does the right thing without dividing by ~0.
private const val MAX_LON_DELTA = 180.0

@HiltViewModel
class NearbyViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repo: RestaurantRepository,
) : ViewModel() {

    private val _nearby = MutableStateFlow<List<Pair<Restaurant, Float>>>(emptyList())
    val nearby: StateFlow<List<Pair<Restaurant, Float>>> = _nearby

    private val _locationMissing = MutableStateFlow(false)
    val locationMissing: StateFlow<Boolean> = _locationMissing

    private var loadJob: Job? = null

    fun load() {
        // The screen's LaunchedEffect(Unit) re-runs on every re-entry, and the nav graph
        // keeps this ViewModel alive across tab switches — so without cancelling the
        // previous job each visit would leave another Room collector running forever.
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val location = bestLastLocation() ?: run { _locationMissing.value = true; return@launch }
            _locationMissing.value = false
            val lat = location.latitude
            val lon = location.longitude
            // A degree of latitude is ~111km everywhere, but a degree of longitude is that
            // times cos(latitude): 55.7km at the equator, 34.6km in London, 24.3km in
            // Reykjavik. A box of ±0.5° on both axes is therefore not a radius at all — it
            // narrows the further north you are, while this list sorts by true haversine
            // distance. Restaurants 40km due east were dropped while ones 50km due north
            // were kept, and "nearest" quietly meant "nearest, unless it is east of you".
            val latDelta = NEARBY_RADIUS_KM / KM_PER_DEGREE_LAT
            val lonDelta = (latDelta / cos(Math.toRadians(lat))).coerceIn(latDelta, MAX_LON_DELTA)
            repo.observeInBounds(
                minLat = (lat - latDelta).coerceAtLeast(-90.0),
                maxLat = (lat + latDelta).coerceAtMost(90.0),
                minLon = (lon - lonDelta).coerceAtLeast(-180.0),
                maxLon = (lon + lonDelta).coerceAtMost(180.0),
            ).collect { restaurants ->
                _nearby.value = restaurants
                    .map { it to haversineKm(lat, lon, it.latitude, it.longitude) }
                    .sortedBy { it.second }
                    .take(50)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun bestLastLocation(): Location? {
        val hasFine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!hasFine && !hasCoarse) return null
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
    }

}
