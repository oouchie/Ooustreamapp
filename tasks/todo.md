# EPG Screen Redesign — "Now Playing" header + toolbar + upgraded grid

Reference: `/Users/oouchiebates/Downloads/IMG_8798.JPG` (TiviMate-style guide).
Goal: make the EPG guide resemble the reference, plus 4 confirmed upgrades.
Target = Fire TV / 10-foot D-pad (Kotlin app is Fire-TV-only; phone is the separate Flutter app).

## Confirmed scope (from user)
- **Header preview**: TIERED live preview — auto-play focused channel video on capable
  devices; static logo + now/next panel on low-RAM sticks (mt8695 / Ooustick).
- **Toolbar**: Global Search · Full-screen EPG (toggle header off) · Hours (2h/4h/8h) ·
  Now (jump-to-now) · date label. **No Record, no Edit EPG.**
- **Upgrades (all 4)**:
  1. Genre color-coding of program cells (sports/movies/news/kids…) via ChannelNameParser.
  2. Now/next live progress bar inside the currently-airing cell in EVERY row.
  3. Inline favorite heart per channel (D-pad + touch toggle), Favorites-first ordering.
  4. Selectable + persisted time window (2h / 4h / 8h).

## Architecture decisions
- Keep the existing Canvas-lane engine (GuideProgramLaneView / GuideTimelineController /
  GuideTimeHeaderView / GuideRowAdapter) — right 60fps approach. Extend, don't replace.
- `windowDurationMs` becomes mutable, driven by the Hours selector (persisted in prefs).
- New header is a sibling above the toolbar + grid; collapsible for "Full screen EPG".
- Tiered preview reuses LivePreviewManager (muted, audio-isolated) into a header surface.

## Plan
- [x] R: research APIs (preview player, device tier, favorites, EPG now/next + genre, nav/resources)
- [x] 1. GuideTimelineController: mutable window duration (setWindowDuration), keep focus/now logic
- [x] 2. GuideProgramLaneView: genre color accent + live-progress in current cell + 2-line cell (title + time range)
- [x] 3. Header: channel logo, title, now-playing (time + progress), up-next, tiered live preview surface (in fragment XML)
- [x] 4. Toolbar row: Global Search, Full-screen toggle, Hours selector, Now, date — D-pad pills
- [x] 5. item_guide_row.xml + GuideRowAdapter: favorite heart, channel number/name
- [x] 6. fragment_epg_grid.xml: restructure into header + toolbar + ruler + grid + hints
- [x] 7. EpgGridViewModel: now/next via row lanes, favorite toggle/observe, genre classification, Favorites-first ordering
- [x] 8. EpgGridFragment: wire tiered header preview, toolbar actions, hours persistence, focus flow, full-screen toggle
- [x] 9. Strings + drawables (ic_heart_outline, bg_guide_pill)
- [x] V1: assembleDebug clean (44s, only pre-existing warnings)
- [x] V2: adversarial review (5-agent research + 3-dimension review, each finding verified) — 3 confirmed of 19
- [x] V3: address confirmed findings + rebuild clean (17s)

## Review
**Build:** `assembleDebug` clean (Kotlin + resources), no new warnings. NOT yet device-verified.

**Proactive perf fix (pre-review):** `GuideProgramLaneView.onDraw` formatted a SimpleDateFormat time-range
string per cell per frame (GC churn on 1GB). Now precomputed into `rangeLabels` on the `programs` setter.

**Adversarial review — 3 confirmed of 19 candidates (16 verified non-issues), all fixed:**
1. (medium) `headerVisible` survived back-stack instance but view re-inflated VISIBLE → preview silently
   suppressed + toggle inverted. Fix: reset `headerVisible = true` in onViewCreated.
2. (low) Back-from-playback focus landed on row 0, not the tuned channel. Fix: `pendingRestoreStreamId`
   set in tuneToChannel; restored in the submitList COMMIT callback (race-free, per v3.7.13 lesson),
   position-then-requestFocus. onResume simplified to the no-recreation case.
3. (low) Header labeled a future program "now playing" during sub-5-min real-EPG holes at NOW. Fix:
   `current` = only a program containing now; gap surfaces under "Up next", now slots blank.

**Deliberate deviations from the reference:** NOW line kept GOLD (app-wide convention) not red; Record +
Edit EPG dropped (Xtream has no DVR/EPG-edit); favorite toggle = hold-OK (no focus-stealing heart column).

**Outstanding (not blockers):**
- NOT device-verified on Fire TV (AFTKRT 192.168.1.84). Recommend sideload + walkthrough before release.
- SurfaceView preview corners won't clip to the rounded frame (SurfaceView ignores clipToOutline) —
  same as the existing LiveTv preview; cosmetic. Could switch PlayerView to texture_view if it bothers.
- No version bump / CLAUDE.md version-history entry yet (release-time action; user hasn't asked to ship).

---
## ARCHIVED — Phone touch fixes (paused; superseded by Flutter phone-app decision)
NOTE: the files below were described as uncommitted in a prior session but are NOT present at
current HEAD (EpgGridFragment.kt / fragment_epg_grid.xml are the clean v4.x versions). Kept for
history only. Kotlin app is now Fire-TV-only — these phone-touch items are out of scope.

- Grid touch-scroll fix (Leanback VerticalGridView → RecyclerView+GridLayoutManager on phone),
  common/TouchGridSetup.kt, single-tap focus stripping, requestFocus cursor-restore gating to isTV.
- STILL-TODO (touch): Home horizontal rows, Search results, EPG guide rows, Favorites scores row.
- DECISION (now resolved): separate Flutter phone app (Android+iOS), Kotlin stays Fire TV.
