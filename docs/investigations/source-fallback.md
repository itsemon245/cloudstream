# Source fallback across episodes

Status: diagnosed, failing tests added, fix implemented on branch
`claude/source-fallback-mirrors`.

## Symptom

The user picks a source in the player's Sources/Subtitles dialog, for example `Vidy [Atlanta]`
or `Atlantic [Lyra] 1080p`. If the next episode has no source with exactly that name, the player
starts on some other link, often a broken one (an `ERROR_CODE_IO_BAD_HTTP_STATUS (2004)` toast
appears). The user then has to pick a source again for every episode.

## How a source is remembered today

Line numbers are from the pre-fix tree (`ee7e594`), under
`app/src/main/java/com/lagradost/cloudstream3/ui/player/`.

| Step | Where |
|---|---|
| The dialog label is `"${link.name} ${Qualities.getStringByInt(link.quality)}"`. So `Vidy [Seattle]` is a link **named** `Vidy [Seattle]` with quality `Unknown` (400, which prints as ""). `Vidy [Atlanta] 720p` is named `Vidy [Atlanta]` with quality 720. | `GeneratorPlayer.kt:1166` |
| Pressing Apply stores the chosen link's `(source, name, quality)`. This is the only place a pick is remembered. | `GeneratorPlayer.kt:1419-1421` → `rememberSourceSelection` `GeneratorPlayer.kt:211-215` → `PlayerGeneratorViewModel.kt:186-193` |
| The pick is stored with `setKey("$account/player_source_selection/$apiName\|$parentId", StoredSourceSelection)`. That is **one value** per account + provider + series, and each pick overwrites the last. | `EpisodeSelectionPreference.kt:6,53-57`, `GeneratorPlayer.kt:202-215` |
| The stored pick is read back when the player fragment is created. The view model keeps it in memory while the user moves to the next or previous episode in the same player. | `GeneratorPlayer.kt:217-220, 2301` |
| Early start: while links are still loading, the player starts only if `findReadyPreferredSource` finds the same name, or the same release traits. If a pick is remembered but not "ready", it waits until loading finishes. | `GeneratorPlayer.kt:2430-2440` |
| Startup choice: `findPreferredSource` → `findPreferredQualityFallback` → first usable link in quality-profile order. | `GeneratorPlayer.kt:1646-1648` |

## Why the selection is lost

1. **`findBest` only knows exact names and an equal `source` field**
   (`EpisodeSelectionPreference.kt:104-148`).
   - A candidate whose `quality` differs is skipped outright (line 112).
   - Otherwise it matches only when `source` is equal (rank 2, line 119) or `name` is equal
     (rank 1).
   - Many providers set `source == name` for each mirror (`source = "Vidy [Seattle]"`). In that
     case `Vidy [Atlanta]` and `Vidy [Seattle]` share nothing, so `findBest` returns `null`.
     Nothing in the code knows that `Vidy [Seattle]` is a mirror of `Vidy [Atlanta]`.
2. **The same-quality fallback ignores the family** (`findSameQuality`, lines 156-162, called
   from `GeneratorPlayer.kt:1647`). It returns the *first* link with an equal `quality` field, in
   quality-profile priority order. That is effectively CloudStream's default pick restricted to
   one quality:
   - `Atlantic [Lyra] 1080p` becomes whatever 1080p link ranks highest (for example
     `DahmerMovies …`, which is often the broken one).
   - `Vidy [Atlanta]` (Unknown) becomes the first Unknown-quality link of any provider.
3. **Resolution comes only from the `quality` field.** A mirror named `Vidy [Atlanta] 4K` that
   still reports quality `Unknown` is treated as "same quality" and can be chosen by step 2. A
   name that says `1080p` next to an Unknown quality field never matches a mirror whose field
   says 1080.
4. **No history.** Only the latest pick is stored (point 3 of the table). If the user's preferred
   family is missing for one episode, the previous good choice (for example the `Atlantic` pick
   from two episodes ago) is already gone.
5. When nothing matches at all, `links.firstOrNull { it.shouldUseLink }` (line 1648) is used.
   That is expected; the problem is that steps 1–4 fall through to it, or to a cross-family pick,
   far too often.

(The 2004 toast is a separate issue: it comes from the dead link itself. On error,
`nextMirror`/`getNextLink`, `GeneratorPlayer.kt:1724-1745`, moves to the next link in
priority order, again ignoring the family. That code is unchanged; see *Remaining*.)

## Design

All selection logic now lives in pure, generic functions in `EpisodeSelectionPreference.kt`. The
caller supplies `source`/`name`/`quality` accessors, so the tests need no Android or
`ExtractorLink`.

### Resolution: `sourceResolutions(name, quality): Set<Int>`

Resolution is what the user sees in the label:

- `NNNp`/`NNNi` tokens (144–4320) and `2K`/`4K`/`8K` in the name, as whole tokens (`x264`,
  `DDP5.1`, `[2.41GB]` and `S01E03` are not resolutions);
- plus the `quality` field when it is a real resolution. `Unknown` (400) and `Auto` (0) are not.

**Rule: two sources have the same resolution only if these sets are equal.** The empty set means
"no stated resolution". It is its own class and matches only other sources with no stated
resolution. So:

- `Vidy [Atlanta]` never falls back to `Vidy [Atlanta] 4K`, whether `4K` is in the name or in the
  quality field.
- `Atlantic [Lyra] 1080p` never falls back to `Atlantic [Silvia] 720p`. It also never falls back
  to `Atlantic [Silvia]` with no resolution: we never assume an unknown resolution is the one we
  want.
