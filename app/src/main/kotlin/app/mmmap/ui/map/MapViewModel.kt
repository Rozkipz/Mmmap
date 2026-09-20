package app.mmmap.ui.map

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import app.mmmap.data.db.entities.VisitedRestaurantEntity
import app.mmmap.data.repository.RestaurantRepository
import app.mmmap.data.repository.VisitedRepository
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import app.mmmap.data.sync.DatasetSyncWorker
import app.mmmap.data.sync.SyncPreferences
import app.mmmap.domain.model.Distinction
import app.mmmap.domain.model.Restaurant
import app.mmmap.map.TileCacheManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlin.math.abs

private const val LOCATE_TIMEOUT_MS = 60_000L

// Restaurants are drawn as circles — 9-14 by award, 26 for the visited glow — so one whose
// centre sits just outside the viewport still owes the edge of the screen a sliver of ink.
// Querying the exact visible region drops those rows, and the circles are simply missing
// along all four edges until you pan far enough to bring the centre into view.
//
// Those radii are style units, which MapLibre scales by pixelRatio (MapView.getPixelRatio
// returns displayMetrics.density), so they are dp — NOT physical pixels. The margin has to
// clear 26dp on the narrowest window the app can be given, which is Android's 220dp
// split-screen minimum: 26/220 = 11.8%. A tenth would be 22dp there and clip the glow all
// over again. Fifteen percent leaves 33dp at that width and 54dp on a normal phone, and
// also means a short pan lands on restaurants that are already loaded.
private const val VIEWPORT_QUERY_MARGIN = 0.15
private val PrettyJson = Json { prettyPrint = true }

data class MapBounds(
    val minLat: Double, val maxLat: Double,
    val minLon: Double, val maxLon: Double,
) {
    /**
     * This box grown by [fraction] of its own span on all four sides, clamped to the
     * valid coordinate range.
     *
     * A fraction of the span, rather than a fixed number of degrees, is what makes the
     * margin a constant slice of the screen at every zoom level: the span a viewport
     * covers is proportional to its pixel size over 2^zoom, so a tenth of the span is a
     * tenth of the screen whether you are looking at a street or a continent.
     */
    fun padded(fraction: Double): MapBounds {
        // abs, so a box that arrives inverted — a west > east pair across the antimeridian,
        // say — is never quietly shrunk instead of grown.
        val latMargin = abs(maxLat - minLat) * fraction
        val lonMargin = abs(maxLon - minLon) * fraction
        return MapBounds(
            minLat = (minLat - latMargin).coerceAtLeast(-90.0),
            maxLat = (maxLat + latMargin).coerceAtMost(90.0),
            minLon = (minLon - lonMargin).coerceAtLeast(-180.0),
            maxLon = (maxLon + lonMargin).coerceAtMost(180.0),
        )
    }
}

enum class VisitedFilter { VISITED_ONLY, UNVISITED_ONLY }

data class MapFilters(
    val distinctions: Set<Distinction>? = null,
    val cuisines: Set<String>? = null,
    val priceTiers: Set<Int>? = null,
    val visitedFilter: VisitedFilter? = null,
)

data class DebugState(
    val dbRestaurantCount: Int,
    val viewportCount: Int,
    val lastSyncAt: Long?,
    val lastCsvSha: String?,
    val workerState: String,
    val nextSyncAt: Long?,
    val bounds: MapBounds?,
    val filters: MapFilters,
)

