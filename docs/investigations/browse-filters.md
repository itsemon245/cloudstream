# Browse by genre across sources — investigation & plan

Status: **phase 1 (investigation + plan + mockup)**. Nothing is implemented yet.

Goal: a Browse page that shows titles for a genre (plus other filters) aggregated over all installed
extensions or a user-chosen subset, without making the app slower, and genre tags on the detail page
that open Browse pre-filtered.

All line numbers below are against `ee7e594`.

---

## 1. What extensions actually expose

### 1.1 `MainAPI` (library/src/commonMain/kotlin/com/lagradost/cloudstream3/MainAPI.kt)

| Hook | Where | What it gives Browse |
|---|---|---|
| `open val mainPage: List<MainPageData>` | MainAPI.kt:630, `MainPageData(name, data, horizontalImages)` at :410 | A static, provider-defined list of **named sections**. On many genre-oriented sites these *are* genre lists ("Action", "Horror Movies", "Korean Drama", "Anime – Romance"). The `data` string is opaque (usually a URL) — the app can only pass it back, never construct a new one. |
| `open suspend fun getMainPage(page, MainPageRequest)` | MainAPI.kt:633, `MainPageRequest` at :416 (note the upstream `//TODO genre selection or smth` at :420) | Paginated items of one section → `HomePageResponse(items: List<HomePageList>, hasNext)` (:1268). |
| `open val hasMainPage` | MainAPI.kt:563 | Providers with `false` have no sections → can only be reached through `search`. |
| `open suspend fun search(query, page)` / `search(query)` | MainAPI.kt:641 / :651 | Free-text title search. **Not** a genre search: `search("Action")` returns titles containing "action". Not usable for genre (see §2). |
| `open suspend fun quickSearch(query)` + `hasQuickSearch` | MainAPI.kt:656 / :564 | Same as search, cheaper on some providers. Not useful for Browse. |
| `open val supportedTypes: Set<TvType>` | MainAPI.kt:618 | Provider-level content types — lets us skip providers for a type filter **with zero requests**. |
| `open var lang` | MainAPI.kt:546 | Provider language (IETF tag). Provider-level, *not* country. |
| `sequentialMainPage`, `sequentialMainPageDelay`, `sequentialMainPageScrollDelay`, `lastHomepageRequest` | MainAPI.kt:524-535 | Rate-limit hints we must respect when fetching several sections of one provider. |
| `getMainPageTimeoutMs` | MainAPI.kt:580 | Per-provider timeout, already applied by `APIRepository.getTimeout` (app/.../ui/APIRepository.kt:69). |
| Existing filter/category hooks | — | **None.** There is no genre, country, year or sort parameter anywhere in the API. `MainPageRequest` carries only `name`, `data`, `horizontalImages`. |

### 1.2 List-level items: `SearchResponse` (MainAPI.kt:1404)

Interface fields: `name, url, apiName, type: TvType?, posterUrl, posterHeaders, id, quality: SearchQuality?, score: Score?`.

| Subtype | Extra fields | Line |
|---|---|---|
| `AnimeSearchResponse` | `year`, `dubStatus`, `otherName`, `episodes` | :1553 (year :1562) |
| `MovieSearchResponse` | `year` | :1653 (year :1662) |
| `TvSeriesSearchResponse` | `year`, `episodes` | :1725 (year :1734) |
| `LiveSearchResponse` | `lang` | :1689 (lang :1700) |
| `TorrentSearchResponse` | — (no year) | :1619 |

So at list level we have **type, year, score, quality, dub status** — all nullable and filled
inconsistently by extensions (many leave `year`/`score` null, some leave `type` null). **No genres/tags
and no country.**

### 1.3 Detail level: `LoadResponse` (MainAPI.kt:1812)

