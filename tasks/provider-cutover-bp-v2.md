# Provider cutover — flarecoral.com → bp-v2.net

**Date opened:** 2026-09-20 · **Cutover:** provider says the new URL is live "starting tomorrow"
**Decision from user:** new panel, same logins · **hard switch** (no fallback to the old host)

## Verified facts (checked this session, not assumed)

- `https://bp-v2.net/player_api.php` answers **401** with no credentials — a healthy Xtream panel
  response. DNS resolves (Cloudflare: 172.67.198.60 / 104.21.44.92).
- **Exactly one functional literal** in this repo:
  `app/src/main/res/values/strings.xml:4` → `default_server_url`. The other three `flarecoral`
  hits are prose comments in `WatchHistoryPruner.kt` and `OoustreamPlaybackFragment.kt` (triage
  notes — no behavior).
- **That literal is read at ONE place only:** `auth/LoginFragment.kt:70`, at login-button click.
  Every other consumer reads the *saved* host out of `CredentialStore`
  (`autoLogin`, `getAccountInfo`, `ContentRepository.api()`, `StreamUrlBuilder` live/vod/series,
  `SpeedTestService`, `SettingsViewModel`). ⇒ **Changing the string alone fixes new installs and
  breaks every existing one.**
- Already safe, no work needed: Continue Watching rebuilds its URL from current credentials
  (v4.2.4, `HomeFragment.kt:1899`); `BackupService` does not export the server URL; the other
  `.extra` readers (Favorites/Search/Vod) read a container extension, not a URL.
- `BlockedCategoryEntity` stores **`categoryName`** alongside `categoryId` — so parental blocks
  can be re-matched by name on the new panel. This is what makes step 4 possible.
- **Precedent to match:** the Flutter phone app (`ooustream-mobile`) already cut over to
  `bp-v2.net` today. Its migration is a silent host rewrite at the credential-load choke point
  (`lib/core/providers.dart:349`, `bootstrapProvider`). Mirror it, don't invent a second shape.

## Plan

- [x] **1. Switch the literal.** `strings.xml` `default_server_url` → `https://bp-v2.net`.
- [x] **2. Silent credential migration (P0 — without this the whole installed base dies).**
      In `CredentialStore.load()`: if the stored host ≠ the canonical one, return + persist the
      canonical host with the saved username/password. `load()` is the single choke point every
      caller already goes through, so one edit covers all 8 consumers. No re-login for anyone.
- [x] **3. Flush the ID-keyed caches once, on provider change (P1).**
      New panel ⇒ stream/category IDs are not guaranteed to match. Drop and let them refetch:
      `cached_categories`, `cached_streams`, `epg_cache`, `search_index`, `channel_scores`,
      `epg_pattern_cache`, `poster_cache`. All have `deleteAll`/`clearAll` DAO methods already.
      Keyed off a stored "last known provider host" marker so it runs exactly once.
- [x] **4. Parental-control safety (P0 by severity — child-safety, not cosmetics).**
      A blocked category ID from the old panel may map to a *different* category on the new one:
      blocks silently stop working and adult categories become visible. New `ParentalRemapState`
      arms the three sections; `ContentFilterManager.remapBlockedCategories(section)` rebuilds the
      rows by `categoryName`, which IS stable across panels. It **fetches the catalogue itself**
      rather than trusting the caller's list — several callers pass filtered or favourites-only
      lists, and a partial list reads as "these categories no longer exist" → silent deletion of
      blocks. An empty catalogue is treated as a failed fetch, never as "block nothing". Until a
      section re-matches, `filterCategories` blocks by NAME and re-asserts adult-by-name blocking
      wherever the user had already blocked adult content, so there is no unprotected window.
      Failure leaves the section armed and every later browse retries.
- [x] **5. Favorites / watch progress** — left alone by decision (full migration WITHOUT clearing
      favorites). `WatchHistoryPruner` already drops watch_progress rows absent from the live
      catalog. Residual risk accepted: a stale favorite may 404 or open different content.
- [ ] **6. Ship — HELD until 2026-09-21 at your direction.** Version bumped to 4.2.17 / 105,
      `update.json` written, both APKs built, everything committed locally. Remaining: push +
      `gh release create v4.2.17` with BOTH APKs.
- [ ] **7. Device-verify before release** on a stick (.82 / .84): existing install upgrades and
      auto-logs-in against bp-v2.net with no re-login; live + VOD both play; parental blocks
      still hide what they hid before.

## Blockers / flags for the user

