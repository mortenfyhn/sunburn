package no.fyhn.uvindex

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

private val Background = Color(0xFFFFFFFF)
private val Ink = Color(0xFF111111)
private val Muted = Color(0xFF666666)

private val AppColors = lightColorScheme(
    primary = Ink,
    onPrimary = Background,
    surface = Background,
    onSurface = Ink,
    background = Background,
    onBackground = Ink,
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = AppColors) {
                Surface(modifier = Modifier.fillMaxSize(), color = Background) {
                    UvApp()
                }
            }
        }
    }
}

private sealed interface ForecastState {
    object Idle : ForecastState
    object Loading : ForecastState
    data class Loaded(val hours: List<HourUv>) : ForecastState
    data class Error(val message: String) : ForecastState
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UvApp() {
    val context = LocalContext.current
    val recents = remember { Recents(context) }
    val scope = rememberCoroutineScope()

    val selected by recents.selected.collectAsState(initial = null)
    val recentList by recents.list.collectAsState(initial = emptyList())

    var forecast by remember { mutableStateOf<ForecastState>(ForecastState.Idle) }
    var sheetOpen by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    LaunchedEffect(selected) {
        val s = selected
        if (s == null) {
            forecast = ForecastState.Idle
            return@LaunchedEffect
        }
        // Stale-while-revalidate: show cached data immediately if we have it
        // (so the app works offline if today's forecast was fetched earlier),
        // then try to refresh in the background. On refresh failure keep the
        // cache silently — surfacing an error would be noise when the user
        // already has usable data on screen.
        val cached = recents.loadCache(s)
        forecast = if (cached != null) ForecastState.Loaded(cached) else ForecastState.Loading
        runCatching { fetchForecast(s) }
            .onSuccess {
                forecast = ForecastState.Loaded(it)
                recents.saveCache(s, it)
            }
            .onFailure { e ->
                if (cached == null) {
                    forecast = ForecastState.Error(e.message ?: "Could not load forecast")
                }
            }
    }

    Scaffold(containerColor = Background) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            // Top bar — tap anywhere to change location.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { sheetOpen = true }
                    .padding(top = 16.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = selected?.displayName ?: "Pick a location",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = Ink,
                )
                Text(
                    text = " ▾",
                    fontSize = 16.sp,
                    color = Muted,
                )
            }

            when (val f = forecast) {
                ForecastState.Idle -> EmptyState(onPickLocation = { sheetOpen = true })
                ForecastState.Loading -> CenterMessage("Loading…")
                is ForecastState.Error -> CenterMessage(f.message, isError = true)
                is ForecastState.Loaded -> LoadedView(loc = selected!!, hours = f.hours)
            }
        }
    }

    if (sheetOpen) {
        ModalBottomSheet(
            onDismissRequest = { sheetOpen = false },
            sheetState = sheetState,
            containerColor = Background,
        ) {
            LocationPicker(
                recents = recentList,
                bias = selected ?: recentList.firstOrNull(),
                onPicked = { loc ->
                    scope.launch {
                        recents.select(loc)
                        sheetOpen = false
                    }
                },
                onDeleteRecent = { loc -> scope.launch { recents.delete(loc) } },
            )
        }
    }
}

@Composable
private fun LoadedView(loc: Location, hours: List<HourUv>) {
    val zone = loc.timezone?.let { runCatching { ZoneId.of(it) }.getOrNull() }
        ?: ZoneId.systemDefault()
    val nowAtLocation = LocalDateTime.now(zone)
    val minutesSinceStart = ChronoUnit.MINUTES.between(hours.first().localTime, nowAtLocation)
    val nowFracHour = (minutesSinceStart / 60.0).coerceIn(0.0, (hours.size - 1).toDouble())
    val nowUv = interpolatedUv(hours, nowFracHour)

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Pushes the chart block toward vertical centre.
        Spacer(Modifier.weight(1f))

        Text(
            "UV index",
            color = Ink,
            fontSize = 18.sp,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = formatUv(nowUv),
            fontSize = 56.sp,
            fontWeight = FontWeight.Light,
            color = Ink,
        )

        Spacer(Modifier.height(24.dp))

        UvChart(
            hours = hours,
            nowFracHour = nowFracHour,
            modifier = Modifier
                .fillMaxWidth()
                .height(320.dp),
        )

        Spacer(Modifier.weight(1f))

        // Attributions: CC BY 4.0 for UV data, ODbL for OSM search.
        Text(
            text = "UV: currentuvindex.com · Search: OpenStreetMap",
            color = Muted,
            fontSize = 11.sp,
            modifier = Modifier.padding(bottom = 16.dp),
        )
    }
}

@Composable
private fun EmptyState(onPickLocation: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Pick a location to see today's UV.", color = Muted)
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onPickLocation) {
                Text("Choose location", color = Ink)
            }
        }
    }
}

@Composable
private fun CenterMessage(message: String, isError: Boolean = false) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = message,
            color = if (isError) Color(0xFFC22E2E) else Muted,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LocationPicker(
    recents: List<Location>,
    bias: Location?,
    onPicked: (Location) -> Unit,
    onDeleteRecent: (Location) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Location>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(query) {
        val q = query.trim()
        if (q.length < 2) {
            results = emptyList()
            searching = false
            searchError = null
            return@LaunchedEffect
        }
        searching = true
        searchError = null
        delay(500) // debounce — keep below Nominatim's 1 req/sec policy.
        runCatching { searchLocations(q, bias) }
            .onSuccess { results = it }
            .onFailure {
                results = emptyList()
                searchError = it.message ?: "Search failed"
            }
        searching = false
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 520.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Search city…", color = Muted) },
            singleLine = true,
        )

        Spacer(Modifier.height(8.dp))

        val showRecents = query.trim().length < 2
        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            if (showRecents) {
                if (recents.isEmpty()) {
                    item {
                        Text(
                            "Type a city name to search.",
                            color = Muted,
                            modifier = Modifier.padding(vertical = 16.dp),
                        )
                    }
                } else {
                    item {
                        Text(
                            "Recent",
                            color = Muted,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    }
                    items(recents) { loc ->
                        LocationRow(loc, onPicked, onDelete = { onDeleteRecent(loc) })
                    }
                }
            } else {
                if (searching) {
                    item { CenterRow("Searching…") }
                }
                searchError?.let { err ->
                    item { CenterRow(err, isError = true) }
                }
                items(results) { loc -> LocationRow(loc, onPicked) }
                if (!searching && results.isEmpty() && searchError == null) {
                    item { CenterRow("No results") }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun LocationRow(
    loc: Location,
    onPicked: (Location) -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            loc.displayName,
            color = Ink,
            fontSize = 16.sp,
            modifier = Modifier
                .weight(1f)
                .clickable { onPicked(loc) }
                .padding(vertical = 14.dp),
        )
        if (onDelete != null) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clickable(onClick = onDelete),
                contentAlignment = Alignment.Center,
            ) {
                Text("×", fontSize = 22.sp, color = Muted)
            }
        }
    }
    HorizontalDivider(color = Color(0xFFEEEEEE))
}

@Composable
private fun CenterRow(text: String, isError: Boolean = false) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (isError) Color(0xFFC22E2E) else Muted)
    }
}

private fun formatUv(uv: Double): String {
    return ((uv * 10).roundToInt() / 10.0).let {
        if (it == it.toLong().toDouble()) "${it.toLong()}.0" else it.toString()
    }
}
