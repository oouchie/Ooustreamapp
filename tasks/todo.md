# v4.1.0 — Full-App Cursor/UX Audit + MultiView World-Class Pass

Goal: #1 IPTV app; UI/UX at the big-streaming-app bar. Same audit as the Live TV cursor session
(v4.0.1), applied to EVERY screen, plus a MultiView deep scan.

Audit criteria per screen (the "cursor loop"):
1. Entry: visible cursor immediately, on a sensible default
2. Every list/control: cursor visible, incl. on already-"selected" items
3. Action → BACK: cursor restored to the item you acted on
4. No D-pad dead ends / invisible focus
5. Polish vs Netflix bar (feedback, labels, transitions)

## Device walkthrough (AFTKRT 192.168.1.84)
- [x] Home — FOUND: (a) BACK from playback → cursor lands invisibly on hero (saveFocusState ran at
      onDestroyView when findFocus()==null → restore defaulted to hero); (b) hero Play/More Info focused
      state too subtle (gold glow on gold button); (c) More Info UNREACHABLE by D-pad (LEFT/RIGHT always
      rotated the featured item); (d) CW row reorders after playback so position-restore points at the
      wrong card (accepted for now — cursor is visible, played item is at front)
- [x] Movies/Series/Favorites/Guide — covered by code sweep (same missing-requestFocus class)
- [x] Search/Settings/detail screens — verified OK by sweep (entry focus present)
- [ ] MultiView device test (after agent fixes land — incl. QUAD soak on Media3 1.10.0/mt8696)

## Code audits (parallel agents)
- [x] Focus sweep: P1 missing-requestFocus on VodFragment:252, SeriesFragment:231, LiveTvFragment:358,
      EpgGridFragment (initialFetchDone gate), FavoritesFragment (saved but never restored).
      Q2 styling: ALL CLEAN after v4.0.1. QuickSidebar/MultiView popups/detail screens OK.
- [x] MultiView review: 20 verified findings. P0: players keep decoding in background (no onStop);
      seed auto-fill dead (categoryId=null); onPlayerError bypasses recovery ladder (flash-loop);
      swapSlots leaves stall monitoring on wrong slots. P1: no slot logo bridge (black until first
      frame), audio-switch feedback weak, top-bar GONE = DPAD_UP dead key race, 500ms×N stagger slow,
      720p focused decode wasteful in QUAD, layout AutoTransition over SurfaceViews. P2: 8-9sp labels,
      exit-confirm friction, 60s keep-alive micro-rebuffer, EPG ticker never uses real EPG, scrim sized
      to full screen, 15s active watchdog.

## Fixes (this release)
- [x] requestFocus restores: VodFragment, SeriesFragment, LiveTvFragment (2nd site), EpgGridFragment
      onResume, FavoritesFragment (restore + ViewModel default -1)
- [x] Home: saveFocusState() moved to onPause (focus still alive there); hero white focus ring on
      Play/More Info; hero LEFT/RIGHT now traverses Play↔More Info and only rotates at the edges
- [ ] MultiView fixes 1-7 (agent in flight): onStop pause, seed categoryId resolve, error→ladder,
      label sizes, double-back exit, slot logo bridge, audio-switch border flash
- [ ] DEFERRED (documented, next pass): MultiView mode-aware tier gating + 540p QUAD cap + stagger
      tuning + real-EPG ticker + keep-alive edge check + scrim sizing + swap re-monitoring + layout
      transition exclusion; Home CW reorder-aware restore; ParentalSettings focus restore

## Verify / Release
- [ ] compileDebugKotlin + assembleDebug/Release clean
- [ ] On-device verification (Home loop, Movies/Series loop, Guide back-return, MultiView open/audio/exit)
- [ ] v4.1.0 release (bump 87, update.json, CLAUDE.md, gh release)

## Review
(at the end)
