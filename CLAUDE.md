# Mmmap — Claude Code notes

## What this app is

Android app that plots every MICHELIN Guide restaurant on a MapLibre map with offline-capable tile caching and multi-select filters (award, cuisine, price tier). No Google Play Services dependency.

## Common commands

```
just install       # build debug APK and push to connected device
just run           # install + launch via adb
just test          # ./gradlew testDebugUnitTest
just coverage      # tests + JaCoCo HTML report
just check         # lint + tests (run before committing)
just lint
just format        # ktlintFormat
just release 1.2.3 # clean → check → sign → tag → GitHub Release
```

`JAVA_HOME` is set automatically by the Justfile to `~/.local/jdk/jdk-17.0.19+10`.

## Architecture

```
ui/map/MapViewModel      ← filters, bounds, WorkManager, TileCacheManager
ui/detail/DetailViewModel ← VisitedRepository (tracks visited state for selected restaurant)
data/repository/
  RestaurantRepository   ← Room DAO, cuisine post-filter (client-side)
  VisitedRepository      ← visited restaurant persistence + export/import
data/sync/
  DatasetSyncWorker      ← WorkManager worker, downloads michelin CSV → Room
  SyncPreferences        ← DataStore: last SHA + timestamp
data/prefs/
  MapCachePreferences    ← DataStore: tile cache size (50/100/500/1024 MB)
map/
  TileCacheManager       ← wraps MapCachePreferences + AmbientCacheSource
  AmbientCacheSource     ← interface (real impl: MapLibreAmbientCacheSource)
  MapLibreAmbientCacheSource ← wraps OfflineManager callbacks as coroutines
di/
  DatabaseModule         ← Room + DAOs + WorkManager provider
  DataStoreModule        ← single shared DataStore<Preferences> ("mmmap_prefs")
  NetworkModule          ← OkHttp + Retrofit (GitHub CSV sync only)
  TileCacheModule        ← @Binds AmbientCacheSource → MapLibreAmbientCacheSource
```

DI: Hilt throughout. `@HiltViewModel` on all ViewModels.

## Data

**Restaurant DB** — bundled SQLite asset (`app/src/main/assets/michelin.db.seed`, gzipped, ~8 MB), refreshed periodically by `DatasetSyncWorker` pulling from `ngshiheng/michelin-my-maps` on GitHub. ~19 000 rows.

**Seed asset gotchas** — the file is gzip, inflated by `DatabaseModule` via `Room.createFromInputStream`. Do NOT rename it to `*.gz`: AGP's asset merger silently expands `foo.ext.gz` back to `foo.ext`, so the asset would be missing at runtime. `noCompress += "seed"` keeps it stored, since AssetManager can't stream a compressed asset over ~1 MB. Regenerate with `just seed-db` (needs Python 3.10+; the script uses `str | None`).

**Bundled SHA** — `scripts/seed_db.py` also writes the upstream CSV's blob SHA to `app/src/main/res/values/dataset_provenance.xml`. `MmmapApplication` seeds it into `SyncPreferences` on first launch so the first sync is a no-op unless upstream actually moved; without it every fresh install re-downloads the whole ~17.5 MB CSV. Never fetch this at build time — that would break F-Droid's reproducible build.

**Award column values** (exact strings, case-sensitive):
- `1 Star`, `2 Stars`, `3 Stars`, `Bib Gourmand`, `Selected Restaurants`

**Price tier** — derived at query time via `LENGTH(price)` in SQL (1 = `£`, 2 = `££`, etc.). No stored column.

**Cuisine filter** — Room does not filter cuisines; `RestaurantRepository` post-filters in Kotlin because compound values like `"French, Contemporary"` need splitting first.

**Room gotcha** — do NOT pre-create `room_master_table` in the bundled SQLite. Room creates it on first open; a pre-existing table (even empty) triggers destructive migration and wipes data.

## Changes are test-driven

Write the failing test first, run it, and confirm it fails **for the reason you think**.
A test written after the fix tends to assert what the code now does rather than what the
bug was, and it never demonstrates that it would have caught anything. Then make it pass,
then `just check`.

This holds for bug fixes above all: the test is the bug report. If you cannot write a test
that fails before the change, say so and say why — that is a real finding about the
codebase, not a reason to skip ahead.

### Never let `any()` stand in for the contract under test