`tags: List<String>?` (genres, :1822), `year`, `score`, `duration`, `contentRating`, `plot`, actors,
recommendations… **Still no country field** anywhere in the library (only internal JSON fields in
`metaproviders/TmdbProvider.kt:164` and `MyDramaList.kt:232`). `LoadResponse` is only available after
`load(url)` — one (often several) HTTP requests per title. `APIRepository.load` caches only the last
20 responses for 10 minutes (APIRepository.kt:57-110).

---

## 2. Feasibility per filter

Legend: **(a)** server-side via what providers already expose · **(b)** client-side from list-level
fields, no extra requests · **(c)** only by calling `load` per item · **(d)** not feasible.

| Filter | Class | How | Honest caveats |
|---|---|---|---|
| **Genre** | **(a)** heuristic today, exact with optional API | Match the genre against each provider's `mainPage` section names (normalised, synonyms: "Sci-Fi" = "Science Fiction", "Action & Adventure" → both, strip "Movies/Series/TV Shows/Top/Latest"). Fetch only matching sections. With the optional `browse()` API (§4) a provider can serve any genre exactly. | Coverage depends on the provider: sites whose home page is only "Latest/Trending" contribute nothing for "Horror". Browse shows "N sources have no *Horror* section" instead of pretending. Client-side genre filtering is **(c)** — `SearchResponse` has no tags. `search(genre)` is rejected (title search, misleading results). |
| Genre "match all" (A **and** B) | (a)+(b) best-effort | Item appears in both genre sections of the same provider (dedupe key). | Only finds items present in the loaded pages of both sections → shown as *best-effort*. |
| **Category / type** (Movie, Series, Anime, Asian drama, Cartoon, Documentary, Live…) | **(b)** + provider pre-filter | 1) skip providers whose `supportedTypes` doesn't intersect — free; 2) filter items by `SearchResponse.type`; 3) items with `type == null` are kept when the provider supports exactly one matching type, else shown only with "include unknown". Section names like "Movies"/"TV Shows" also steer which sections we fetch. | Exact for most providers. |
| **Year** (range) | **(b)** best-effort | `year` on Movie/TvSeries/Anime responses. "Include items without a year" toggle (default on). | Many extensions don't set it; Torrent/Live have no field. Marked *best-effort* in UI. |
| **Rating** (min score) | **(b)** best-effort | `SearchResponse.score` (`Score.toDouble/toInt(10)`). Same "include unrated" toggle. | Often null at list level. *best-effort*. |
| **Quality** (bonus) | **(b)** | `SearchResponse.quality`; reuse existing `filterSearchResultByFilmQuality` (AppContextUtils.kt:482) semantics. | — |
| **Dub / Sub** (bonus, anime) | **(b)** | `AnimeSearchResponse.dubStatus`. | Only anime providers. |
| **Language** (bonus, replaces country) | **(b)** provider-level, zero requests | Filter providers by `MainAPI.lang` (same data as the existing provider-language setting, AppContextUtils.kt:400). | Language of the *site*, not of each title. |
| **Country** | **(d)** today / (a) via section names or optional API | No country field at list *or* detail level. Only possible when (1) a provider's section is named after a country ("Korean Drama", "Bollywood") — treated like a genre keyword, or (2) the provider declares countries in the optional API. | Not offered as a generic filter in v1; country-named sections appear in the genre list. Per-item `load` would not help — `LoadResponse` has no country either. |
| **Sort** | **(b)** over loaded items | Relevance (round-robin interleave of providers, like `SearchViewModel.bundleSearch`, SearchViewModel.kt:172), Name A–Z, Year (newest), Rating (highest). Server-side "Popular/Latest" only where a provider has such sections or implements the optional API. | Sorting is over what has been loaded so far (window grows as you scroll); the UI says "sorted across N loaded titles". |
| Deep genre verification via `load` | **(c)** — not in v1 | Would need 1+ request per title. If ever wanted: opt-in, only visible items, concurrency 2, persistent tag cache. | Too slow/abusive by default. |