- `Vidy [Atlanta] 1080p` written in the name (quality Unknown) does match `Vidy [Chicago]` with
  quality 1080.
- A label that contradicts itself, such as name `… 720p` with quality 1080, gives `{720, 1080}`
  and matches only the same contradiction.

### Family: `sourceFamily(name, source): String?`

The family is the first word of the name, lowercased, skipping resolution tokens. If the name has
no word, the `source` field is used instead.

| Name | Family |
|---|---|
| `Vidy [Seattle]`, `Vidy [Atlanta] 720p` | `vidy` |
| `Atlantic [Lyra]`, `Atlantic [Silvia]` | `atlantic` |
| `DahmerMovies ][EAC3 5 1][x265]-iVy`, `DahmerMovies Proper][…]`, `DahmerMovies BluRay REMUX …` | `dahmermovies` |
| `Vidlink` | `vidlink` |

The `source` field is not used as the family. Some providers put a plugin-wide name there (one
value for every mirror), and others put the per-mirror name there. Neither tells you the family
reliably. A matching `source` is still used as a tie-breaker.

Trade-off: two different providers whose names start with the same word would be grouped
together. This is bounded by the same-resolution rule. The prefix-before-bracket idea was
rejected because `DahmerMovies BluRay REMUX …` has no brackets.

### Choosing a match for one remembered pick

1. **Exact**: same name (trimmed, case-insensitive) at the same resolution. A matching `source`
   field breaks ties.
2. **Mirror**: same family at the same resolution. When several fit, rank them by:
   1. matching `source`;
   2. most shared release-name traits (the existing tokeniser, e.g. `eac3`, `x265`);
   3. closest file size;
   4. quality-profile order.

### History: `SourceSelectionHistory`

- **What is kept**: up to `LIMIT = 6` picks, most recent first. Picking the same source again
  moves it to the front instead of adding a duplicate.
- **Scope**: the existing scope, account + provider (`apiName`) + series (`parentId`). DataStore
  accounts are CloudStream's user profiles, so "per profile" is covered for user profiles.
  Quality profiles ("Profile 1" in the dialog) only change link ordering and visibility, so they
  are not part of the key. Candidates are already sorted and filtered for the active quality
  profile.
- **Storage**: `"$account/player_source_history/$apiName|$parentId"` holds a
  `StoredSourceSelectionHistory(entries: List<StoredSourceSelection>)`.
- **Migration and downgrade**: the old single key is still written with the newest pick, so older
  builds keep working. If there is no history yet, the old key is read as a one-entry history.

`findPreferred(candidates)`, where candidates are the usable links in priority order:

1. For each remembered pick, newest first: its exact match, else a mirror of its family. A mirror
   of the newest pick therefore beats an exact match of an older pick, as the brief asks.
2. The previous behaviour for the newest pick (`findBest`, then `findSameQuality`), now limited to
   candidates with the same resolution. This keeps existing cases working, such as a provider
   whose shared `source` field identifies the release. It also enforces the hard rule here.
3. `null` means the player uses the first usable link (the unchanged default).

`findReady` (used for early start while links load) is unchanged and conservative. It accepts only
the newest pick's exact release (or the same release traits), now also requiring the same
resolution. A mirror that arrives early must not win over the exact source that may still be
loading. When loading finishes, `startPlayer` runs the full `findPreferred`.

## Tests

The tests are in `app/src/test/java/com/lagradost/cloudstream3/ui/player/SourceSelectionHistoryTest.kt`.

Order of the commits:

1. `refactor(player): extract …`: moves the old startup chain unchanged into
   `SourceSelectionHistory` (`LIMIT = 1`, `findBest → findSameQuality → first`).
2. `test(player): add failing tests …`: on that code, **9 of 21 new tests failed** (assertion
   failures, not compile errors). The 18 existing `EpisodeSelectionPreferenceTest` tests still
   passed:
   - never falls back to a family mirror whose name states 4K
   - stored history restores every remembered pick in order
   - history keeps the six most recent distinct picks, newest first
   - resolution stated in the name equals the same resolution in the quality field
   - second most recent pick is used when the latest family is missing
   - third most recent pick is used when the two latest are missing
   - missing 1080p mirror falls back to a 1080p mirror of the same family
   - release names of one provider form a family and the closest release wins
   - missing mirror falls back to another mirror of the same family without resolution

   The other 12 new tests pass on both versions and guard behaviour that must not regress: exact
   wins, refusing 4K/720p/unknown mirrors, newest pick wins, default order, and early-start
   conservatism.
3. `feat(player): …`: the implementation, plus 4 tests for the pure helpers, JSON round-trip with
   the DataStore mapper, and legacy migration. All 25 `SourceSelectionHistoryTest` tests and all
   18 `EpisodeSelectionPreferenceTest` tests pass, and the full `:app:testPrereleaseDebugUnitTest`
   suite passes (77 tests).

## Remaining / not done

- **Error-driven mirror switching** (`getNextLink`/`nextMirror`) still moves to the next link in
  priority order. It could reuse `findMirror` to prefer the next same-family, same-resolution
  mirror. That is left out on purpose, so this change only affects the startup choice.
- Picks are remembered only when the user presses Apply in the dialog. Automatic switches after
  an error are not recorded, which is deliberate: a fallback should not overwrite the user's
  choice.
- Not run on a device or emulator; none is available here. Coverage is unit tests plus an
  `assemblePrereleaseDebug` build.
