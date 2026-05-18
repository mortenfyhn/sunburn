package no.fyhn.uvindex

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.Locale

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
    object Loading : ForecastState
    data class Loaded(val hours: List<HourUv>) : ForecastState
    object Error : ForecastState
}

/**
 * Seed location for a fresh install (or after clearing app data). Coords and
 * timezone are hardcoded; country comes from a string resource so the rendered
 * display name matches the user's locale ("Trondheim, Trøndelag, Norway" vs.
 * "…, Norge" / "…, Noreg") — and matches what Nominatim would return if the
 * same location were searched manually.
 */
private fun defaultLocation(context: android.content.Context): Location =
    Location(
        name = "Trondheim",
        country = context.getString(R.string.country_norway),
        admin1 = "Trøndelag",
        latitude = 63.4305,
        longitude = 10.3951,
        timezone = "Europe/Oslo",
    )

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UvApp() {
    val context = LocalContext.current
    val recents = remember { Recents(context) }
    val scope = rememberCoroutineScope()

    val selected by recents.selected.collectAsState(initial = null)
    val recentList by recents.list.collectAsState(initial = emptyList())

    var forecast by remember { mutableStateOf<ForecastState>(ForecastState.Loading) }
    var sheetOpen by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Keep `selected` non-null for the lifetime of the composition. On fresh
    // install the underlying DataStore emits null and we seed Trondheim; if
    // the user later deletes their currently-selected recent we'll see null
    // again and either jump to the new top of the list or reseed.
    LaunchedEffect(Unit) {
        recents.selected.collect { sel ->
            if (sel == null) {
                val firstRecent = recents.list.first().firstOrNull()
                recents.select(firstRecent ?: defaultLocation(context))
            }
        }
    }

    LaunchedEffect(selected) {
        val s = selected
        if (s == null) {
            // Brief window before the seed effect above writes the default.
            // Stay in Loading; the flow re-emits once the seed lands.
            forecast = ForecastState.Loading
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
            .onFailure {
                // runCatching catches Throwable, including CancellationException
                // thrown when the parent LaunchedEffect restarts (e.g. user
                // switches location quickly). Without rethrowing we'd flash
                // "Could not load" from a coroutine that's no longer current.
                if (it is CancellationException) throw it
                if (cached == null) forecast = ForecastState.Error
            }
    }

    Scaffold(containerColor = Background) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            // Main content area — fills the screen above the bottom bar.
            Box(modifier = Modifier.weight(1f)) {
                when (val f = forecast) {
                    ForecastState.Loading -> CenterMessage(stringResource(R.string.loading))
                    ForecastState.Error -> CenterMessage(
                        stringResource(R.string.could_not_load),
                        isError = true,
                    )
                    is ForecastState.Loaded -> LoadedView(
                        loc = selected!!,
                        hours = f.hours,
                        onPickLocation = { sheetOpen = true },
                    )
                }
            }

            // Attribution stays at the bottom for every state where a location
            // is selected. The location picker now lives inside LoadedView
            // (centred between chart and attribution) — easier to reach with
            // a thumb than the original top-bar position.
            if (selected != null) {
                AttributionRow()
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
private fun LoadedView(loc: Location, hours: List<HourUv>, onPickLocation: () -> Unit) {
    val zone = loc.zoneOrSystem()
    // Tick every 5 minutes so the hero number and "now" dot keep up with
    // wall-clock time when the app stays open. The UV curve changes slowly
    // enough that finer granularity isn't worth the wake-ups — and 5 min
    // is cheap enough (one state write, no rendering when not visible) that
    // we don't need to bind the timer to the activity's lifecycle.
    var nowAtLocation by remember(zone) { mutableStateOf(LocalDateTime.now(zone)) }
    LaunchedEffect(zone) {
        while (true) {
            delay(5 * 60_000L)
            nowAtLocation = LocalDateTime.now(zone)
        }
    }
    val minutesSinceStart = ChronoUnit.MINUTES.between(hours.first().localTime, nowAtLocation)
    val nowFracHour = (minutesSinceStart / 60.0).coerceIn(0.0, (hours.size - 1).toDouble())
    val nowUv = interpolatedUv(hours, nowFracHour)

    // Three equal weight spacers — above the chart, between the chart and the
    // location row, and below the location row — so the chart sits in the
    // upper portion and the location picker sits centred in the space between
    // the chart and the attribution (which lives in the outer Column).
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1f))

        Text(
            stringResource(R.string.title_uv_index),
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

        LocationRowBar(name = loc.displayName, onClick = onPickLocation)

        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun LocationRowBar(name: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(text = name, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Ink)
        Text(text = " ▾", fontSize = 16.sp, color = Muted)
    }
}

@Composable
private fun AttributionRow() {
    // CC BY 4.0 (currentuvindex.com) and ODbL (OpenStreetMap/Nominatim) both
    // require visible attribution. Keeping it on the main screen avoids having
    // to add an About screen for what is otherwise a one-screen app.
    Column(
        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.attribution),
            color = Muted,
            fontSize = 11.sp,
            textAlign = TextAlign.Center,
        )
        Text(
            text = BuildConfig.GIT_DESCRIBE,
            color = Muted,
            fontSize = 10.sp,
            textAlign = TextAlign.Center,
        )
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
    var searchError by remember { mutableStateOf(false) }

    LaunchedEffect(query) {
        val q = query.trim()
        if (q.length < 2) {
            results = emptyList()
            searching = false
            searchError = false
            return@LaunchedEffect
        }
        searching = true
        searchError = false
        delay(500) // debounce — keep below Nominatim's 1 req/sec policy.
        runCatching { searchLocations(q, bias) }
            .onSuccess { results = it }
            .onFailure {
                // Same trap as in UvApp's LaunchedEffect(selected): rethrow
                // CancellationException so a typing-fast user doesn't flash
                // "Search failed" from a stale request.
                if (it is CancellationException) throw it
                results = emptyList()
                searchError = true
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
            placeholder = { Text(stringResource(R.string.search_city), color = Muted) },
            singleLine = true,
        )

        Spacer(Modifier.height(8.dp))

        val showRecents = query.trim().length < 2
        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            if (showRecents) {
                if (recents.isEmpty()) {
                    item {
                        Text(
                            stringResource(R.string.type_city_name),
                            color = Muted,
                            modifier = Modifier.padding(vertical = 16.dp),
                        )
                    }
                } else {
                    item {
                        Text(
                            stringResource(R.string.recent),
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
                    item { CenterRow(stringResource(R.string.searching)) }
                }
                if (searchError) {
                    item { CenterRow(stringResource(R.string.search_failed), isError = true) }
                }
                items(results) { loc -> LocationRow(loc, onPicked) }
                if (!searching && results.isEmpty() && !searchError) {
                    item { CenterRow(stringResource(R.string.no_results)) }
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

// Locale.ROOT so a Norwegian device doesn't render "2,3" instead of "2.3".
private fun formatUv(uv: Double): String = "%.1f".format(Locale.ROOT, uv)