- **Dirty working tree.** Branch `fix/dead-domain-and-support-contract` has uncommitted work in
  LiveTvFragment, LiveTvViewModel, MultiView (4 files), ContentFilterManager, BackupService,
  the playback fragment, 2 layouts, plus two **untracked new files**
  (`RecentChannelsRepository.kt`, `LiveResync.kt`). A release build bundles all of it — this is
  exactly how v4.2.2 accidentally shipped ~900 lines of unreviewed Guide rework. Decide before
  step 6: finish it, commit it separately, or stash it.
- **Out of scope, but must not be forgotten — the portal.** `ooustream-portal` hardcodes
  `flarecoral.com` in **7 places** per its own `tasks/reseller-panel-2026-09-21.md`, including
  customer-facing help text, the reseller customer page, and the **send-credentials email**. If
  the old host stops serving, every new customer gets emailed a dead URL. Separate repo, separate
  task.
- **Flutter app is already done** — no action there.


## Verification results (2026-09-20, AFTKRT 192.168.1.82)

Build: `assembleRelease` clean, both ABIs, versionCode 105 / 4.2.17.

**Verified on device** — a real 4.2.16 install upgraded in place (`install -r`, user data kept):
- Came up **still signed in**: no login prompt, greeting still "Good Evening, Oouchie247",
  Continue Watching rail fully populated. The silent credential migration works.
- **The app is provably hitting the new host.** The two panels answer differently:
  `bp-v2.net/player_api.php` → **401** for any request (no params AND with dummy credentials);
  `flarecoral.com/player_api.php` → **404** for both. The app logged
  `OOUSTREAM_PLAN: refreshPlan result=false failure=HTTP 401`, which is bp-v2's signature.

**NOT verified, and the reason the release is held:**
- That same 401 means **bp-v2.net did not accept the real account on 2026-09-20** — exactly what
  you'd expect from a provider saying the new URL starts "tomorrow". So playback against the new
  panel is untested.
- The parental re-match needs a real catalogue to run against, so it is untested too. It failed
  safe: the sections stayed armed, which keeps the name-based blocking active.

**Tomorrow, before releasing, on .82:** confirm auth succeeds (no 401), a live channel and a VOD
both play, and — with parental controls enabled and a category blocked — that the block survives
the switch. Then push and cut the GitHub release.

**Note on .82's current state:** it is now running 4.2.17 pointed at a panel that doesn't accept
the account yet, so it can't play anything until the provider cuts over. Home still renders from
local data. Nothing was lost.


## HELD AND REVERTED — 2026-09-20 evening

**bp-v2.net is up but had NOT been activated for the account.** Probed directly: the panel answers
`{"user_info":{"auth":0}}` (the standard Xtream "authentication failed" payload, delivered with
HTTP **401** rather than 200) for a live account, and for no-credential and dummy-credential
requests alike. A browser User-Agent makes no difference, so it is not a Cloudflare bot gate — the
panel itself is rejecting the login. Username is unchanged (`Oouchie247`). Provider says Live TV
comes up first, movies the following day.

**What was rolled back in the tree** (three things only):
- `strings.xml` `default_server_url` → back to `https://flarecoral.com`
- `app/build.gradle.kts` → back to 4.2.16 / versionCode 104
- `update.json` → restored from `c5dbab6` (the released 4.2.16 manifest)

**What was deliberately KEPT** (shipping-ready, inert until the string flips): the
`CredentialStore.load()` host rewrite, `ProviderMigration`, `ParentalRemapState`, the
`ContentFilterManager` by-name re-match, and `VodCastDao.clearAll()`. With the canonical host
equal to what installs already have, `ProviderMigration.runIfNeeded()` records its marker and
does nothing.

**Bug found and fixed while reverting:** `runIfNeeded()` treated *any* install with no marker as
"the host moved", so an upgrade whose saved login was ALREADY on the canonical host would have
pointlessly dropped its caches and re-armed the parental re-match. Now skipped unless the saved
host actually differs.

**Test sticks restored** to the released 4.2.16 (downloaded from the v4.2.16 GitHub release,
`install -r -d`, user data kept):
- **.84** — sitting on the sign-in screen, ready for a normal flarecoral login.
- **.82** — still holds a saved login that the migration rewrote to bp-v2.net, and released
  4.2.16 has no rewrite logic, so it cannot fix itself: needs **Settings → Logout → sign in**.

## To re-apply tomorrow (three lines + the usual release steps)

1. `strings.xml` → `https://bp-v2.net`
2. `app/build.gradle.kts` → 4.2.17 / 105
3. `update.json` → 4.2.17 / 105, v4.2.17 download URLs, the cutover changelog (recoverable from
   commit `182be5f`), `mandatory: true`
4. `assembleRelease`, verify on .82/.84 per step 7, then push + `gh release create v4.2.17`.

Everything in commit `182be5f` other than those three files stays as-is.
