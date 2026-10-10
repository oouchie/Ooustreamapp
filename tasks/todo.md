# Catalog "Browse by" release — plan (2026-10-09)

Source: `tasks/catalog-layout-options-2026-10-09.md` (items 1–3 = this release; 4–5 follow-up).

## Scope
- [x] 1. `tmdb_id` alternate name on `VodStream` + `Series` (panel sends `tmdb_id`; app read `tmdb` → always null).
      Feed a known series TMDB id into `EpisodeNameResolver` (skip the name search when we have it).
- [x] 2. `CatalogCache` — ONE in-memory copy of the bulk movie / series lists (10-min TTL, single-flight,
      cleared on expiry + on "Update Playlist"). Today every full-list consumer (Home hero/trending, BYW rows,
      Movies "Recently Added", Series "Recently Added") re-downloads 10 MB / 6.5 MB.
- [x] 3. `CatalogBrowse` — index builder: series genres (≥5 titles), decades (movies from `(YYYY)` in the
      title, series from `releaseDate`; "Classics" fold before 1970 / 1990), per-category counts, recent count.
- [x] 4. Sidebar groups in `CategoryListAdapter` / `CategoryItem`: HEADER rows (non-focusable), GROUP rows
      (▸/▾ toggle), indented children. Fold services with < 50 titles under "More services". Rename
      "Unlabeled Movies/Series" → "All Other Movies/Series" and move to the end of the main list. Counts on
      every service row.
- [x] 5. Movies + Series ViewModels: browse index flow, expanded-group state, virtual-category selection
      (genre / decade / classics) filtered through parental blocks AND adult exclusion (adult titles only
      ever appear inside their own category).
- [x] 6. "Recently Added" → true 14-day window on `added` / `last_modified`; row hides when the window is
      empty (unless it is the selected row).
- [x] 7. Home + RecommendationEngine read the bulk lists from `CatalogCache` (no behavior change, fewer downloads).
- [x] 8. Build (`compileDebugKotlin` + `assembleRelease`), device-walk Movies + Series sidebars on .82:
      groups expand/collapse by D-pad, focus stays sane, genre/decade grids populate, adult titles absent from
      every virtual category, Recently Added shows the 2026-10-07 drop.
- [x] 9. CLAUDE.md + release notes; version bump 5.1.0 (111); `update.json`; GitHub release.

## Decisions
- Recently Added window = **14 days** (plan said 7; with the catalog's lumpy drops a 7-day row would blink
  in and out weekly). Label stays "Recently Added".
- Virtual categories exclude adult content by category NAME (always available from the loaded category
  list) plus `AdultContentGuard` ids when they are known — no network wait, no unprotected window.
- Groups default collapsed; a group auto-expands when its child is the selected category (back-nav restore).
- Sidebar search (header search) flattens the list: no headers/groups while a filter is typed.
- NOT in this release: movie genre rows (needs the widened `get_vod_info` cache — item C), season art.

## Review (2026-10-09)
Built + device-walked on .82 (AFTKRT, release build) via uiautomator-driven D-pad scripts: Movies and
Series sidebars render the BROWSE BY / SERVICES blocks, groups expand/collapse, decade + genre grids
populate (1990s newest-first; Crime by rating), Recently Added = the 2026-10-07 drop, no adult titles
in view, "All Other Movies 5897", "More services (8)" (8, not the 16 estimated — the <50 floor folds
fewer than the <100 count in the survey). Zero FATAL/ANR in logcat across the walks.
Not verified: name-based adult exclusion (stick has parental ON), Ooustick, phone, the TMDB-id
episode-name path (needs a series open while the bulk list is cached).
Found while verifying: the `ooustream://vod` deep link does not open the Movies screen (it needs an
id); Home's genre rows are service rows on this panel. Neither changed.
