# Allwinner sun50iw9p1 — 4K HEVC playback failure triage (2026-06-27)

Source report: `~/Documents/Repport.docx` (customer `rasheeda`, device id `ae3ec22848da32a7`).

## Device (NEW hardware class for us)
- **Pendoo X11 PRO**, `Build.HARDWARE = sun50iw9p1` → **Allwinner H616** SoC. (The "Amlogic" in the
  model marketing string is wrong; `sun50iw9` is Allwinner.) **First Allwinner box we've ever seen a
  report from.** All our device-tier / decoder logic to date is MediaTek/Amlogic-Fire-TV-centric.
- Android 10 (API 29), 4007MB RAM, memoryClass=256MB. App classified it `tier=MID, badMtk=false,
  goodMtk=false` (Allwinner is invisible to the MTK lists).
- `Is TV: false` (it's an Android box, not a Fire TV — reports as a phone/tablet form factor).

## Decoders present on the device (observed in logs)
- `OMX.allwinner.video.decoder.avc` — HW H.264 ✓ (every AVC title gets a first frame from it)
- `OMX.allwinner.video.decoder.hevc` — HW HEVC ✓ (plays "The Passenger" 3840x1600)
- `c2.android.avc.decoder` / `c2.android.hevc.decoder` — Google software fallbacks
- `ffmpegLavc60.3.100-hevc` — our bundled FFmpeg SW HEVC extension

## VERIFIED failure table (from the log)
| Title | Codec | Res | First decoder chosen | Outcome |
|---|---|---|---|---|
| **4K: Michael** | HEVC **Main10** `hvc1.2.4.H150.B0` | 3840x2074 | **`ffmpegLavc60.3.100-hevc`** (FFmpeg SW) — HW HEVC never tried | fps=0 → 2.5-min watchdog thrash → GIVE_UP → "format not supported" |
| **4K: Project Hail Mary** | HEVC **Main** 8-bit `hvc1.1.6.L150.90` | 3840x2160 | `OMX.allwinner.video.decoder.hevc` (HW, correct) | first frame, ~22s, then fps=0; bw ~0.7–3 Mbps, refills 15–27s → **network-starved**; watchdog then swaps HW→SW and thrashes |
| **4K: The Passenger** | HEVC 8-bit | 3840x1600 | `OMX.allwinner.video.decoder.hevc` (HW) | **PLAYS ✓** |
| Michael (non-4K), Stepfather, NASCAR, Chum, Hungry, Strung, Automata… | AVC | ~1080p | `OMX.allwinner.video.decoder.avc` (HW) | first frame OK, then IDLE/rebuffer loops (network) |
| André Is an Idiot | AVC High `avc1.640028` | 1920x1080 | HW avc → SW c2.android → fps=2 SLIDESHOW_GIVE_UP | fails (likely network) |

## Three distinct problems

### P1 — HEVC Main10 routed to FFmpeg SW → unplayable (THE headline "won't play")
- For `hvc1.2.4.*` (Main10 / 10-bit), the app picks the **FFmpeg software** HEVC renderer on the
  **first** attempt and **never tries `OMX.allwinner.video.decoder.hevc`**. Strong hypothesis: the
  Allwinner HW HEVC MediaCodec does not *advertise* `HEVCProfileMain10`, so ExoPlayer's MediaCodec
  renderer reports the format unsupported and the prepended FFmpeg video renderer accepts it.
- FFmpeg SW cannot decode 4K Main10 in real time on the A53 cores → `fps=0`, full buffer, black
  screen → watchdog ladder runs the entire SEEK_FLUSH→SW_FALLBACK→720P_CAP→480P_CAP→FFMPEG_VIDEO→
  GIVE_UP sequence (every rebuild still landing on `ffmpegLavc...`), ~2.5 minutes, then the error
  dialog. Reproduced identically across app v4.1.0 and v4.2.2.
- **Open question (needs device/research):** does the Allwinner H616 HW HEVC decoder ACTUALLY decode
  10-bit even though it doesn't advertise the profile? If yes → force HW for Main10. If no → fail
  fast + honest instead of the 2.5-min thrash.

### P2 — watchdog misreads network starvation as decoder failure
- "Project Hail Mary" (full 2160) started on the **correct HW decoder** and was fine until the buffer
  drained. The bandwidth (~1 Mbps sustained) and 15–27s `BUFFER_COMPLETE` refills are the textbook
  customer-side-network-bottleneck signal from our own triage reference. `fps=0 dropped=0
  buffer=2–3s` = decoder starved, not broken.
- The watchdog can't tell this apart from a decoder freeze, so it tears down the working HW decoder
  and rebuilds with the slower `c2.android.hevc` SW decoder — each rebuild re-buffers from scratch
  (another 15–27s) — actively making the stall worse and burning minutes.

### P3 — general network/server rebuffering on 1080p AVC (NOT app-fixable)
- Many AVC titles get a first frame then IDLE/rebuffer with bw 150 kbps–2 Mbps. That's the customer's
  network or the flarecoral server. Honest messaging only. Do not chase as an app bug.

## CONFIRMED root-cause mechanism (verified against source)
- Initial player → `createMtkAwareRenderersFactory` → non-MTK → `createRenderersFactory` (default),
  `EXTENSION_RENDERER_MODE_ON` (`AudioPipelineFactory.kt:324, 359, 419`).
- **Media3 1.10.0's `DefaultRenderersFactory.buildVideoRenderers` reflection probe auto-registers the
  bundled `ExperimentalFfmpegVideoRenderer`** — so the FFmpeg *video* renderer is in the default list
  even though the stale comment at `AudioPipelineFactory.kt:105-109` claims it isn't. (Proven by the
  v3.7.0 MODE_PREFER comment at `:217-222` AND the customer log showing `ffmpegLavc...` on the first
  decode.) With MODE_ON the order is `[MediaCodec, FFmpeg]`.