---

## 3. Existing code to reuse

**Compose/desktop:** `shared/` and `desktopApp/` contain only theme, settings and extension screens
(`shared/src/commonMain/kotlin/com/lagradost/cloudstream4/...`, `desktopApp/.../Main.kt`). Home, Search
and Result exist only in the View-based `app`. Browse will be View-based like Search, but the
engine (planning, fetching, aggregation, filtering, sorting) is plain Kotlin with no Android types,
following the MVI direction in `COMPOSE.md` (single immutable state + one event sink) so it can later
move to `shared`.

| Need | Reuse |
|---|---|
| Provider calls with timeout, error wrapping | `APIRepository.getMainPage(page, nameIndex)` already fetches **one** section by index (APIRepository.kt:153-162); `waitForHomeDelay()` (:147). |
| Provider set (language + preferred media) | `Context.filterProviderByPreferredMedia(hasHomePageIsRequired)` (AppContextUtils.kt:448), `getApiSettings()` (:376). |
| Type chips | `tvtypes_chips.xml` / `tvtypes_chips_scroll.xml` + `HomeFragment.bindChips/updateChips/validateChips` (HomeFragment.kt:321-387). |
| Provider picker sheet | `home_select_mainpage.xml` multi-choice list as used by the Search filter button (SearchFragment.kt:286-409), incl. language flags. |
| Poster grid + click → detail | `SearchAdapter` (ui/search/SearchAdaptor.kt:33) + `SearchResultBuilder.bind` (SearchResultBuilder.kt:46), `search_result_grid.xml`, span logic (SearchFragment.kt:231). Clicks go through `SearchHelper` like Search. |
| Parallel map | `amap` (library ParCollections.kt:36) — **unbounded**; Browse wraps calls in a `Semaphore` instead. |
| Pagination pattern | `HomeViewModel.expandAndReturn` (HomeViewModel.kt:234) / `SearchViewModel.expandAndReturn` (SearchViewModel.kt:124). |
| Plugin reload invalidation | `afterPluginsLoadedEvent` (MainActivity.kt:256), as in APIRepository.kt:62. |
| Genre tags on detail | `UIHelper.populateChips(ChipGroup, tags)` (UIHelper.kt:107) — creates non-clickable chips; called from `ResultFragmentPhone.kt:989` and `ResultFragmentTv.kt:926` with `ResultData.tags` (ResultViewModel2.kt:169). |
| Navigation | `Activity?.navigate(id, bundle)` (UIHelper.kt:243), `mobile_navigation.xml` (Search destination with `search_query` arg at :310-323). |

**Constraint found:** the bottom bar is a Material `BottomNavigationView` (activity_main.xml:37) that
already has the maximum of **5** items (menu/bottom_nav_menu.xml). A 6th tab is not allowed by
Material. Browse therefore becomes a regular destination (nav bar stays visible, Search tab
highlighted) reached from: a "Browse" button in the Search top bar next to the filter button
(fragment_search.xml:98), a "Browse by genre" row on the empty Search page, and genre tags on the
detail page. (Open question: replace a tab instead?)

---

## 4. Optional library API (backward compatible)

Worth adding, because the heuristic can't do "any genre on any provider" — only providers know their
genre URLs. Design rules: existing extensions keep working unchanged; nothing is added to existing
data classes (adding a constructor parameter to `MainPageData`/`MainPageRequest` changes their JVM
constructor/`copy`/`componentN` signatures and would break already-compiled extensions); everything
new is marked `@Prerelease` so `abiValidation` excludes it (library/build.gradle.kts:88-96) and
extension authors get the opt-in error explaining it crashes on stable.

New file `library/src/commonMain/kotlin/com/lagradost/cloudstream3/Browse.kt`:

