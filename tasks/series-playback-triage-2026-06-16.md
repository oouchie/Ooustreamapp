# "Series aren't playing" — triage (2026-06-16)

Two customer `Repport.docx` exports reviewed. **Watch the rolling-log version trap** (CLAUDE.md): each
DIAGNOSTIC LOG block prints its own `App Version:` — the report header version is only what's installed
at export time.

## Report 1 — AFTTIFF43 / m7632 / user "rootzbar" (header App 4.2.0)
The series failure (The Chi S08E01, mkv) ran on the **3.6.8 (65)** session (`VLC_SWAP_IN_PLACE` →
`VLC_START_UPFRONT reason=mkv_vod_series` → `VLC_ERROR` → "video format isn't supported") — the **old
libVLC upfront-mkv path removed in v3.7.0**. Customer was stranded on the April 3.6.8 build until today,
then updated to 4.2.0 (the 15:52/15:53 sessions, no playback). The crash log NPEs at
`OoustreamPlaybackFragment.onViewCreated` (2026-05-27/05-28) date to ~3.6.8/3.7.13 (git: v3.7.13 shipped
05-28, v3.8.0 05-29), i.e. the old build. **Verdict: their failures are all old-version; 4.2.0 resolves the
VLC path. No 4.2.0 playback data captured to fully confirm, but the failing mode no longer exists.**

## Report 2 — AFTSS / mt8695 / ULTRA_LOW / user "allinone" (failing session = **4.2.0 (88)**) — ACTIONABLE
- The Newsroom S01E06 (.mkv, DTS 6ch) **played perfectly** on this exact device/session (FIRST_FRAME_RENDERED,
  24fps) → engine + mkv-series path are healthy.
- Will Trent S02E08 & S01E03 (.mkv) **failed**: `.mkv` → `UnrecognizedInputFormatException` + `loaded=0KB`
  (server returned a non-media/empty body) → `CONTAINER_EXT_RETRY from=mkv to=mp4 authoritative=false` →
  **HTTP 551** → "The server is having issues."
- **Proximate cause = server-side**: flarecoral.com returned a bad/empty body for the correct `.mkv` and 551
  for the (wrong) `.mp4`. 551 on Xtream = stream-unavailable / connection-limit. The app can't make a
  missing/blocked stream play. (Newsroom worked minutes later → transient/availability, consistent with a
  connection limit or per-title unavailability.)

### App defects this exposed (FIXED — bundle into next release, NOT yet released)
1. **Series container-retry blind-swapped mkv→mp4.** `PlayerViewModel.fetchRealVodExtension()` is VOD-only
   (`if (contentType != VOD) return null`), so series got `authoritative=false` and fell into the
   `alternateContainerUrl` mkv↔mp4 heuristic. But a series episode's extension is ALREADY authoritative
   (from `get_series_info` → `Episode.containerExtension`), so swapping it requests a non-existent `.mp4`
   → guaranteed 551, plus a misleading "server issues" message. **Fix:** in the `altUrl` when-block
   (`OoustreamPlaybackFragment.kt:~1188`), `viewModel.contentType == ContentType.SERIES -> null` before the
   `else -> alternateContainerUrl(...)`. Series now fails fast with the honest "broken or missing on your
   provider's server" message and never makes the bogus `.mp4` request. (VOD path unchanged — its
   authoritative re-probe + heuristic still apply.)
2. **551 had a generic message.** `causeChainMessage` mapped 551 via `in 500..599` → "The server is having
   issues." **Fix:** dedicated `551 ->` branch (before `in 500..599`) → "This title is unavailable right now.
   Your account may have hit its connection limit — close Ooustream on your other devices, then try again."

`:app:compileDebugKotlin` BUILD SUCCESSFUL (3 pre-existing warnings at 3285/3319/3975, unrelated). No version
bump, no GitHub release (per user). **Action for next release: include these two OoustreamPlaybackFragment
changes** (alongside the v4.2.x HomeFragment "Pick Up" extra-URL fix from the prior triage).

