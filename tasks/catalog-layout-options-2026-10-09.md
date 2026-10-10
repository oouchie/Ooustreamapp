# What the bp-v2.net panel exposes for Movies & Series — layout options (2026-10-09)

Probed live with the Oouchie247 account (login pulled from the portal's `customers` table, not
stored here). `auth=1`, `max_connections=4`, `server_info.timezone=UTC`. Full catalogs saved to the
session scratchpad only.

The portal (`ooustream-portal/src/lib/panel.ts`) talks to a DIFFERENT API — the reseller
**management** API on `bestpanel.xyz` (lines, renewals, credits, bouquets). It has nothing about
content layout; the only content-adjacent piece is `GET /packages/{id}/bouquets`, which returns
bouquet names + `movie_count` / `series_count` per package. Not useful for in-app layout.

## Shape of the catalog

| | Movies | Series |
|---|---|---|
| items | 29,331 | 7,942 |
| categories | 36 | 33 |
| nesting (`parent_id`) | none — all `0` | none — all `0` |
| items in >1 category | 0 | 0 |

**Categories are by streaming service, not genre.** Movies: 2026 Releases, 4K Movies, then
Netflix / Prime / Disney+ / HBO Max / Apple TV+ / Hulu / Paramount / Peacock / Crunchyroll / Starz /
AMC+ / MGM+ / Discovery+ / NOW / Sky Go / BritBox / Crave / Stan / BINGE / Foxtel / Viaplay / Acorn /
Shudder / Curiosity / YouTube Premium / fuboTV / Tubi / Philo / Plex / Pluto / Roku / Xumo /
**Unlabeled Movies (5,897 — 20%)** / **18+ | Nutflix 🔞 (692)**. Series: the same service list
(+ Hayu, no 4K/2026/18+) and **Unlabeled Series (1,107 — 14%)**.

Skewed: Prime 9,074 + Unlabeled 5,897 + Netflix 3,073 = 61% of movies. Twelve movie categories
have under 100 titles; 6 have ≤ 8 (Viaplay 1, Sky Go 1, YouTube Premium 1, Acorn 2, Discovery+ 2,
Curiosity 8). A sidebar of 36 near-empty service names is the weakest part of the current layout.

"4K Movies" = exactly the 567 titles whose name starts with `4K ` (both ways, no stragglers).
"2026 Releases" = 554 of the 784 titles ending `(2026)`.

## Fields — bulk list vs detail

**`get_vod_streams` (bulk movies)** carries ONLY: `num, name, stream_id, stream_icon, rating (0–10
int, 95%), rating_5based, added, category_id, container_extension, tmdb_id (98%), is_adult (always
"0" — useless, even inside 18+), custom_sid/direct_source (empty)`.
**No genre, year, plot, runtime, cast, backdrop or trailer in the bulk list.** Year is only in the
title: `Name (YYYY)` on 97.6% of movies.

**`get_vod_info` (per movie)** adds: `genre, plot, cast, director, releasedate, duration /
duration_secs, backdrop + backdrop_path[], youtube_trailer, tmdb_id, rating (decimal)`.
The app already models all of these (`VodInfo`). `VodCastBackfillWorker` already calls this for
every movie over time (150 per 15-min run, ~200 ms apart) but `vod_cast` stores only cast/director.

**`get_series` (bulk series) is rich:** `genre (98%), plot (99%), cast (87%), releaseDate (99%),
episode_run_time (97%), backdrop_path (98%), youtube_trailer (66%), rating, last_modified, tmdb_id
(100%)`. Everything needed for genre/decade/runtime layouts is in ONE request. `director` is empty.

**`get_series_info`** adds per-season `name, overview, air_date, episode_count, cover` and per-episode
`info.movie_image` (still), `plot`, `rating`, `duration_secs`. Episode titles are still the
provider's `Show - S01E01 - Episode 1` pattern (so `EpisodeNameResolver` is still needed — but see
TMDB note below).

## Things the probe exposed that are NOT layout (fix regardless)

1. **`tmdb_id` ≠ `tmdb`.** The panel sends `tmdb_id`; `VodStream`/`Series` map `@SerializedName("tmdb")`,
   so `tmdbId` is null on every item. Consequences today: `PosterUrlResolver`'s TMDB fallback never
   runs (3% of movies have no `stream_icon` → blank posters), and `EpisodeNameResolver` searches
   TMDB by name+year when a direct `tv/{tmdb_id}` lookup is available for 100% of series (fewer
   wrong-show matches). One-line fix each: add `alternate = ["tmdb_id"]`.
2. **"Recently Added" is meaningless right now.** 27,862 of 29,331 movies carry `added =
   2026-09-21` (migration day); series 6,753 of 7,942 `last_modified` the same day. The app's
   Recently Added row sorts by `added` → effectively arbitrary order within that day. Genuine
   additions since: ~1,030 movies on 2026-10-07, 187 on 09-28, 166 on 10-02. A "New this week"
   row should use a **7-day window**, not "newest first", and should hide itself when the window
   is empty.
