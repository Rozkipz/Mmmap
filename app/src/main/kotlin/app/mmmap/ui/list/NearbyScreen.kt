package app.mmmap.ui.list

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import app.mmmap.domain.model.Restaurant
import app.mmmap.ui.shortLabel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NearbyScreen(
    onNavigateToRestaurant: (Restaurant) -> Unit = {},
    viewModel: NearbyViewModel = hiltViewModel(),
) {
    val nearby by viewModel.nearby.collectAsState()
    val locationMissing by viewModel.locationMissing.collectAsState()

    LaunchedEffect(Unit) { viewModel.load() }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Near Me") }) }
    ) { padding ->
        // Without this the "no location" case is indistinguishable from "nothing nearby".
        if (locationMissing) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding).padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Location unavailable. Grant location access to see restaurants near you.",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
            }
            return@Scaffold
        }
        // The guide covers a limited set of regions, so "nothing within ~55km" is the
        // normal case for most of the world — without this it renders as a blank page
        // that looks broken.
        if (nearby.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding).padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "No MICHELIN Guide restaurants near you. Pan the map to explore other areas.",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
            }
            return@Scaffold
        }
        LazyColumn(contentPadding = padding) {
            items(nearby, key = { it.first.id }) { (restaurant, distanceKm) ->
                NearbyRow(
                    restaurant = restaurant,
                    distanceKm = distanceKm,
                    onClick = { onNavigateToRestaurant(restaurant) },
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun NearbyRow(
    restaurant: Restaurant,
    distanceKm: Float,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(restaurant.name, fontWeight = FontWeight.SemiBold)
            Text(
                listOfNotNull(restaurant.distinction.shortLabel(), restaurant.cuisine, restaurant.price)
                    .joinToString("  ·  "),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = if (distanceKm < 1f) "${(distanceKm * 1000).toInt()}m" else "${"%.1f".format(distanceKm)}km",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

