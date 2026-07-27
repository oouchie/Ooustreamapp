# Repport.docx triage — Nawfatla1, 2026-07-26 22:39 EDT

**Device:** Amazon AFTSS · mt8695 · 900MB RAM (293MB free) · Android 9 (API 28) · `isTV=true`
**App at export:** 4.2.8 (96) · User `Nawfatla1` · Device ID `40850afe5a99c85c`

---

## 1. Repeated buffering — APP DEFECT, already fixed in v4.2.9 (shipped before this report was read)

**Signature: 11 forced playback teardowns, every one at EXACTLY 30.0 seconds.**

```
22:20:18 BUFFERING → 22:20:48 IDLE      22:32:31 BUFFERING → 22:33:01 IDLE
22:20:49 BUFFERING → 22:21:19 IDLE      22:33:41 BUFFERING → 22:34:11 IDLE  ┐ "72 HOURS"
22:21:38 BUFFERING → 22:22:08 IDLE      22:34:12 BUFFERING → 22:34:42 IDLE  │ 4 attempts,
22:29:28 BUFFERING → 22:29:58 IDLE      22:34:45 BUFFERING → 22:35:15 IDLE  │ never played
22:29:59 BUFFERING → 22:30:29 IDLE      22:35:20 BUFFERING → 22:35:50 IDLE  ┘
22:31:36 BUFFERING → 22:32:06 IDLE
```

30.0s is `STALL_TIMEOUT_VOD_MS`. Pre-v4.2.9 `startStallDetector()` was a blind `delay(timeout)` that
called `p.stop()` if still BUFFERING — **regardless of whether the buffer was filling**. Each fire
discarded all partial progress and restarted the load from zero.

**Proof it was destroying working progress:** loads that happened to finish under 30s succeeded —
`BUFFER_COMPLETE took=14142ms`, `took=19227ms`, and one transfer moved `loaded=34318KB` (34MB).
Anything needing >30s was killed and restarted forever. In the 22:20 session the decoders had already
initialised cleanly (`OMX.MTK.VIDEO.DECODER.AVC` 52ms, `ffmpegLavc60.3.100-eac3`) and it *still* never
reached a first frame, because the timer fired 30s after the rebuild.

**v4.2.9 fix applies directly:** the detector now polls and only tears down when `bufferedPosition` is
completely static. With 34MB flowing, this stream would have been left alone to finish.

**Caveat — the connection IS genuinely weak.** `BANDWIDTH estimate=` 2400 / 4425 / 2052 kbps against
1920x800 AVC, and `10kbps loaded=8KB` on *72 HOURS*. v4.2.9 stops the app amplifying the problem; it
cannot make a slow line fast. NOTE `NETWORK/CAPABILITIES down=1048Mbps` is the **WiFi link rate**, not
throughput — never read it as available bandwidth.

**The v4.2.9 BUFFER-CONFIG fix does NOT apply to this device.** `BUFFER_CONFIG memoryClass=128,
totalMem=0.88GB, lowMem=true` is CORRECT for a 900MB stick. That fix only helps >=1.4GB devices.

## 2. NPE crash — REAL, LIVE, NOT fixed by v4.2.9

`java.lang.NullPointerException at OoustreamPlaybackFragment.onViewCreated(SourceFile:1699)` ×5.

| Crash time | Version running (by ship date) |
|---|---|
| 2026-07-02 01:42, 01:48 | v4.2.3 or earlier |
| 2026-07-21 23:40 | v4.2.6 (shipped 07-20 22:16) |
| 2026-07-26 16:18 | v4.2.7 (shipped 07-24 00:46) |
| **2026-07-26 21:09** | **v4.2.8 (shipped 07-26 18:30)** |

Long-standing across 4+ versions, still present in the current build. **v4.2.9 does not touch it.**

**Line decode — DELIBERATELY INCONCLUSIVE, do not act on it.** R8 remaps line numbers, so `:1699` is
obfuscated, not a source line. Decoded with an authentic v4.2.8 mapping (rebuilt from commit `5fe3a4f`
in a throwaway worktree, since the shipped mapping.txt had been overwritten): `obf 1699:1703 → original
line 600`, frame `onViewCreated`. **But line 600 is `ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT)`
inside the SERIES-only binge-overlay `addView` — a statement containing no null dereference.** The
stack's TOP frame is `onViewCreated` with no framework frame above it, so the throw came from our own
bytecode, yet the attributed line provably cannot throw. The decode is internally inconsistent, so it
cannot be used to name an expression. We know the method, not the statement.

> **CORRECTION (same session).** An earlier version of this note, and of the first breadcrumb commit,
> blamed the ambiguity on R8 merging the ~12 byte-identical `addView(x, LayoutParams(...))` statements
> in this method. **That was wrong and is now disproven**: the v4.2.8 mapping shows distinct entries
> per site (`1692:1698→598`, `1699:1703→600`, `1704:1706→598`), and the post-breadcrumb release
> mapping shows all 12 sites resolving to 12 distinct non-overlapping ranges. R8 was never merging
> them, so the breadcrumb's value is the breadcrumb itself — NOT an anti-merge structural fix.
> Why the decode still points at an unthrowable line remains unexplained; that is the open question.

Ruled out by inspection: all 4 `R.id.binge_*` exist in `overlay_binge_countdown.xml` (no missing-view
findViewById NPE); no `ViewStub`/`include`; `viewModel` is `by viewModels()` and
`PlayerViewModel.contentType` is a non-null `var` with a default, so a process-death restore cannot
NPE there.

**NEXT STEP — use Crashlytics, not guesswork.** Firebase Crashlytics is fully wired (SDK + plugin +
`google-services.json` + `com_google_firebase_crashlytics_mappingfileid.xml` generated and uploaded per
build). This NPE is almost certainly already in the console **deobfuscated to the exact line**, with an
affected-user count. That is the authoritative source; check it before writing any fix.

## 3. Noise — ignore

- `EPG_EMPTY` for ~17 live streams at every launch: provider-side EPG gap, cosmetic (per triage ref).
- `AUDIO_DISABLED` events: ride on top of the starvation above, not an audio defect.
- Rapid `APP_START` pairs (22:16:57/22:17:31, 22:26:45/22:27:42, ~35-57s apart) with **no** matching
  crash entries. Consistent with LMK kills on a 900MB device, which are NOT exceptions and leave no
  crash log — but unproven; do not report as fact.

## 4. Correctly-functioning things this report confirms in production

`isTV=true` on AFTSS · `PREBUFFER_GATE enabled=false, tier=ULTRA_LOW` (v4.2.1 gate working) ·
`MTK_MULTICHANNEL_FFMPEG_REBUILD hw=mt8695, mime=audio/eac3, ch=6` (v4.2.6 gate working) ·
`VIDEO_TRACK_SUPPORT support=4 selected=true` (v4.2.5 diagnostic present) ·
`RESOLUTION_CAP maxRes=1920x1080` · `SUBTITLE_PIPELINE_OK`.