```kotlin
// Passes whatever box the ViewModel computes — asserts nothing about it.
every { repo.observeInBounds(any(), any(), any(), any(), any(), any(), any()) }
```

Five tests stubbed `observeInBounds` exactly that way, so nothing noticed that the map
queried the viewport as an exact box and dropped every restaurant whose circle overlapped
a screen edge (`6f99e82`). `any()` is fine for arguments a test genuinely does not care
about; for the ones it does, match on the value or capture it:

```kotlin
val minLat = slot<Double>()
every { repo.observeInBounds(capture(minLat), any(), any(), any(), any(), any(), any()) } returns flowOf(emptyList())
// …
assertEquals(49.8, minLat.captured, 1e-9)
```

Setup-block stubs are the usual culprit: a `@Before` that relaxes every argument silently
disarms every test in the class.

### Where a JVM test cannot reach

`ui/map/MapScreen.kt` has no test coverage and cannot easily get any — it is MapLibre view
code, and the circle radii that caused the bug above live there in `addCustomLayers`. The
seam between it and `MapViewModel` is where bugs hide, so when a change lands in the view
layer, pin the behaviour in the layer below it where you can (the query box, the state
flow), and record in the commit message what you verified on a device or emulator, with
the API level. `fix(map): use MapLibre's OpenGL variant` (`416cc3e`) is the pattern.

## Testing patterns

### What to mock vs what to instantiate

| Class | How to test |
|---|---|
| `RestaurantRepository` | Instantiate with `mockk<RestaurantDao>` |
| `TileCacheManager` | Instantiate with real `MapCachePreferences` (DataStore) + `mockk<AmbientCacheSource>` |
| `MapViewModel` | Instantiate directly; inject `mockk<WorkManager>` |
| `SyncPreferences` / `MapCachePreferences` | Instantiate with `PreferenceDataStoreFactory.create(scope = testScope) { tmpFolder.newFile(...) }` |

### Do NOT mock OfflineManager

`OfflineManager` has a JNI native static initializer that calls `android.util.Log.e()` → `ExceptionInInitializerError` in JVM tests. The `AmbientCacheSource` interface exists specifically to keep `OfflineManager` out of unit tests. Mock the interface instead.

### WorkManager injection

`WorkManager.getInstance(context)` cannot be intercepted by `mockkStatic` during `every { }` recording — the companion's real implementation runs and calls `context.getApplicationContext()` on the matcher object, which throws `AbstractMethodError`. Always inject `WorkManager` as a constructor parameter and provide it via `DatabaseModule.provideWorkManager`.

### Room withTransaction in tests (Room 2.8.x)

```kotlin
// Room 2.8 moved withTransaction to this class:
mockkStatic("androidx.room.RoomDatabaseKt__RoomDatabase_androidKt")
coEvery { db.withTransaction<Unit>(any()) } coAnswers {
    // args[0] = extension receiver (db), args[1] = the suspend lambda
    @Suppress("UNCHECKED_CAST")
    (invocation.args[1] as suspend () -> Unit).invoke()
}
```

### Avoid withContext(Dispatchers.IO) in ViewModels under test

`withContext(IO)` dispatches to a real thread pool. If the test's `@After` calls `Dispatchers.resetMain()` before the IO thread finishes, you get `UncaughtExceptionsBeforeTest` on the next test. Use `viewModelScope.launch` on the main dispatcher instead, or restructure to avoid blocking calls inside ViewModels.

### Standard test setup for coroutine-based classes

```kotlin
private val testDispatcher = UnconfinedTestDispatcher()

@Before fun setUp() {
    Dispatchers.setMain(testDispatcher)
}

@After fun tearDown() {
    Dispatchers.resetMain()
}
```

Use `TestScope(testDispatcher)` + `testScope.runTest { }` for classes that need a stable `CoroutineScope` (e.g. DataStore-backed prefs). Use `runTest { advanceUntilIdle() }` for ViewModels.

## Security

- `local.properties` and `keystore.properties` are gitignored — never commit them.
- Release signing credentials live in `keystore.properties` (gitignored). Back up the `.jks` separately.

## Distribution plan

Phase 1 (current): self-hosted GitHub Releases APK (`just release <version>`).
Phase 2 (deferred): F-Droid — stack is already compatible (no GMS/Firebase/Mapbox).

`just deps-audit` checks for proprietary lib leakage.
