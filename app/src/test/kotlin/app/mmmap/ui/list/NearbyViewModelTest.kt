package app.mmmap.ui.list

import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import app.mmmap.data.repository.RestaurantRepository
import app.mmmap.domain.model.Distinction
import app.mmmap.domain.model.Restaurant
import app.mmmap.util.haversineKm
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NearbyViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val repo            = mockk<RestaurantRepository>(relaxed = true)
    private val context         = mockk<Context>(relaxed = true)
    private val locationManager = mockk<LocationManager>(relaxed = true)
    private lateinit var vm: NearbyViewModel

    @Before fun setUp() {
        Dispatchers.setMain(testDispatcher)
        mockkStatic(ContextCompat::class)
        every { context.getSystemService(Context.LOCATION_SERVICE) } returns locationManager
        every { ContextCompat.checkSelfPermission(any(), any()) } returns PackageManager.PERMISSION_GRANTED
        every { locationManager.getLastKnownLocation(any()) } returns null
        every { repo.observeInBounds(any(), any(), any(), any(), any(), any(), any()) } returns flowOf(emptyList())
        vm = NearbyViewModel(context, repo)
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test fun initialNearbyEmpty() {
        assertTrue(vm.nearby.value.isEmpty())
    }

    @Test fun initialLocationMissingFalse() {
        assertFalse(vm.locationMissing.value)
    }

    @Test fun noPermission_setsLocationMissing() = runTest {
        every { ContextCompat.checkSelfPermission(any(), any()) } returns PackageManager.PERMISSION_DENIED

        vm.load()
        advanceUntilIdle()

        assertTrue(vm.locationMissing.value)
    }

    @Test fun noPermission_nearbyStaysEmpty() = runTest {
        every { ContextCompat.checkSelfPermission(any(), any()) } returns PackageManager.PERMISSION_DENIED

        vm.load()
        advanceUntilIdle()

        assertTrue(vm.nearby.value.isEmpty())
    }

    @Test fun noLastKnownLocation_setsLocationMissing() = runTest {
        every { locationManager.getLastKnownLocation(any()) } returns null

        vm.load()
        advanceUntilIdle()

        assertTrue(vm.locationMissing.value)
    }

    @Test fun withLocation_doesNotSetLocationMissing() = runTest {
        every { locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER) } returns fakeLocation(51.5, -0.1)

        vm.load()
        advanceUntilIdle()

        assertFalse(vm.locationMissing.value)
    }

    @Test fun boxReachesAsFarEastAsItDoesNorth() = runTest {
        // A degree of longitude shrinks with latitude, so a box of ±0.5° on both axes is
        // not a distance — it is a rectangle that narrows the further from the equator you
        // are. The list it feeds is sorted by true haversine distance, so the two reaches
        // have to match or "nearest" silently means "nearest, if it isn't due east".
        val lat = 64.15 // Reykjavik: a degree of longitude is 44% of one at the equator
        val lon = -21.94
        every { locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER) } returns
            fakeLocation(lat, lon)
        val maxLat = slot<Double>()
        val maxLon = slot<Double>()
        every {
            repo.observeInBounds(any(), capture(maxLat), any(), capture(maxLon), any(), any(), any())
        } returns flowOf(emptyList())

        vm.load()
        advanceUntilIdle()

        val northKm = haversineKm(lat, lon, maxLat.captured, lon).toDouble()
        val eastKm = haversineKm(lat, lon, lat, maxLon.captured).toDouble()
        assertEquals("east reach $eastKm km vs north reach $northKm km", northKm, eastKm, 0.5)
    }

    @Test fun boxStaysWithinValidCoordinatesNearThePole() = runTest {
        every { locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER) } returns
            fakeLocation(89.9, 0.0)
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

        vm.load()
        advanceUntilIdle()

        assertTrue("minLat ${minLat.captured}", minLat.captured >= -90.0)
        assertTrue("maxLat ${maxLat.captured}", maxLat.captured <= 90.0)
        assertTrue("minLon ${minLon.captured}", minLon.captured >= -180.0)
        assertTrue("maxLon ${maxLon.captured}", maxLon.captured <= 180.0)
    }

    @Test fun withLocation_nearbyPopulated() = runTest {
        every { locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER) } returns fakeLocation(51.5, -0.1)
        val restaurants = listOf(restaurant("r1", 51.51, -0.11), restaurant("r2", 51.52, -0.12))
        every { repo.observeInBounds(any(), any(), any(), any(), any(), any(), any()) } returns flowOf(restaurants)

        vm.load()
        advanceUntilIdle()

        assertEquals(2, vm.nearby.value.size)
    }

    @Test fun withLocation_sortedByDistance() = runTest {
        every { locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER) } returns fakeLocation(51.5, -0.1)
        val far   = restaurant("far",   51.59, -0.19)
        val close = restaurant("close", 51.501, -0.101)
        every { repo.observeInBounds(any(), any(), any(), any(), any(), any(), any()) } returns flowOf(listOf(far, close))

        vm.load()
        advanceUntilIdle()

        assertEquals("close", vm.nearby.value[0].first.id)
        assertEquals("far",   vm.nearby.value[1].first.id)
    }

    @Test fun nearbyLimitedTo50() = runTest {
        every { locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER) } returns fakeLocation(51.5, -0.1)
        val many = (1..100).map { restaurant("r$it", 51.5 + it * 0.001, -0.1 + it * 0.001) }
        every { repo.observeInBounds(any(), any(), any(), any(), any(), any(), any()) } returns flowOf(many)

        vm.load()
        advanceUntilIdle()

        assertEquals(50, vm.nearby.value.size)
    }

    @Test fun coarseOnlyPermission_proceeds() = runTest {
        every { ContextCompat.checkSelfPermission(any(), android.Manifest.permission.ACCESS_FINE_LOCATION) } returns PackageManager.PERMISSION_DENIED
        every { ContextCompat.checkSelfPermission(any(), android.Manifest.permission.ACCESS_COARSE_LOCATION) } returns PackageManager.PERMISSION_GRANTED
        every { locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER) } returns fakeLocation(51.5, -0.1)

        vm.load()
        advanceUntilIdle()

        assertFalse(vm.locationMissing.value)
    }

    @Test fun distanceInPairIsNonNegative() = runTest {
        every { locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER) } returns fakeLocation(51.5, -0.1)
        val restaurants = listOf(restaurant("r1", 51.51, -0.11))
        every { repo.observeInBounds(any(), any(), any(), any(), any(), any(), any()) } returns flowOf(restaurants)

        vm.load()
        advanceUntilIdle()

        assertTrue(vm.nearby.value.single().second >= 0f)
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun fakeLocation(lat: Double, lon: Double) = mockk<Location> {
        every { latitude }  returns lat
        every { longitude } returns lon
        every { time }      returns System.currentTimeMillis()
    }

    private fun restaurant(id: String, lat: Double, lon: Double) = Restaurant(
        id = id, name = "Restaurant $id", address = "1 Test St", location = null,
        latitude = lat, longitude = lon, distinction = Distinction.ONE_STAR,
        greenStar = false, cuisine = null, price = null, phoneNumber = null,
        michelinUrl = "https://guide.michelin.com/$id",
        websiteUrl = null, description = null, facilitiesAndServices = null,
    )
}