@HiltViewModel
class MapViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val repo: RestaurantRepository,
    private val syncPrefs: SyncPreferences,
    private val tileCacheManager: TileCacheManager,
    private val workManager: WorkManager,
    private val visitedRepo: VisitedRepository,
) : ViewModel() {

    val bounds = MutableStateFlow<MapBounds?>(null)
    val filters = MutableStateFlow(MapFilters())

    val availableCuisines = MutableStateFlow<List<String>>(emptyList())
    val availablePrices = MutableStateFlow<List<String>>(emptyList())

    val visitedRestaurantIds: StateFlow<Set<String>> = visitedRepo.visitedIds
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    @OptIn(ExperimentalCoroutinesApi::class)
    val restaurants: StateFlow<List<Restaurant>> = combine(bounds, filters) { b, f -> b to f }
        .flatMapLatest { (b, f) ->
            if (b == null) return@flatMapLatest kotlinx.coroutines.flow.flowOf(emptyList<Restaurant>())
            val q = b.padded(VIEWPORT_QUERY_MARGIN)
            combine(
                repo.observeInBounds(
                    minLat = q.minLat, maxLat = q.maxLat,
                    minLon = q.minLon, maxLon = q.maxLon,
                    distinctions = f.distinctions,
                    cuisines = f.cuisines,
                    priceTiers = f.priceTiers,
                ),
                visitedRepo.visitedIds,
            ) { list: List<Restaurant>, visitedSet: Set<String> ->
                when (f.visitedFilter) {
                    VisitedFilter.VISITED_ONLY   -> list.filter { it.id in visitedSet }
                    VisitedFilter.UNVISITED_ONLY -> list.filter { it.id !in visitedSet }
                    null                         -> list
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val selectedRestaurant = MutableStateFlow<Restaurant?>(null)

    val cacheSizeMb: StateFlow<Long> = tileCacheManager.maxSizeMb
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 100L)

    fun setCacheSizeMb(mb: Long) {
        // MapLibre's OfflineManager reports failures by resuming the continuation with an
        // exception. Nothing catches it, so a corrupt or full ambient cache would otherwise
        // take the whole process down from a settings tap.
        viewModelScope.launch { runCatching { tileCacheManager.setMaxSizeMb(mb) } }
    }

    fun clearTileCache() {
        viewModelScope.launch { runCatching { tileCacheManager.clearAmbientCache() } }
    }

    val debugState = MutableStateFlow<DebugState?>(null)

    fun loadDebugInfo() {
        viewModelScope.launch {
            // getWorkInfosForUniqueWork returns a ListenableFuture; .get() would block,
            // and viewModelScope is Main.immediate — an ANR waiting to happen. The Flow
            // variant suspends instead.
            val workInfo = workManager
                .getWorkInfosForUniqueWorkFlow(DatasetSyncWorker.TAG)
                .first()
                .firstOrNull()
            debugState.value = DebugState(
                dbRestaurantCount = repo.count(),
                viewportCount     = restaurants.value.size,
                lastSyncAt        = syncPrefs.lastSyncAt(),
                lastCsvSha        = syncPrefs.lastCsvSha()?.take(8),
                workerState       = workInfo?.state?.name ?: "none",
                nextSyncAt        = workInfo?.nextScheduleTimeMillis?.takeIf { it != Long.MAX_VALUE },
                bounds            = bounds.value,
                filters           = filters.value,
            )
        }
    }

    private val _importExportMessage = MutableStateFlow<String?>(null)
    val importExportMessage: StateFlow<String?> = _importExportMessage

    fun exportVisited(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val json = PrettyJson.encodeToString(visitedRepo.getAll())
                // "wt" truncates: plain "w" leaves trailing bytes from a longer previous
                // export, producing invalid JSON that import then rejects. A null stream is
                // a real failure, not something to report success for.
                val stream = context.contentResolver.openOutputStream(uri, "wt")
                    ?: error("Could not open $uri for writing")
                stream.use { it.write(json.toByteArray()) }
                _importExportMessage.value = "Exported ${visitedRepo.count()} places"
            }.onFailure {
                _importExportMessage.value = "Export failed"
            }
        }
    }

    fun importVisited(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val json = context.contentResolver.openInputStream(uri)
                    ?.use { it.readBytes().toString(Charsets.UTF_8) }
                    ?: return@launch
                val entities = Json.decodeFromString<List<VisitedRestaurantEntity>>(json)
                visitedRepo.importAll(entities)
                _importExportMessage.value = "Imported ${entities.size} places"
            }.onFailure {
                _importExportMessage.value = "Import failed — check the file format"
            }
        }
    }

    fun clearImportExportMessage() { _importExportMessage.value = null }

    fun forceRefresh() {
        // Deliberately NOT on Dispatchers.IO: neither call blocks — clearSha() is a
        // DataStore suspend that dispatches internally and enqueueUniquePeriodicWork is
        // async — while a real IO thread races advanceUntilIdle() in tests, making
        // MapViewModelTest.forceRefresh_clearsShaAndEnqueuesWork fail nondeterministically.
        viewModelScope.launch {
            syncPrefs.clearSha()
            val request = PeriodicWorkRequestBuilder<DatasetSyncWorker>(1, TimeUnit.DAYS)
                .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED))
                .build()
            workManager.enqueueUniquePeriodicWork(
                DatasetSyncWorker.TAG,
                ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE,
                request,
            )
        }
    }

    private val _userLatLon = MutableStateFlow<Pair<Double, Double>?>(null)
    val userLatLon: StateFlow<Pair<Double, Double>?> = _userLatLon

    private val _isLocating = MutableStateFlow(false)
    val isLocating: StateFlow<Boolean> = _isLocating

    private var pendingLocationListener: LocationListener? = null
    private var pendingCancellationSignal: CancellationSignal? = null
    private var locateTimeoutJob: Job? = null
    private val locationManager by lazy {
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    }

    init {
        viewModelScope.launch {
            availableCuisines.value = repo.distinctCuisines()
            availablePrices.value = repo.distinctPrices()
        }
    }

    // lat, lon, zoom — persists across tab switches so the map can restore its position
    var lastCameraPosition: Triple<Double, Double, Double>? = null
        private set

    // True after the first automatic zoom to GPS; prevents re-zooming on tab switch
    var hasZoomedToUserOnce = false

    // Set by Nearby→Map navigation; consumed on next map init to animate the camera
    var pendingFocusLatLon: Pair<Double, Double>? = null

    fun saveLastCamera(lat: Double, lon: Double, zoom: Double) {
        lastCameraPosition = Triple(lat, lon, zoom)
    }

    fun selectRestaurant(restaurant: Restaurant?) { selectedRestaurant.value = restaurant }
    fun updateBounds(b: MapBounds) { bounds.value = b }
    fun updateFilters(f: MapFilters) { filters.value = f }

    @SuppressLint("MissingPermission")
    fun locateUser() {
        val hasFine = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasFine && !hasCoarse) return

        val last = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .mapNotNull { runCatching { locationManager.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
        if (last != null) _userLatLon.value = last.latitude to last.longitude

        pendingLocationListener?.let { locationManager.removeUpdates(it) }
        pendingCancellationSignal?.cancel()
        pendingCancellationSignal = null

        val enabledProviders = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { runCatching { locationManager.isProviderEnabled(it) }.getOrElse { false } }
        if (enabledProviders.isEmpty()) return

        // Only show spinner when we have no position yet; a cached fix is enough to hide it
        if (last == null) _isLocating.value = true
        locateTimeoutJob?.cancel()
        locateTimeoutJob = viewModelScope.launch {
            delay(LOCATE_TIMEOUT_MS)
            _isLocating.value = false
            pendingCancellationSignal?.cancel()
            pendingCancellationSignal = null
            pendingLocationListener?.let { locationManager.removeUpdates(it) }
            pendingLocationListener = null
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val provider = if (hasFine && LocationManager.GPS_PROVIDER in enabledProviders)
                LocationManager.GPS_PROVIDER
            else
                enabledProviders.first()
            val signal = CancellationSignal()
            pendingCancellationSignal = signal
            locationManager.getCurrentLocation(provider, signal, context.mainExecutor) { loc ->
                locateTimeoutJob?.cancel()
                _isLocating.value = false
                pendingCancellationSignal = null
                if (loc != null) _userLatLon.value = loc.latitude to loc.longitude
            }
        } else {
            var fired = false
            val listener = object : LocationListener {
                override fun onLocationChanged(loc: Location) {
                    if (fired) return
                    fired = true
                    locateTimeoutJob?.cancel()
                    _isLocating.value = false
                    _userLatLon.value = loc.latitude to loc.longitude
                    locationManager.removeUpdates(this)
                    pendingLocationListener = null
                }
                override fun onProviderDisabled(p: String) {}
                override fun onProviderEnabled(p: String) {}
                // Deprecated, and defaulted from API 29 — but still abstract on API 26-28,
                // where the framework calls it and an unimplemented one throws
                // AbstractMethodError, killing the app the moment a fix arrives.
                @Deprecated("Deprecated in API 29; required on API 26-28")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            }
            pendingLocationListener = listener
            enabledProviders.forEach { provider ->
                runCatching {
                    locationManager.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        locateTimeoutJob?.cancel()
        pendingCancellationSignal?.cancel()
        pendingLocationListener?.let { locationManager.removeUpdates(it) }
    }
}