- **Main10**: `OMX.allwinner.video.decoder.hevc` does not advertise the Main10 profile → MediaCodec
  `supportsFormat` = UNSUPPORTED → the auto-registered FFmpeg SW renderer accepts it → `ffmpegLavc...`
  decodes → can't do realtime 4K → fps=0.
- **The watchdog never set `usingSoftwareVideoDecoder`/`usingFfmpegVideoDecoder`** for the
  auto-selected FFmpeg, so it believes it's on hardware and runs the full ladder
  (`OoustreamPlaybackFragment.kt:1858-2075`). Every "software fallback" rebuild lands back on FFmpeg
  (the only renderer that accepts Main10), so all 5 steps are futile → ~2.5-min thrash → GIVE_UP.
- **The watchdog escalates purely on frame-count delta — it never reads buffered duration or
  bandwidth** (`:1858-1876`). So network starvation (Project Hail Mary, ~1 Mbps, 15-27s refills) is
  indistinguishable from a decoder fault, and it rips out the working HW decoder for the slower
  `c2.android.hevc` SW one, re-buffering from scratch each time.
- `hwDecoderProvenGood` requires 30 rendered frames in a single 2s poll (=15 fps). A network-starved
  HW decoder never hits that, so the "proven good → hard-reset only" protection (`:1923`) never
  engages — it falls straight to the destructive SW swap.

## Fix direction (finalized)
1. **Allwinner awareness**: add `isAllwinner()` (Build.HARDWARE starts with `sun`) and treat its HW
   HEVC decoder as capable; prefer HW HEVC, and for Main10 either force-try HW or fail fast.
2. **Fast, honest fail for 4K-HEVC-only-on-FFmpeg-SW**: skip the 2.5-min watchdog thrash.
3. **Network-starvation guard in the watchdog**: when `fps==0` but buffer is shallow / refills are
   huge / repeated BUFFERING, suppress decoder rebuilds; show "slow network", keep the working
   decoder.