```kotlin
@Prerelease
data class BrowseOptions(
    val genres: List<BrowseOption> = emptyList(),   // id = provider value, name = display ("Action")
    val countries: List<BrowseOption> = emptyList(),
    val sorts: List<BrowseOption> = emptyList(),    // e.g. popular, latest, rating
    val supportsYear: Boolean = false,
    val supportsMultipleGenres: Boolean = false,
)
@Prerelease data class BrowseOption(val id: String, val name: String)
@Prerelease data class BrowseRequest(
    val genres: List<String>, val countries: List<String>, val sort: String?,
    val yearFrom: Int?, val yearTo: Int?, val types: Set<TvType>,
)
```

On `MainAPI`:

```kotlin
@Prerelease open val browseOptions: BrowseOptions? = null
@Prerelease open suspend fun browse(page: Int, request: BrowseRequest): SearchResponseList? =
    throw NotImplementedError()
@Prerelease open val browseTimeoutMs: Long? = null
```

Adding open members with bodies to an abstract class is binary compatible for existing subclasses.
`library:checkKotlinAbi` already fails on master for upstream reasons (known); the plan is to verify
that the Browse change adds **no new lines** to `library/api/jvm/library.api` diff beyond that.
A provider with `browseOptions` is used through `browse()`; all others through section matching.

---

## 5. Architecture

```
BrowseFragment (View UI) ──events──▶ BrowseViewModel ──state (StateFlow<BrowseUiState>)──▶ UI
                                        │
                     FetchKey changed?  │  only client filters changed?
                        ▼ yes           │        ▼
                 BrowsePlanner          │   BrowseFilter.apply(pool)   (Dispatchers.Default, pure)
                 (pure, no network)     │
                        ▼               │
                 BrowseFetcher ──▶ Flow<SourceEvent> ──▶ BrowseAggregator (pool, dedupe) ─┘
                 (Semaphore, cache,                         
                  cancellation, paging)
```

* **`BrowseQuery`** = `FetchKey` + `ClientFilters`.
  * `FetchKey` (changing it re-fetches): genres, genre match mode, selected providers, and server-side
    options for optional-API providers (sort/country/year).
  * `ClientFilters` (never re-fetch): types, year range, min rating, include-unknown toggles, quality,
    dub/sub, sort.
* **`BrowsePlanner`** (pure): for each provider decide sources:
  `BrowseApi(request)` if `browseOptions` covers the genre → else `Sections(indices)` from
  `GenreMatcher.match(mainPage names, genres)` → else `Skipped(reason)`. Type filter prunes providers
  via `supportedTypes`. Section genres come from the `GenreIndex` (below), so planning does no
  string parsing.
* **`GenreIndex`** (implemented): genres of every provider section, built once off the main thread
  and cached in `BrowseGenreIndexCache` until plugins reload (or the provider set changes). Shared
  by Search's genre row, the planner, the filter sheet and the source picker.
* **`GenreMatcher`** (pure): normalisation (lowercase, diacritics, `&`/`/`/`,` splits, stop words
  "movies, series, tv, shows, top, latest, new, popular, best"), synonym table (sci-fi/science
  fiction, rom-com/romance+comedy, kdrama/korean drama, …). Genre list in the UI = canonical genres
  ∪ genres discovered from section names, with per-genre source counts. A discovered genre is the
  section name minus list words ("Latest", "Trending", "Popular", "New", "Recent", "Top",
  "Movies", "Series", …), at most three words, and is listed only when **at least two sources**
  share it (e.g. "Superhero Movies" + "Superhero" → *Superhero*). Discovered and ad-hoc genres
  (from detail page tags) match sections by their words. (`browseOptions.genres` joins this list
  once the optional provider API lands.)
