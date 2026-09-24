# Series episodes screen + Watch Next card — redesign plan (2026-09-24)

## Already shipped in the working tree (uncommitted, device-verified on .84, release build)
- [x] Watch Next card: white focus ring + 1.06 scale on the focused button (was: no focus state at all)
- [x] BACK on the card = Cancel (stay on episode). Was: fell through and exited to the episode list
- [x] Glue BACK path closes whichever modal is up (was track-picker only)

## Problems with the current screen (seen on device)
- Primary job ("carry on where I left off") has no button — user must find the episode row.
- Everything is the same weight: 24sp title, 100x150 poster, 15sp rows — too small at 10 ft.
- Provider titles repeat the series name: "(Un)Well S01E01" — never run through MediaTitleFormatter.
- Episode row focus is nearly invisible: bg colour set on the outer frame, inner card_bg is opaque.
- RecyclerView fully expanded inside a NestedScrollView → every episode card inflated, no recycling
  (violates PERFORMANCE_AGENT "60fps rules"; long seasons = main-thread inflate spike).
- Season tabs are raw TextViews with hardcoded px padding and no focus ring.

## Design plan
Subject: a viewer on the couch, remote in hand, picking a series back up. Primary job: one press
to continue. Secondary: browse a season.

Colour
- Base  #0A0A0A (brand background)   - Surface #15171C (row on focus / panels)
- Text  #F4F5F7 / secondary #A3A9B4  - Gold #FFC107 = focus + progress (matches Continue Watching)
- Watched = text dimmed to 45%, no green checkbox icon (the stock checkbox drawable is the ugliest
  thing on screen)

Type
- Space Grotesk 700 (brand face, bundled in res/font — Fire TV has no GMS font provider) for the
  series title and the episode numerals only. Roboto (system) for everything else.
- Scale (TV): title 44sp, action 20sp, numeral 40sp, row title 20sp, row meta 15sp, plot 15sp.

Layout — two columns, left-aligned, no scrolling page (list scrolls itself)
```
+--------------------------------------------------------------------------+
| (backdrop, right-weighted, fades to #0A0A0A on the left third)           |
|                                                                          |
|  (Un)Well                          |  1   Plant extracts         55 min   |
|  2020 · Documentary · ★ 3.0        |      Once fringe but now at...     |
|                                    |  ─────────────────────────────────  |
|  [ ▶ Resume E3 · 23 min left ]     |  2   Sexual healing         48 min  |
|  [   Start over             ]      |  ━━━━━━━━━━━━━━━━━━━──────────────  |
|                                    |  3   Liquid gold            52 min  |
|  Season 1                          |  ...  (VerticalGridView, recycles) |
|  Season 2                          |                                    |
|  Season 3                          |                                    |
|                                    |                                    |
|  Plot, max 4 lines, 60ch measure   |                                    |
+--------------------------------------------------------------------------+
```
- Left column (≈38%): identity + the one primary action + seasons as a vertical list (D-pad UP/DOWN
  within, RIGHT enters episodes). Primary action text is computed: "Resume E3 · 23 min left" /
  "Play E4" (next unwatched) / "Play E1".
- Right column (≈62%): episodes as a Leanback VerticalGridView. Each row: big Space Grotesk numeral
  (episodes ARE a sequence, so the numeral carries information), cleaned title, runtime, one-line
  plot that expands to 3 lines only on the focused row. Progress = 3dp gold bar under the row.
- Focus: gold 2dp ring + surface fill on the row; no scale on rows (scale jitters text at 10 ft).
- The one bold thing: the numerals. Everything else stays quiet.

Watch Next card (same language)
```
                                   +-------------------------------+
                                   | [thumb 16:9]  4               |
                                   |               Fasting         |
                                   | [▶ Play now ▓▓▓▓▓░░░]  [Cancel]|
                                   +-------------------------------+
```
- Next-episode thumbnail + numeral + cleaned title. The countdown is a gold fill draining across the
  Play button instead of the "Next episode in 10s" line. Remove the full-screen 50% black scrim
  (it dims the credits for no reason).

## Scope guard
- TV layout only: new `fragment_series_detail_tv.xml`, chosen at runtime by `DeviceUtils.isTV()`
  (CLAUDE.md rule 3 — never the -television qualifier; Ooustick must get it too). Phone keeps the
  current layout untouched.
- No ViewModel/data changes except exposing "resume target" (derived from existing watch progress).

## Verify
- [x] assembleRelease clean
- [x] .84 (Fire TV, release build over the top): action → seasons → episodes → back; LEFT from
      episodes returns to the browsed season; UP from Season 1 returns to Play (was trapped)
- [x] Resume target: RESUME / NEXT (incl. Up Next placeholder) / START all seen on device
- [x] Watch Next card: appears at 19.5s left, drain, focus ring unclipped, real name, OK, Back
- [x] Real episode names from TMDB (13 Reasons Why "Tape 1, Side A", (Un)Well "Essential Oils")
- [ ] Ooustick walk — NOT done (no Ooustick on the network 2026-09-24)
- [ ] .82 — offline all night; background watcher installs the build when it comes online
- [ ] Phone layout path — code untouched, but not re-walked on a phone

## Review (2026-09-24)
Added beyond the original plan, at the user's request during the build:
- Real episode names: new `data/repository/EpisodeNameResolver.kt` — TMDB search by name+year (exact
  normalized name, year ±1, else no name), season endpoint, 30-day SharedPreferences cache. Used by
  the series screen and the Watch Next card (PlayerViewModel, 2s cap so it can't delay an advance).
- Watch Next timing: card at 20s left (was 15s), countdown 15s (was 10s) → advance still ~5s before end.
Bugs found while building (fixed): action buttons clipped by a 280dp minWidth in a ~274dp column;
Up Next placeholder row (position 0, duration 1, 6%) read as "Resume, 1 min left"; Leanback
`focusOutFront=false` default trapped focus in the season list; drained-fill animation must never
drive the auto-advance (ValueAnimator obeys the system animator scale — CountDownTimer is the clock).