## Implemented (safe fast-fail scope — user chose this; no device to verify a force-HW path)
Decision: do NOT attempt to force the Allwinner HW decoder to play Main10 (unverifiable from here,
risky on the core player path, and the customer's ~1 Mbps network can't sustain 4K anyway). Instead:
turn the 2.5-min black-screen thrash into a ~5s honest failure, and stop the watchdog destroying a
working decoder. The watchdog fix is **device-agnostic** (triggers on "software decoder + 4K HEVC"),
so it also covers RockChip and other generic boxes — not just Allwinner.

Changes (all in `player/OoustreamPlaybackFragment.kt` + `ExoPlayerDiagnosticListener.kt` + `DeviceTier.kt`):
1. **Capture the live video decoder name** — `ExoPlayerDiagnosticListener.onVideoDecoder` callback
   set in `onVideoDecoderInitialized`; fragment field `activeVideoDecoderName`. Needed because the
   FFmpeg video renderer is auto-selected by the *default* factory (Media3's reflection probe) WITHOUT
   setting `usingSoftwareVideoDecoder`/`usingFfmpegVideoDecoder` — so the watchdog had no way to know
   it was on software. Survives rebuilds (same listener re-attached); reset on content switch.
2. **Cache video resolution** (`cachedVideoWidth/Height`) in `onTracksChanged` for robust 4K detection.
3. **4K-HEVC handling at the top of the watchdog frozen branch:**
   - on a SOFTWARE video decoder (ffmpeg / c2.android / OMX.google) → **fast give-up** with an honest
     "4K HDR (10-bit) — this device can't decode it" message (Michael: ~5s instead of 2.5 min).
   - on the HARDWARE decoder → hard-reset to recover from a transient/network dip, **never swap to
     software** (it can't do 4K HEVC and re-buffers from scratch); give up honestly after
     `FOURK_HW_HARD_RESET_LIMIT`(3) resets unless `hwDecoderProvenGood` (Hail Mary: stops the
     destructive HW→c2.android swap).
   - Non-4K-HEVC content unchanged — falls through to the original ladder.
4. **`DeviceTier.describe()`** now logs `allwinner=` so future debug reports flag this device class.

New diagnostic events for future triage: `WATCHDOG_GIVE_UP reason=sw_4k_hevc_unplayable`,
`WATCHDOG_HW_4K_RESET`, `WATCHDOG_GIVE_UP reason=hw_4k_hevc_stalled`.

## Status
- [x] Report extracted + fully analyzed (verified from log)
- [x] Code investigation (decoder selection / watchdog / tier / stall detection) — root cause confirmed against source
- [x] Fix designed + scope confirmed with user (safe fast-fail, no force-HW)
- [x] Implemented
- [x] `:app:compileDebugKotlin` clean
- [x] Adversarial review of the diff (8 agents: 3 reviewers + 5 Opus verifiers) — **0 confirmed
      findings**; all 14 candidates dismissed against the actual code. Two were confirmed as
      *deliberate* design (`|| hwDecoderProvenGood` unbounded retry matches existing non-4K behavior;
      "never swap 4K HEVC to software" is intentional — software can't do realtime 4K HEVC).
- [x] Corrected a stale comment in `AudioPipelineFactory.kt` (it wrongly claimed the FFmpeg video
      renderer isn't auto-registered — it IS, in Media3 1.10.0 with MODE_ON; that's the whole root cause).
- [ ] Version bump + release build + GitHub release (awaiting user go-ahead — outward-facing)

## NOT app-fixable (tell the customer / provider)
- The 1080p AVC rebuffer loops + the 4K stalls are bounded by the customer's ~1 Mbps connection (4K
  needs 15-25 Mbps). No app change makes 4K stream over that pipe. The fix makes the failure honest
  and fast instead of a multi-minute black screen.
- **No device on hand to verify** — shipped build is reason- + compile-verified only (error-path
  change to the core player). Treat any playback-regression report against this build with that caveat.