3. `rating` on the bulk movie list is an **integer** (0–10); the decimal is only in `get_vod_info`.
   Trending scoring already handles this. 1,233 movies have rating 0 (unrated, not bad).
4. Containers: 26,614 mkv / 2,709 mp4 / 8 avi. 2,721 posters missing (716 no icon + ~2k not TMDB-hosted? — 28,615 are `image.tmdb.org`).

## Layout options, ranked by (value ÷ cost)

### A. Series — genre + decade browsing, zero new network cost  ★ do first
Bulk series has genre on 98%. Top tokens: Drama 2,652 · Comedy 2,289 · Animation 2,076 ·
Documentary 1,754 · Sci-Fi & Fantasy 1,631 · Action & Adventure 1,371 · Reality 1,250 · Crime 1,202
· Mystery 742 · Family 409 · Kids 333 · War & Politics 155 · Talk 99 · Western 39. Decades: 2020s
4,393 · 2010s 2,440 · 2000s 638 · 1990s 237 · 1980s 131.
- Sidebar: a **"Browse by" group** (Genre ▸ 14 entries, Decade ▸ 6, "Short episodes ≤30 min",
  "With trailer") ABOVE the service list; services collapse under "By service ▸".
- Virtual categories like the existing `__recently_added__` — same ViewModel pattern, filter the
  already-fetched bulk list. No API change.
- Hide a virtual category when it filters to < 5 items.

### B. Movies — year/decade rows from the title, zero network cost
97.6% of movie names end `(YYYY)`; `VodViewModel.parseYear` already exists. Decades: 2020s 9,329 ·
2010s 8,764 · 2000s 3,622 · 1990s 2,246 · 1980s 1,667 · 1970s 1,000 · 1960s 676 · 1950s 537 ·
1940s 441 · 1930s 286. "Classics (pre-1970)" alone is ~2,000 titles.
- Virtual categories: **This year / Last year / 2010s / 2000s / 90s / 80s / Classics**.
- "4K Movies" stays (real category). "New Releases" (current/last year) stays.

### C. Movies — genre rows, needs the detail cache  (medium)
Genre only comes from `get_vod_info`. The cheapest honest route is the worker that already runs:
widen `vod_cast` (or a new `vod_meta` table) to store `genre, year, duration_secs, backdrop,
trailer` from the same `get_vod_info` call the backfill already makes. Cost: a DB migration + a
few columns; **no extra requests**. Coverage grows at ~14,400 movies/day at the current cadence
(150 per 15 min) → full catalog in ~2 days of the app being open; adult category should be skipped
by the worker. Then: "Browse by genre" on Movies (same UI as A), genre chips on the poster card
focus line, and runtime ("Under 90 min") filters. Until coverage is high, show genre rows only
when ≥ 20 matches exist.
- Alternative: TMDB `discover`/`movie/{tmdb_id}` by `tmdb_id` — faster but adds a second
  dependency and quota on 98% of 29k titles. Not recommended while the free path exists.

### D. Collapse the long tail of service categories  (small, pure UI)
Show services with ≥ 50 titles as top-level; fold the rest under "More services ▸" (movies: 16 of
36 would fold; series: 15 of 33). Counts are known after the bulk fetch (already cached). This
alone cuts the Movies sidebar from 38 rows to ~22.

### E. "Unlabeled" buckets — relabel, don't hide  (small)
5,897 movies / 1,107 series with no service tag — they're normal titles (samples: "Joy Ride (2021)",
"Scooby-Doo! Moon Monster Madness", "Blade: The Series"). Rename to **"All Other Movies / Series"**
and push to the bottom of the service list. Genre/decade browsing (A/B/C) is what actually makes
these 7,000 titles findable.

### F. Series detail — season art + overviews  (small)
`get_series_info.seasons[]` has per-season `cover`, `overview`, `air_date`, `episode_count`, and
episodes have stills + plots + runtimes. The TV series screen already uses stills via
`EpisodeNameResolver`; season overview text and season posters in the season rail are new and
free.

### G. Movie detail — runtime badge, release date, backdrop  (tiny)
`duration` ("01:37:00"), `releasedate`, `backdrop` already in `VodInfo`. The TV movie page shows
some; verify runtime + year are rendered.

### Not possible from this panel
- Provider-side genre/collection categories (none exist; all `parent_id=0`).
- Sub-categories / nesting.
- A reliable "added" date for the back catalog (migration flattened it).
- `is_adult` flag (always 0) — name regex stays the adult signal.

## Recommended order
1. `tmdb_id` alternate-name fix (both models) — unblocks poster fallback + direct TMDB episode lookup.
2. A + B + D + E together as one "Browse by" release on Movies & Series (all client-side).
3. Fix "Recently Added" to a 7-day window with self-hide.
4. C (widen the backfill cache) as the follow-up that brings genre to Movies.
5. F/G polish.

Everything above is **inferred from the live API on 2026-10-09**; nothing is implemented. The
adult category must be excluded from every virtual category (route them through
`AdultContentGuard` like Home — the Movies/Series screens currently only honor parental blocks).