### Fix #3 (FIXED — added on user request, bundle into next release)
**Gapless binge pre-buffer now gated on `maxConnections > 1`.** The pre-buffer `addMediaItem()`s the next
episode while the current one is still playing — a 2nd concurrent stream — which on a 1-connection account
returns HTTP 551 (max connections) and breaks the playing episode. `OoustreamPlaybackFragment.kt:~317`:
`preBufferEnabled = (tier HIGH|MID) && userPlanManager.maxConnections.value > 1`. Added
`@Inject lateinit var userPlanManager` + a `PREBUFFER_GATE` diagnostic (`enabled/tier/maxConnections`) so
future reports show the decision. maxConnections is refreshed at launch (MainActivity.refreshPlan) and login
(AuthViewModel) on the @Singleton UserPlanManager; default 1 → unknown plan safely falls back to legacy
advance. `:app:compileDebugKotlin` BUILD SUCCESSFUL. (This is a broad systemic fix for binge series on
1-connection accounts — independent of the AFTSS report, which was ULTRA_LOW so pre-buffer was already off.)

### Considered but NOT changed (no evidence / risk)
- **Route 551 to the retry ladder** instead of fast-fail: 551 can be permanent (not-in-package) OR transient
  (max-conn). Retrying a permanent failure 5× (~29s of spinner) is worse UX; the honest message is better.
  With fix #1 the series case no longer reaches the 551 path anyway. Left deterministic.

## Report 3 — AFTTIFF43 / m7632 / "rootzbar", now ALL sessions on **4.2.0 (88)** — CONFIRMS on current code
After updating to 4.2.0, the same customer's series still fail with the exact mkv→mp4→551 pattern:
- The Chi S08E02 (id 1536245) + By Blood S05E01 (id 1539404), both `.mkv`: `UnrecognizedInputFormatException`
  → `CONTAINER_EXT_RETRY from=mkv to=mp4 authoritative=false` → **551** → "server is having issues."
- The Chi S08E02 was retried ~8× (user hammering Retry); **every attempt returned 551**.
- VODs in the same sessions PLAYED (The Bayou, Outcome, Coraline → FIRST_FRAME_RENDERED ×3).
**Conclusion: with every retry a 551 across multiple titles while VODs play → the account is hitting its
max-simultaneous-connection limit (HTTP 551).** This is server/account-side, not an app decode bug. Fix #1
removes the self-inflicted 2nd connection (mkv→mp4) and fails fast honestly; Fix #2 now tells the user it's a
connection limit; Fix #3 stops the binge pre-buffer 2nd connection. None can make the provider grant a
connection it's refusing — that's an account/provider matter (close other devices / raise the plan's
connection count).

## Connection-lifecycle audit (rootzbar has a 2-connection account, still 551s)
With 2 allowed connections and 551 on series, either a 2nd device holds a slot or the APP holds ≥2 concurrent
streaming connections. Audited the streaming-connection lifecycle (verified in code):
- **VERIFIED OK:** normal back-out (`onDestroyView` stop+clearVideoSurface+release, synchronous), all 3
  `rebuildPlayerWith*` (stop+safeReleasePlayer before new), live preview→fullscreen (`stopPreview()` releases
  before the fragment commit). No leak on these.
