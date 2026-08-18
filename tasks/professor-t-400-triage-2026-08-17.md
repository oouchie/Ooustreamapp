# "Professor T" HTTP 400 — triage (2026-08-17)

**Verdict: provider-side dead listing. Season 5 only. No app defect caused it.**
Proven end-to-end outside the app with the real account, not inferred from a log.

---

## Symptom

User tried to play *Professor T (2021)* on .82 (AFTKRT, release **4.2.14 / versionCode 102**) and got an
error containing **400**.

That string can only come from one place — `OoustreamPlaybackFragment.causeChainMessage`'s final
`else` branch (`"Server returned error $code. Try again or contact your provider."`). There was no
`400` branch. So the panel genuinely answered the stream GET with 400.

## What was measured

### On device (adb logcat, .82)
```
13:03:38.995 I/SeriesDetailVM  loadSeriesInfo called with seriesId=43675
13:03:39.117 I/SeriesDetailVM  API success: name=Professor T (2021), episodeMapKeys=[1,2,3,4,5]
13:03:41.420 I/SeriesDetailVM  selectSeason(5): found 6 episodes, first: id=2056173 …
13:03:42.875 I/ExoPlayerImpl   Init [AndroidXMedia3/1.10.0] [karat, AFTKRT, Amazon, 30]
13:03:46.566 E/ExoPlayerImplInternal  Caused by: InvalidResponseCodeException: Response code: 400
                                       at OkHttpDataSource.open
                                       at ProgressiveMediaPeriod$ExtractingLoadable.load
```
Two distinct failures (13:03:46.566, 13:04:01.239), each logged twice — once by
`ExoPlayerImplInternal`, once by `AudioLogger`/`OOUSTREAM_AUDIO` — hence `grep -c "Response code: 400"`
returns **4** for **2** attempts. Don't misread that count.

### Against the provider (curl, real account)
```
GET https://flarecoral.com/series/<u>/<p>/2056173.mkv
  → HTTP/2 302  location: http://74.119.149.77:80/live/play/<base64 token>/2056173
  → HTTP/1.1 400 Bad Request   Content-Type: video/x-matroska   (zero bytes)
```

**Full sweep of all 30 episodes** (`Range: bytes=0-1`):

| Season | Episode ids | Result |
|---|---|---|
| S1 | 2045758–2045763 | **206** — `Content-Range: bytes 0-1/2735085614`, first bytes `\x1aE` (valid EBML) |
| S2 | 2045764–2045769 | **206** |
| S3 | 2045770–2045775 | **206** |
| S4 | 1887656–1887662 | **206** |
| **S5** | **2056173–2056178** | **400 — all six, zero bytes** |

`get_series_info` returns `container_extension: "mkv"` and a clean numeric `id` for **all 30**
episodes. The app builds a perfectly well-formed URL.

## Hypotheses ruled out (each by direct test)

| Ruled out | How |
|---|---|
| Blank `container_extension` → trailing-dot URL | All 30 episodes return `"mkv"` from the API |
| `episode.id?.toIntOrNull() ?: 0` → `/0.mkv` | Real ids are clean 7-digit numerics. Also: a valid-but-missing id gets 404/551, both of which have their own message branches, so neither could ever print "400" |
| Credential percent-encoding | Username alphanumeric, password all-numeric; and S1–S4 stream on the *same* credentials |
| Request shape | 400 with Range, without Range, `bytes=0-`, ExoPlayer UA, VLC UA, and no UA |
| Codec / decoder | Never reached — failure is at `OkHttpDataSource.open`, no media byte arrives |
| Connection limit (551 class) | 551 has its own branch; this is 400, and S1–S4 succeeded in the same window |
| Systemic provider ingest failure | 0/10 of the most recently modified series have a dead newest episode |

## Timeline evidence

`last_modified` for series 43675 = **1786634611 → 2026-08-13 15:23 UTC**. Season 5 was added four
days before this triage and has never been servable. Not transient; it will not self-heal.

Account state at triage: `max_connections: 4`, `active_cons: 4`, `exp_date: 1787488147` →
**2026-08-23 12:29 UTC**.

## New failure signature — add to the triage reference

This is the **same class** as the v4.2.2 "dead listing" but a **different signature**:

| | v4.2.2 signature | this one |
|---|---|---|
| Status | HTTP 200 | **HTTP 400** |
| Content-Type | `text/html` | **`video/x-matroska`** |
| Body | empty | empty |
| Detected by | `probeStreamContentType()` after `UnrecognizedInputFormatException` | *nothing* (before this fix) |

The v4.2.2 probe could never catch it: `probeStreamContentType` only runs under
`isUnrecognizedContainerError(error)` (a 200 with an unreadable body), and a hard 400 throws
`InvalidResponseCodeException` before any body parsing. Even if it had run, the content-type check
looks for `text/html` and this returns `video/x-matroska`.

## Fixes applied

1. **`StreamUrlBuilder` is now the validation choke point** (`data/model/StreamUrlBuilder.kt`).
   `sanitizeExt()` (2–5 alphanumerics, the idiom already proven in `HomeFragment.containerExtFrom`)
   is applied inside `vod()` and `series()`, so **every** caller is fixed at once instead of the ~10
   scattered `?: "mp4"` sites — `?:` fires only on null, so an empty string used to survive all of
   them. `episodeStreamId()` returns null for a non-numeric/≤0 id instead of coercing to `0`.
2. **Honest 400 message** — `causeChainMessage` gained a `400 ->` branch reusing the proven
   dead-listing copy. The old generic text invited a retry that can never succeed.
3. **Correct diagnostic attribution** — new `logFailFast()` logs
   `PROVIDER_DEAD_LISTING reason=http_400` instead of a bare `UNPLAYABLE_SOURCE`, so a customer
   export names the provider as the cause. Extracted `httpStatusOf()` and refactored
   `isDeterministicHttpError()` onto it (three sites did the same reflection walk).
4. **Clone drift closed** — `PlayerViewModel.buildNextResult` was a byte-for-byte copy of the
   `SeriesDetailViewModel` pair; both now go through `StreamUrlBuilder`. `buildNextResult` returns
   null for an unusable listing, which `resolveNextEpisode` already treats as "no more episodes".
   This also stops a malformed URL being persisted into `watch_progress.extra` via `insertUpNextRow`
   (`PlayerViewModel.kt:203`) — see `[[project_frozen_stream_urls]]`.

Note `isDeterministicHttpError` **already** covered 400 (`code in 400..499 && != 408 && != 429`), so
the app was correctly failing fast — it was NOT burning the retry ladder. The two logged attempts
were the initial play plus a user-pressed **Retry** (that path resets `retryCount` but not
`containerExtRetryAttempted`).

## Action for the provider

Report to flarecoral: **series_id 43675, Season 5, episode ids 2056173–2056178** are listed by
`get_series_info` but return HTTP 400 from the delivery CDN, while S1–S4 serve normally on the same
account. Listed-but-not-ingested.

## Method note worth keeping

`Range: bytes=0-1` is the right probe for catalog health: ~2 KB per title, and it cleanly separates
**206** (live, and you can even verify the container magic bytes) from **400/404/200-empty** (dead).
A naive `curl -L` without a Range header downloads the whole 2.7 GB file and looks like a hang. This
is the basis for the proposed catalog-health monitor.
