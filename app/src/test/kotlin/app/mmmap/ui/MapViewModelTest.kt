package app.mmmap.ui

import android.content.Context
import android.location.LocationManager
import app.mmmap.data.prefs.MapCachePreferences
import app.mmmap.data.repository.RestaurantRepository
import app.mmmap.data.repository.VisitedRepository
import app.mmmap.data.sync.SyncPreferences
import app.mmmap.map.TileCacheManager
import app.mmmap.domain.model.Distinction
import app.mmmap.domain.model.Restaurant
import app.mmmap.ui.map.DebugState
import app.mmmap.ui.map.MapBounds
import app.mmmap.ui.map.MapFilters
import app.mmmap.ui.map.MapViewModel
import app.mmmap.ui.map.VisitedFilter
import androidx.work.WorkManager
import app.mmmap.data.sync.DatasetSyncWorker
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MapViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val repo: RestaurantRepository = mockk(relaxed = true)
    private val context: Context = mockk(relaxed = true)
    private val syncPrefs: SyncPreferences = mockk(relaxed = true)
    private val tileCacheManager: TileCacheManager = mockk(relaxed = true)
    private val workManager: WorkManager = mockk(relaxed = true)
    private val visitedRepo: VisitedRepository = mockk(relaxed = true) {
        every { visitedIds } returns flowOf(emptySet())
    }
    private lateinit var vm: MapViewModel

    @Before fun setUp() {
        Dispatchers.setMain(testDispatcher)
        every { context.getSystemService(Context.LOCATION_SERVICE) } returns mockk<LocationManager>(relaxed = true)
        // WorkManager.getInstance calls context.getApplicationContext() during mockk recording;
        // the relaxed mock doesn't implement this abstract method, so we stub it explicitly.
        every { context.applicationContext } returns context
        coEvery { repo.distinctCuisines() } returns listOf("French", "Japanese")
        coEvery { repo.distinctPrices() } returns listOf("£", "££", "£££")
        every { repo.observeInBounds(any(), any(), any(), any(), any(), any(), any()) } returns flowOf(emptyList())
        every { tileCacheManager.maxSizeMb } returns flowOf(MapCachePreferences.DEFAULT_CACHE_MB)
        vm = MapViewModel(context, repo, syncPrefs, tileCacheManager, workManager, visitedRepo)
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test fun initialRestaurantsEmpty() {
        assertTrue(vm.restaurants.value.isEmpty())
    }

    @Test fun initialBoundsNull() {
        assertNull(vm.bounds.value)
    }

    @Test fun initialFiltersAllNull() {
        val f = vm.filters.value
        assertNull(f.distinctions)
        assertNull(f.cuisines)
        assertNull(f.priceTiers)
        assertNull(f.visitedFilter)
    }

    @Test fun availableCuisinesPopulatedOnInit() = runTest {
        advanceUntilIdle()
        assertEquals(listOf("French", "Japanese"), vm.availableCuisines.value)
    }

    @Test fun availablePricesPopulatedOnInit() = runTest {
        advanceUntilIdle()
        assertEquals(listOf("£", "££", "£££"), vm.availablePrices.value)
    }

    @Test fun updateBoundsSetsValue() {
        val bounds = MapBounds(1.0, 2.0, 3.0, 4.0)
        vm.updateBounds(bounds)
        assertEquals(bounds, vm.bounds.value)
    }

    @Test fun queryBoxClearsTheCameraByAtLeastTheWidestCircle() = runTest {
        // The requirement, not the constant. The widest mark drawn is the visited glow at
        // radius 26 (MapScreen.addCustomLayers) — style units, which MapLibre scales by
        // pixelRatio, so dp rather than physical pixels. The box must clear that on the
        // narrowest window the app can be given: Android's 220dp split-screen minimum.
        // Retuning VIEWPORT_QUERY_MARGIN upward must leave this green; dropping it back to
        // a tenth must not, because a tenth of 220dp is 22dp and clips the glow.
        val narrowestWindowDp = 220.0
        val widestMarkDp = 26.0
        val requiredFraction = widestMarkDp / narrowestWindowDp
        val minLat = slot<Double>()
        val maxLat = slot<Double>()
        val minLon = slot<Double>()
        val maxLon = slot<Double>()
        every {
            repo.observeInBounds(
                capture(minLat), capture(maxLat), capture(minLon), capture(maxLon),
                any(), any(), any(),
            )
        } returns flowOf(emptyList())

        val job = vm.restaurants.launchIn(this)
        vm.updateBounds(MapBounds(minLat = 50.0, maxLat = 52.0, minLon = -1.0, maxLon = 1.0))
        advanceUntilIdle()

        val south = 50.0 - minLat.captured
        val north = maxLat.captured - 52.0
        val west = -1.0 - minLon.captured
        val east = maxLon.captured - 1.0
        assertTrue("south margin $south of a 2.0 span", south / 2.0 >= requiredFraction)
        assertTrue("north margin $north of a 2.0 span", north / 2.0 >= requiredFraction)
        assertTrue("west margin $west of a 2.0 span", west / 2.0 >= requiredFraction)
        assertTrue("east margin $east of a 2.0 span", east / 2.0 >= requiredFraction)
        assertEquals("a lopsided box would clip one edge", south, north, 1e-9)
        assertEquals("a lopsided box would clip one edge", west, east, 1e-9)
        job.cancel()
    }

    @Test fun paddingIsAFractionOfSpanSoItIsConstantInPixels() {
        // The name's claim is about two zoom levels, so the test has to compare two. A
        // padded() that added a fixed number of degrees would satisfy either box alone.
        fun marginRatio(b: MapBounds) = (b.padded(0.1).maxLat - b.maxLat) / (b.maxLat - b.minLat)

        val street = MapBounds(minLat = 51.5000, maxLat = 51.5020, minLon = -0.1290, maxLon = -0.1270)
        val continent = MapBounds(minLat = 35.0, maxLat = 55.0, minLon = -10.0, maxLon = 10.0)

        // Spans differ by 10 000x; the margin stays the same slice of the screen...
        assertEquals(marginRatio(street), marginRatio(continent), 1e-9)
        // ...which means far fewer degrees when zoomed in.
        assertTrue(
            street.padded(0.1).maxLat - street.maxLat <
                continent.padded(0.1).maxLat - continent.maxLat
        )
    }

    @Test fun paddingLeavesABoxThatNeedsNoClampingAlone() {
        val padded = MapBounds(minLat = 50.0, maxLat = 52.0, minLon = -1.0, maxLon = 1.0).padded(0.1)
        assertTrue(padded.minLat > -90.0 && padded.maxLat < 90.0)
        assertTrue(padded.minLon > -180.0 && padded.maxLon < 180.0)
    }

    @Test fun paddingGrowsAnInvertedBoxRatherThanShrinkingIt() {
        // padded() uses abs specifically so a west > east pair across the antimeridian is
        // not quietly narrowed. Nothing pinned that.
        val inverted = MapBounds(minLat = 50.0, maxLat = 52.0, minLon = 179.0, maxLon = -179.0)
        val padded = inverted.padded(0.1)
        assertTrue("west edge moved the wrong way", padded.minLon < 179.0)
        assertTrue("east edge moved the wrong way", padded.maxLon > -179.0)
    }

    @Test fun paddingNeverLeavesTheValidCoordinateRange() {
        val world = MapBounds(minLat = -85.0, maxLat = 85.0, minLon = -175.0, maxLon = 175.0)
            .padded(0.5)
        assertEquals(-90.0, world.minLat, 0.0)
        assertEquals(90.0, world.maxLat, 0.0)
        assertEquals(-180.0, world.minLon, 0.0)
        assertEquals(180.0, world.maxLon, 0.0)
    }

    @Test fun updateFiltersSetsValue() {
        val filters = MapFilters(distinctions = setOf(Distinction.THREE_STAR), cuisines = setOf("French"))
        vm.updateFilters(filters)
        assertEquals(filters, vm.filters.value)
    }

    @Test fun selectRestaurantUpdatesSelection() {
        val r = restaurant("r1", "Noma")
        vm.selectRestaurant(r)
        assertEquals(r, vm.selectedRestaurant.value)
    }

    @Test fun selectRestaurantNullClearsSelection() {
        vm.selectRestaurant(restaurant("r1", "Noma"))
        vm.selectRestaurant(null)
        assertNull(vm.selectedRestaurant.value)
    }

    @Test fun restaurantsFlowEmitsFromRepo() = runTest {
        val list = listOf(restaurant("r1", "Le Gavroche"), restaurant("r2", "The Ritz"))
        every { repo.observeInBounds(any(), any(), any(), any(), any(), any(), any()) } returns flowOf(list)

        val emissions = mutableListOf<List<Restaurant>>()
        val job = vm.restaurants.onEach { emissions.add(it) }.launchIn(this)

        vm.updateBounds(MapBounds(50.0, 52.0, -1.0, 1.0))
        advanceUntilIdle()

        assertTrue("expected list emission, got $emissions", emissions.any { it == list })
        job.cancel()
    }

    @Test fun nullBoundsKeepsEmptyList() = runTest {
        val emissions = mutableListOf<List<Restaurant>>()
        val job = vm.restaurants.onEach { emissions.add(it) }.launchIn(this)
        advanceUntilIdle()

        assertTrue(emissions.all { it.isEmpty() })
        job.cancel()
    }

    @Test fun filterDistinctionPassedToRepo() = runTest {
        val job = vm.restaurants.launchIn(this)

        vm.updateBounds(MapBounds(50.0, 52.0, -1.0, 1.0))
        vm.updateFilters(MapFilters(distinctions = setOf(Distinction.ONE_STAR)))
        advanceUntilIdle()

        verify { repo.observeInBounds(any(), any(), any(), any(), setOf(Distinction.ONE_STAR), null, null) }
        job.cancel()
    }

    @Test fun filterCuisinePassedToRepo() = runTest {
        val job = vm.restaurants.launchIn(this)

        vm.updateBounds(MapBounds(50.0, 52.0, -1.0, 1.0))
        vm.updateFilters(MapFilters(cuisines = setOf("French")))
        advanceUntilIdle()

        verify { repo.observeInBounds(any(), any(), any(), any(), null, setOf("French"), null) }
        job.cancel()
    }

    @Test fun filterPriceTiersPassedToRepo() = runTest {
        val job = vm.restaurants.launchIn(this)

        vm.updateBounds(MapBounds(50.0, 52.0, -1.0, 1.0))
        vm.updateFilters(MapFilters(priceTiers = setOf(2, 3)))
        advanceUntilIdle()

        verify { repo.observeInBounds(any(), any(), any(), any(), null, null, setOf(2, 3)) }
        job.cancel()
    }

    @Test fun visitedFilterOnly_keepsVisitedRestaurants() = runTest {
        val r1 = restaurant("r1", "Visited Place")
        val r2 = restaurant("r2", "Unvisited Place")
        every { repo.observeInBounds(any(), any(), any(), any(), any(), any(), any()) } returns flowOf(listOf(r1, r2))
        every { visitedRepo.visitedIds } returns flowOf(setOf("r1"))

        val freshVm = MapViewModel(context, repo, syncPrefs, tileCacheManager, workManager, visitedRepo)
        val emissions = mutableListOf<List<Restaurant>>()
        val job = freshVm.restaurants.onEach { emissions.add(it) }.launchIn(this)

        freshVm.updateBounds(MapBounds(50.0, 52.0, -1.0, 1.0))
        freshVm.updateFilters(MapFilters(visitedFilter = VisitedFilter.VISITED_ONLY))
        advanceUntilIdle()

        assertTrue("expected only visited restaurant", emissions.any { it.size == 1 && it[0].id == "r1" })
        job.cancel()
    }

    @Test fun unvisitedFilterOnly_keepsUnvisitedRestaurants() = runTest {
        val r1 = restaurant("r1", "Visited Place")
        val r2 = restaurant("r2", "Unvisited Place")
        every { repo.observeInBounds(any(), any(), any(), any(), any(), any(), any()) } returns flowOf(listOf(r1, r2))
        every { visitedRepo.visitedIds } returns flowOf(setOf("r1"))

        val freshVm = MapViewModel(context, repo, syncPrefs, tileCacheManager, workManager, visitedRepo)
        val emissions = mutableListOf<List<Restaurant>>()
        val job = freshVm.restaurants.onEach { emissions.add(it) }.launchIn(this)

        freshVm.updateBounds(MapBounds(50.0, 52.0, -1.0, 1.0))
        freshVm.updateFilters(MapFilters(visitedFilter = VisitedFilter.UNVISITED_ONLY))
        advanceUntilIdle()

        assertTrue("expected only unvisited restaurant", emissions.any { it.size == 1 && it[0].id == "r2" })
        job.cancel()
    }

    @Test fun visitedFilter_reactsToVisitedIdsChanging() = runTest {
        val r1 = restaurant("r1", "Place A")
        val r2 = restaurant("r2", "Place B")
        every { repo.observeInBounds(any(), any(), any(), any(), any(), any(), any()) } returns flowOf(listOf(r1, r2))

        val visitedFlow = MutableStateFlow(emptySet<String>())
        every { visitedRepo.visitedIds } returns visitedFlow

        val freshVm = MapViewModel(context, repo, syncPrefs, tileCacheManager, workManager, visitedRepo)
        val emissions = mutableListOf<List<Restaurant>>()
        val job = freshVm.restaurants.onEach { emissions.add(it) }.launchIn(this)

        freshVm.updateBounds(MapBounds(50.0, 52.0, -1.0, 1.0))
        freshVm.updateFilters(MapFilters(visitedFilter = VisitedFilter.VISITED_ONLY))
        advanceUntilIdle()

        assertTrue("expected empty before any visit", emissions.last().isEmpty())

        visitedFlow.value = setOf("r1")
        advanceUntilIdle()

        assertEquals(listOf(r1), emissions.last())
        job.cancel()
    }

    @Test fun debugStateNullOnInit() {
        assertNull(vm.debugState.value)
    }

    @Test fun hasZoomedToUserOnce_defaultsFalse() {
        assertFalse(vm.hasZoomedToUserOnce)
    }

    @Test fun hasZoomedToUserOnce_canBeSetTrue() {
        vm.hasZoomedToUserOnce = true
        assertTrue(vm.hasZoomedToUserOnce)
    }

    @Test fun saveLastCamera_storesPosition() {
        vm.saveLastCamera(51.5, -0.1, 12.0)
        assertEquals(Triple(51.5, -0.1, 12.0), vm.lastCameraPosition)
    }

    @Test fun lastCameraPosition_nullOnInit() {
        assertNull(vm.lastCameraPosition)
    }

    @Test fun debugStateDataClassEquality() {
        val bounds = MapBounds(1.0, 2.0, 3.0, 4.0)
        val filters = MapFilters()
        val state1 = DebugState(
            dbRestaurantCount = 100,
            viewportCount = 5,
            lastSyncAt = 1_000L,
            lastCsvSha = "abc12345",
            workerState = "RUNNING",
            nextSyncAt = 2_000L,
            bounds = bounds,
            filters = filters,
        )
        val state2 = state1.copy()
        assertEquals(state1, state2)
        assertEquals(state1.dbRestaurantCount, 100)
        assertEquals(state1.workerState, "RUNNING")
        assertEquals(state1.lastCsvSha, "abc12345")
    }

    // ── tile cache ────────────────────────────────────────────────────────────

    @Test fun cacheSizeMb_emitsFromTileCacheManager() = runTest {
        every { tileCacheManager.maxSizeMb } returns flowOf(500L)
        val freshVm = MapViewModel(context, repo, syncPrefs, tileCacheManager, workManager, visitedRepo)
        val values = mutableListOf<Long>()
        val job = freshVm.cacheSizeMb.onEach { values.add(it) }.launchIn(this)
        advanceUntilIdle()
        assertTrue("expected 500 emission, got $values", values.any { it == 500L })
        job.cancel()
    }

    @Test fun setCacheSizeMb_delegatesToTileCacheManager() = runTest {
        coJustRun { tileCacheManager.setMaxSizeMb(any()) }
        vm.setCacheSizeMb(500L)
        advanceUntilIdle()
        coVerify { tileCacheManager.setMaxSizeMb(500L) }
    }

    @Test fun clearTileCache_delegatesToTileCacheManager() = runTest {
        coJustRun { tileCacheManager.clearAmbientCache() }
        vm.clearTileCache()
        advanceUntilIdle()
        coVerify { tileCacheManager.clearAmbientCache() }
    }

    // ── forceRefresh ──────────────────────────────────────────────────────────

    @Test fun forceRefresh_clearsShaAndEnqueuesWork() = runTest {
        coJustRun { syncPrefs.clearSha() }

        vm.forceRefresh()
        advanceUntilIdle()

        coVerify { syncPrefs.clearSha() }
        verify { workManager.enqueueUniquePeriodicWork(DatasetSyncWorker.TAG, any(), any()) }
    }

    // ── loadDebugInfo ─────────────────────────────────────────────────────────

    @Test fun loadDebugInfo_populatesDebugState() = runTest {
        every { workManager.getWorkInfosForUniqueWorkFlow(any()) } returns flowOf(emptyList())
        coEvery { repo.count() } returns 99
        coEvery { syncPrefs.lastSyncAt() } returns 1_000L
        coEvery { syncPrefs.lastCsvSha() } returns "abcdef1234567890"

        vm.loadDebugInfo()
        advanceUntilIdle()

        val state = vm.debugState.value
        assertEquals(99, state?.dbRestaurantCount)
        assertEquals(1_000L, state?.lastSyncAt)
        assertEquals("abcdef12", state?.lastCsvSha)
        assertEquals("none", state?.workerState)
    }

    @Test fun loadDebugInfo_workerStateFromWorkInfo() = runTest {
        val workInfo = mockk<androidx.work.WorkInfo>(relaxed = true)
        every { workInfo.state } returns androidx.work.WorkInfo.State.RUNNING
        every { workInfo.nextScheduleTimeMillis } returns Long.MAX_VALUE
        every { workManager.getWorkInfosForUniqueWorkFlow(any()) } returns flowOf(listOf(workInfo))
        coEvery { repo.count() } returns 0
        coEvery { syncPrefs.lastSyncAt() } returns null
        coEvery { syncPrefs.lastCsvSha() } returns null

        vm.loadDebugInfo()
        advanceUntilIdle()

        assertEquals("RUNNING", vm.debugState.value?.workerState)
        assertNull(vm.debugState.value?.nextSyncAt)
    }

    private fun restaurant(id: String, name: String) = Restaurant(
        id = id, name = name, address = "1 Test St", location = null,
        latitude = 51.5, longitude = -0.1, distinction = Distinction.ONE_STAR,
        greenStar = false, cuisine = null, price = null, phoneNumber = null,
        michelinUrl = "https://guide.michelin.com/$id",
        websiteUrl = null, description = null, facilitiesAndServices = null,
    )
}