- **APP self-inflicted 2nd connection — the relevant one for rootzbar (NOW FIXED by Fix #1):** the series
  `mkv→mp4` container-ext retry opened a SECOND streaming GET while the first `.mkv` slot was still counted
  server-side → 2 slots used → 551. Fix #1 makes series fail fast (no swap) → 1 connection per attempt.
- **VERIFIED latent overlap — FIXED (Fix #4).** Watch Next (`OoustreamPlaybackFragment.kt:~756`) was the ONLY
  in-player navigation that spawns a second player fragment (series-complete + binge reuse the SAME player via
  setMediaItem, no overlap). It did `supportFragmentManager.replace().commit()` WITHOUT releasing the current
  player — the new fragment's player opened a connection before the old fragment's `onDestroyView` released
  (async transaction) → a momentary 2-connection overlap that 551s on a limited account. **Fix:** added
  `try { player?.stop() } catch(_) {}` before the transaction — ends the current MediaSource (closes the
  OkHttp socket / frees the slot) so the next player can't overlap; onDestroyView still does the full release.
  Mirrors LiveTV's `stopPreview()`-before-commit. `:app:compileDebugKotlin` BUILD SUCCESSFUL. (Not rootzbar's
  trigger — their logs are direct series plays — but a real latent bug for any limited-connection account that
  taps Watch Next.)
- **Generic retry ladder (`:1234`)** lacks `stop()` before `prepare()` (the stall detector has it) — but it
  re-prepares the SAME single player = 1 connection, so it is NOT a 2-concurrent overlap. Marginal; left as-is
  to avoid speculative churn.
- **Rapid user "Retry" on 551:** Xtream holds a dropped slot for a server-side timeout, so hammering Retry
  faster than the slot frees keeps 551ing regardless of app code. Mitigated by Fix #1 (no doubling) + Fix #2
  (honest "close other devices / wait a moment" message that discourages frantic retrying).

**Reconciliation:** for rootzbar (2 conn, LOW tier → no binge pre-buffer), the app's ONLY self-inflicted extra
connection on series was the `mkv→mp4` retry, which Fix #1 removes. If 551 persists with the app using a single
connection, the 2nd slot is genuinely occupied — a real 2nd device, or the provider's slot-release lag on rapid
retries. That part is account/provider-side, not app-fixable.

## DEFINITIVE root cause — proven by direct fetch (2026-06-17, account "Oouchie", on-device .82 + Mac ffprobe/curl)
Fetched the actual stream endpoints from flarecoral with a real account (User-Agent = the app's), account at
`active=3 max=4` (NOT maxed — rules out connection limit):
- **Failing series** (The Chi S07E01 `series/.../1210734.mkv`, By Blood `1539404`, Power Book III `1232082`):
  **HTTP 200, `content-type: text/html`, EMPTY body — no redirect.** ExoPlayer is handed 0 bytes of HTML →
  `UnrecognizedInputFormatException` (exactly the `loaded=0KB` in the reports). The `.mp4` variant → HTTP 551.
- **Working titles** (The C-Team series `1463484`, Michael VOD `1538153`): **HTTP 302 → CDN**
  `http://68.235.41.x/live/play/<token>/<id>`; following it yields real Matroska bytes (`1a45dfa3 … matroska`).
**Conclusion: the failing titles are LISTED by flarecoral (get_series_info returns them, so they show in the
app) but NOT SERVED — the stream endpoint returns an empty HTML page instead of a 302-to-CDN.** This is 100%
provider-side; no app change can play an empty response. The v4.2.1 series message ("broken or missing on your
provider's server") is therefore accurate. Affected: a band of premium series (The Chi all seasons, Power Book
III/IV, By Blood, Will Trent). ACTION: provider/flarecoral must actually host those files (or remove the dead
listings). Optional app polish: detect `content-type: text/html` on a stream response → show "not available
from your provider" instantly (faster + clearer than the generic unrecognized-format path).

## Catalog scan (2026-06-17, account "Oouchie") — quantifies the provider damage
`tasks`-side python probe of the live series catalog (sample 4 series/category, probe first episode's
stream endpoint, classify 302→CDN = served vs 200 text/html = dead). Result:
- **19 categories, 7,995 series listed. Overall DEAD RATE ≈ 21% (15/72 probed series return an empty page).**
- Worst: Wrestling (3/4 dead), Crime/News/Soap (2/4). Fully served: Drama, Comedy, Reality, Documentary,
  Family, Western, War & Politics. Extrapolated ≈ ~1,600 "listed-but-not-served" series.
- Confirms the customer reports: The Chi (all seasons), Power Book III/IV, By Blood, Will Trent are dead.
This is hard evidence for the flarecoral conversation: ~1 in 5 listed series serves nothing.

## App polish shipped to code (Fix #5) — detect & message provider dead listings
`OoustreamPlaybackFragment`: on an `UnrecognizedInputFormatException` for VOD/SERIES, a new
`probeStreamContentType()` (shared OkHttp client, follows redirects, 8s call-timeout, range 0-1, self-closing)
checks the stream's final Content-Type. If `text/html` → it's a provider dead listing (no media), so show
"This title isn't available from your provider right now. Try another title, or let your provider know." and
log a new `PROVIDER_DEAD_LISTING` diagnostic event (so future debug reports show this unambiguously instead of
a bare UnrecognizedInputFormat). Falls through to the existing container-ext retry when the probe fails or the
type isn't html (no regression for real container mismatches). `:app:compileDebugKotlin` BUILD SUCCESSFUL.
NOTE: this only makes the *message* honest/precise — it cannot make a 0-byte provider response play. The
streams can only "work" once flarecoral actually serves those files.

## What to tell the customers
- rootzbar: update to 4.2.0 (you were on the April 3.6.8 build); the mkv-series failure is gone.
- allinone: those specific Will Trent episodes are erroring on the provider (flarecoral.com returned 551 /
  empty body); other series (The Newsroom) play fine on your device. If it's widespread, it's likely your
  account's connection limit — close Ooustream on other devices. The next app update gives an accurate
  message and stops the misleading mp4 retry.