* **`BrowseFetcher`**:
  * global `Semaphore(4)` for concurrent provider calls (Search today fires all providers at once);
    at most 1 in-flight call per provider, honouring `sequentialMainPage*` delays via
    `waitForHomeDelay()`; timeouts via existing `APIRepository` logic.
  * first page of every source in parallel (bounded), results emitted as they arrive
    (`SourceEvent.Loaded/Failed/Skipped`) → **progressive grid**.
  * paging (implemented): a real downward scroll near the end requests the next page of every
    section that has one (interleaved by provider). While fewer than 30 titles are visible, up to
    **3** extra steps load automatically (also when the grid is too short to scroll); after that
    only the user's scroll or the "Load more" button continues. A manual load never resets that
    budget (layout passes report `onScrolled(0, 0)`, which is ignored); only a new fetch or new
    filters reset it.
  * failures: a source whose request failed shows "failed · retried when you apply"; applying the
    filter sheet re-requests the failed pages of the selected sources.
  * **cache**: in-memory LRU of `(provider, sourceId, page) → items, hasNext`, TTL 10 min, ~200
    entries; cleared on `afterPluginsLoadedEvent(forceReload)`. Re-opening Browse, toggling a genre
    off and on, or going back from the detail page doesn't refetch.
  * **cancellation**: one `Job` per `FetchKey` in `viewModelScope`; a new key cancels the old job
    (coroutine cancellation propagates into OkHttp via the NiceHttp suspend calls); results from a
    stale key are dropped by a generation counter (same idea as `currentSearchIndex`,
    SearchViewModel.kt:67).
* **`BrowseAggregator`**: keeps a pool (cap 3 000 items) of `BrowseItem(response, sources, genres,
  arrivalIndex)`; dedupes by `apiName+url`, and merges cross-provider duplicates by normalised
  `name + year` (merge keeps the first poster and lists other sources). Items are updated on the
  main thread while filtering runs on `Dispatchers.Default`, so `genres`/`alsoOn` are immutable
  sets replaced copy-on-write (a stress test showed the earlier mutable set losing a
  "match all genres" result ~49k times in 4M reads).
* **`BrowseFilter`** (pure, O(n), plus O(n log n) sort): applied on `Dispatchers.Default`, results
  posted with `distinctUntilChanged`; recomputation is conflated (only the latest pool/filter
  combination is computed). UI uses `ListAdapter`/DiffUtil, so new arrivals only insert rows.
* **`BrowseUiState`**: `items`, `activeChips`, `genres(with counts)`, `providerStatus`
  (`loading/loaded/failed/skipped` per provider + "x of y sources"), `isLoadingMore`, `canLoadMore`.

### Genre-link navigation

* New destination `navigation_browse` in `mobile_navigation.xml` with args `genre: String?`,
  `apiName: String?` (optional: "this source only").
* `populateChips` gets an optional `onClick: ((String) -> Unit)?` (default `null` → today's behaviour,
  so other callers are untouched). Result phone/TV pass a callback that navigates to Browse with the
  tag; chips become clickable/focusable (TV D-pad).
* Browse normalises the tag through `GenreMatcher`. If no source has a matching section it still
  opens and shows "No source lists *X* as a category" with a button "Search titles for *X*"
  (honest fallback, opens normal Search).
* Long-press on a tag (optional): "Browse *X* on <this provider> only".

---

## 6. UI structure (approved design)

Mockup (Claude Design canvas, private): https://claude.ai/artifact/2aCZChzAP9CdfD7d6J3xnS —
phone row = blue accent, landscape row = teal accent; both just follow the user's accent setting
(`?attr/colorPrimary`), nothing is hard-coded.

1. **Search entry** (`fragment_search.xml` / `fragment_search_tv.xml`): while the query is empty, a
   "Browse by genre" header (title + → arrow) with up to ~10 genre chips sits above the search
   history. Genres = the ones most installed sources have a section for. Hidden when no source has
   any genre section.
