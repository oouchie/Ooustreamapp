# Phone touch fixes — IN PROGRESS (paused for Flutter discussion)

Device verified: Samsung Z Fold SM-F966U (arm64, Android 16) over USB (RFCY81PNYGH). USB screencap
works; wireless (192.168.1.143) screencap returns black (secure inner display).

## ROOT CAUSE (confirmed on-device)
Leanback `VerticalGridView`/`HorizontalGridView` are D-pad widgets: they DON'T touch-scroll (snap
content back to keep the "selected" item aligned), and their items are `focusableInTouchMode=true`
so the first tap only moves focus → "have to double tap".

## DONE + DEVICE-CONFIRMED (all phone-gated via DeviceUtils.isTV; TV byte-identical via layout-television/)
- **Grid touch-scroll fix**: swapped Leanback VerticalGridView → plain RecyclerView+GridLayoutManager
  on phone for Movies (vod_grid), Series (series_grid), Live TV (channels_list). New helper
  `common/TouchGridSetup.kt` (configure/setSelected/currentPosition/stripItemFocusForTouch +
  GridSpacingDecoration). layout-television/ copies keep the VerticalGridView for TV. Favorites already
  used a plain RecyclerView. CONFIRMED on device: Movies + Live TV channel lists scroll by finger.
- **Single-tap fix** (strip item focusable on phone, keep clickable): Movies/Series poster onBind,
  Live TV channel onBind, **CategoryListAdapter** (covers ALL category lists — user confirmed they all
  double-tapped), all 10 Home row onBinds, Home hero Play/More Info buttons. Movies single-tap CONFIRMED
  by user. Categories/Home just installed — NOT yet user-confirmed.
- requestFocus cursor-restore gating to isTV (7 sites); EPG chip 48dp; hero buttons 48dp. (earlier)

## STILL TODO (touch)
- Home HORIZONTAL rows (Continue Watching/Top 10/etc. = Leanback HorizontalGridView): left/right finger
  scroll likely broken (same Leanback issue). These rows are built programmatically in HomeFragment via
  ItemBridgeAdapter into HorizontalGridViews — harder than the vertical-grid swap. Vertical scroll on
  Home works (NestedScrollView).
- Search results (HorizontalGridView in fragment_search_aurora) — same.
- EPG Guide rows (VerticalGridView guide_rows) — same; has custom drag-pan already.
- Favorites scores row (HorizontalGridView) — minor.
- Verify categories + Home single-tap on device.

## Files touched (uncommitted): common/TouchGridSetup.kt (new), common/CategoryListAdapter.kt,
vod/VodFragment.kt, series/SeriesFragment.kt, livetv/LiveTvFragment.kt, home/HomeFragment.kt,
epg/guide/EpgGridFragment.kt, favorites/FavoritesFragment.kt + ViewModel, res/values/ids.xml,
res/layout/{fragment_vod,fragment_series,fragment_live_tv,fragment_home,fragment_epg_grid}.xml,
res/layout-television/{fragment_vod,fragment_series,fragment_live_tv}.xml (new).
NOTE: also still-uncommitted Kung Fu Panda / player-robustness work from an earlier session.

## DECISION PENDING: user wants to build a SEPARATE Flutter phone app (Android+iOS), keeping Kotlin for
Fire TV. If we go Flutter-for-phone, the remaining Leanback-on-touch fixes here become throwaway — only
finish them if phones will keep running this Kotlin build in the interim.