2. **Browse page**
   * Bar: back, a "Search in Browse…" field, filter button (dot when sheet-only filters are
     active). The field filters the loaded pool by title (case/diacritics-insensitive, also
     matches `otherName`), debounced ~150 ms, no network. When nothing matches it shows
     "Search all sources for *q*" → opens the normal Search with that query.
   * Three pills: **Genre ▾**, **Type ▾**, **Sort ▾**. Each opens a small single-purpose picker
     (reusing `sort_bottom_sheet` / `sort_bottom_single_choice`), the filter button opens the full
     sheet.
   * Hairline progress bar under the bar (fills as sources answer, hides when done).
   * Grid: the existing `search_result_grid` items via `SearchAdapter` (posters, rating badge and
     quality tag already honour the user's "show rating/HD" settings), placeholder tiles for
     sources still loading, `GridLayoutManager` with the same span logic as Search.
   * Empty state: "No source lists *X* as a category" + "Search titles for *X*" button.
3. **Filter sheet** (phone: `BottomSheetDialog`; landscape/TV: right-side panel, 400dp, same
   content view): Genre chips (top 7 + "+N more" expands inline, multi-select), Type, Sort,
   Rating (Any/6+/7+/8+); row **Sources ›**; folded **More** (year from–to, quality, audio,
   "Hide titles without year or rating"); Reset; primary button "Show N titles" (N computed
   live from the loaded pool).
4. **Sources**: the app's multi-select list dialog; each row is flag, name and a status
   ("3 Action sections", "no Action section", "failed · retried when you apply"). Selecting every
   source means "all installed" (includes sources installed later). Long-press on a detail page
   tag browses only that title's source, even if language/media settings hide it.
5. **Detail page genre tags**: same dark chips (`ChipFilled`), no arrow; text tinted with a lighter
   shade of the accent (`ColorUtils.blendARGB(colorPrimary, white, ~0.45)` for contrast), ripple on
   press, focusable on TV. Tap → Browse(genre); long-press → Browse(genre, this source only).

## 7. Performance strategy (summary)

* Zero `load()` calls for filtering. Only `getMainPage`/`browse` on matching sections.
* Bounded concurrency (4 global, 1 per provider), per-provider rate-limit hints respected.
* Progressive emission per source; DiffUtil updates; filtering off the main thread, conflated.
* Client filters/sort never touch the network; FetchKey changes cancel stale work.
* LRU+TTL page cache, cached section→genre index, plugin-reload invalidation.
* Hard caps: pool size, pages per scroll step; "Load more" instead of runaway paging.

## 8. Risks

* Genre coverage via section names depends on providers; many only expose "Latest/Popular".
  Mitigation: transparent per-source status, optional API for richer providers.
* Synonym/normalisation false positives (e.g. "Action" matching "Live Action"). Mitigation: token
  match not substring, unit-tested table, user can narrow sources.
* Multilingual section names (e.g. "Acción", "Komedi") — v1 ships an English synonym table plus a
  few common languages; unmatched sections still appear as discovered genres.
* Sorting/year/rating are over loaded items only and nullable fields → labelled best-effort.
* Providers that hammer/ban on many parallel requests — handled by per-provider serialisation.
* `checkKotlinAbi` already red on master — must diff carefully so Browse adds nothing new.
* Bottom nav is full (5 items) — entry-point placement is a UX decision (open question).

## 9. Implementation steps (phase 2)

New package `app/src/main/java/com/lagradost/cloudstream3/ui/browse/`:

| File | What |
|---|---|
| `engine/GenreMatcher.kt` | normalisation, synonyms, section-name → genre matching, discovered genre keys (pure) |
| `engine/GenreIndex.kt` | per-section genres + available genre list, built once; `BrowseGenreIndexCache.kt` caches it per plugin load |
| `engine/BrowseSession.kt` | per-fetch bookkeeping: pages per section, failures/retries, statuses, progress |
| `engine/BrowsePaging.kt` | when a scroll should load more (ignores layout passes) |
| `engine/BrowseModels.kt` | `BrowseQuery`, `FetchKey`, `ClientFilters`, `BrowseItem`, `SourceStatus`, `BrowseUiState` |
| `engine/BrowsePlanner.kt` | per-provider plan: sections to fetch / skipped + reason (pure) |
| `engine/BrowseFilter.kt` | type/year/rating/quality/audio filters + sort + "Show N" count (pure) |
| `engine/BrowseAggregator.kt` | pool, dedupe (apiName+url, then normalised name+year), caps |
| `engine/BrowsePageCache.kt` | LRU+TTL page cache, cleared on plugin reload |
| `engine/BrowseFetcher.kt` | semaphore(4), 1-per-provider, rate-limit delays, timeouts, paging, cancellation; talks to providers through a small `BrowseSource` interface (implemented over `APIRepository`) so tests use fakes |
| `BrowseViewModel.kt` | MVI: `LiveData<BrowseUiState>` (like the rest of the app) + `onEvent(BrowseEvent)` |
| `BrowseFragment.kt` | phone + TV via `isLayout()`, pills, grid, progress, empty state |
| `BrowseFilterSheet.kt` | filter content view, hosted in a BottomSheetDialog (phone) or side panel (landscape/TV) |
| `BrowseSourcesPicker.kt` | source list (reuses the multi-select dialog) |

Changes to existing code (kept minimal, existing behaviour unchanged):

* `res/navigation/mobile_navigation.xml`: `navigation_browse` (args `genre`, `apiName`, both optional).
* `MainActivity.kt`: add `navigation_browse` to `isNavVisible` and treat it as the Search tab for
  bottom-nav / rail highlighting.
* `ui/search/SearchFragment.kt` + both search layouts: "Browse by genre" header above history.
* `utils/UIHelper.populateChips`: optional `onClick` / `onLongClick` / `linkStyle` params
  (defaults = today's behaviour) — other callers untouched.
* `ui/result/ResultFragmentPhone.kt:989`, `ResultFragmentTv.kt:926`: pass the callbacks.
* `DataStoreHelper`: remember last Browse type/sort/rating and chosen sources (per account,
  like `searchPreferenceProviders`).
* `res/values/strings.xml`: new strings only (English; translations come via Weblate).
* `app/build.gradle.kts`: `testImplementation(libs.kotlinx.coroutines.test)` (already in the catalog).
* **No `library` changes** in this phase → `checkKotlinAbi` unaffected. The optional
  `@Prerelease` provider API (§4) is a follow-up.

Tests (`app/src/test/java/com/lagradost/cloudstream3/ui/browse/`):
`GenreMatcherTest` (synonyms, token vs substring, multilingual leftovers),
`BrowsePlannerTest` (type pruning, no-section skip, sequential providers),
`BrowseFilterTest` (each filter, unknown-value handling, sort stability, count),
`BrowseAggregatorTest` (dedupe/merge, caps), `BrowsePageCacheTest` (TTL, LRU, invalidation),
`BrowseFetcherTest` (progressive emission order, concurrency ≤ 4, 1 per provider, cancellation on
new FetchKey, cache hit = no call, client filter change = no call, paging stops at cap).

Order of work: engine + tests → view model → Browse page → sheets → Search entry → detail links →
build `assemblePrereleaseDebug` + `testPrereleaseDebugUnitTest`. No emulator here, so UI is verified
by build + unit tests only; you test on device.

## 10. Decisions (from review)

1. Pills open their own small pickers; the filter button opens the full sheet.
2. Optional `@Prerelease` provider API: follow-up change, not in this one (no `library` changes).
3. Country: dropped (no reliable data). Source language is shown in the Sources list only.
4. Default sources: all installed, respecting the provider-language setting.
5. Last type / sort / rating / chosen sources are remembered; genre comes from the entry point.
6. No "verify genres by opening titles" mode.
7. One PR with multiple commits (opened only when asked).
8. Added: search field on the Browse page, filtering loaded results locally (see §6.2).
