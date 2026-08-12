# The Closer "buffering then stalled" — EAC3 HDMI passthrough stall (2026-08-08/09)

Report: `2026-08-08_21-28-Oouchie-DL-F75728B1.txt` (user Oouchie, AFTKRT mt8696, device 742f25b81eae84aa
— a third stick, NOT .82/.84; both test sticks checked by android_id). Symptom: The Closer (2005)
S01E03 froze/stalled; E02 buffered mid-episode.

## PROVEN (direct fetch + live repro on .84, same model, running 4.2.11)

**The file is healthy.** Fetched with the real account:
- Stream endpoint: 302 → CDN, real Matroska, 4.76GB, byte ranges OK (NOT a dead listing)
- ffmpeg decodes video AND audio with ZERO errors — including 495–540s, the exact stall region
- Normal keyframes (3–5s), progressive 1080p AVC High@L4.0 23.976fps @ 14.1Mbps, EAC3 2.0 @ 224kbps

**Live repro on .84** (resume E03 from Continue Watching @ 513.5s):
- `state=PLAYING, speed=1.0`, position FROZEN at 513536ms for 44+s with ~12s buffer ahead
- Logcat smoking gun — the audio path is **EAC3 HDMI PASSTHROUGH**, not decode:
  - `AudioTrack: audio_input: format: 6 (E_AC3) ... channels: 2`
  - `AudioALSAStreamManager: audio_output: devices: hdmi, format: eac3`
  - `Eve: AUDIO_CRITICAL: Playback drop samples=1536 expected=1349013 interval=28104ms` —
    the passthrough track output ~nothing for 28s
- Frozen passthrough AudioTrack ⇒ frozen playback clock ⇒ video renders ONE frame and waits
  (fps=0.0, full buffer, dropped=0). Every video-decoder swap (SEEK_FLUSH → MTK_SW_FALLBACK →
  c2.android → 0x80000000 crash loop → slideshow give-up) was misdirected — video was never the fault.
  The c2.android `CodecException 0x80000000` ~5–7s after each restart is a downstream casualty.
- Stall position = whatever position was seeked to (463s, 473s, 513s across attempts) — it is
  "seek → passthrough pipe fails to (re)lock", not a bad spot in the file. Sometimes it locks after
  9–39s (E02's BLACK_RECOVERED windows, and one successful Retry during the session); sometimes never.

**Also confirmed in the report:** the mid-E02 buffering at ~21:02 was provider delivery collapse
(bw est. 8.5Mbps → 1.1Mbps, SOURCE_STALL buffered=238ms static 30s) — v4.2.9 detector working as
designed. Separate issue, provider-side.

## Why passthrough engages at all
DefaultAudioSink takes the direct (compressed) path whenever the HDMI sink's EDID advertises
EAC3/AC3 — bypassing MediaCodec/FFmpeg decode AND our ChannelMixingAudioProcessor stereo-downmix
chain entirely. Our whole AudioPipelineFactory design assumes decode-to-PCM; passthrough is an
accidental bypass that only appears when the TV/soundbar advertises Dolby (living-room stick has a
soundbar). This RETROACTIVELY EXPLAINS v4.2.6/v3.7.3/v3.3.3: the "hardware audio decoder stall" on
mt8695 was very likely passthrough stalling — the FFmpeg-preferred rebuild "fixed" it because
FfmpegAudioRenderer outputs PCM, which cannot take the passthrough path.

## Fix status (2026-08-11): IMPLEMENTED, awaiting device verification
`AudioPipelineFactory.buildAudioSinkSafely` now uses the no-context `DefaultAudioSink.Builder()`
(both main + fallback paths). Verified against the local Media3 1.10.0 clone: with a Context the
Builder IGNORES setAudioCapabilities (`AudioTrackAudioOutputProvider.Builder(context)` probes EDID
dynamically); with null context capabilities pin to `DEFAULT_AUDIO_CAPABILITIES` = PCM-only, no
capabilities receiver, `supportsFormat(eac3/ac3/dts)==false` → decode → downmix → PCM out. Null
context only costs API-34 virtual-device routing (irrelevant on TV). One shared function → all 7
player build sites covered. compileDebugKotlin + assembleDebug clean.
**Recurrence evidence:** report `2026-08-10_19-26-Oouchie-DL-23049E80.txt` (AFTKRT 3a6fa453c50e472e,
4.2.12 — a DIFFERENT stick from the 08-08 report device, both distinct from .82/.84): (1) 16:33
"Show Yourself" AUDIO_STALL silent=15s on eac3 2ch mid-episode, recovered ~30s via stall ladder +
hard reset; (2) 19:24 "Flashpoint" resume@7:42 → frozen clock after seek → watchdog burned HW→SW→
FFmpeg decoders → SLIDESHOW_GIVE_UP → false "video format not supported" dialog. Same signature.
**Verification pending:** stick with Dolby-advertising HDMI sink. On 2026-08-11 21:30 EDT: .82 was
mid-viewing (hands off), .84 powered off, .214 off-network. Verify = play/resume-seek an EAC3 title;
expect logcat `AudioALSAStreamManager audio_output` format pcm (not eac3), position advances past
every seek.

## Fix direction (original, NOT yet implemented as of 08-08)
Force decode-to-PCM in `AudioPipelineFactory` (DefaultAudioSink built with PCM-only audio
capabilities so `supportsFormat(eac3/ac3/dts)==false` → renderer decodes via FFmpeg/MediaCodec →
existing downmix chain applies). Consistent with the app's stereo-downmix design. Tradeoff: AVR/
soundbar users get stereo PCM instead of bitstream Dolby — consider a Settings toggle
("Dolby passthrough: Off (default) / Auto") later.
- Media3 1.10 API to verify against local clone (`ffmpeg-build/media3-source`): how DefaultAudioSink
  Builder accepts capabilities in 1.10; wire at ALL sink-construction sites via the shared factory
  (one function — rebuild-clone-drift rule).
- MUST device-verify on a stick whose HDMI sink advertises Dolby (both .82/.84 qualify — HAL showed
  eac3 out) before shipping. Both sticks were in use (live TV) when this session ended — verification
  pending.

## A/B test caveat
`settings put global encoded_surround_output 1` did NOT force PCM on Fire OS (HAL kept eac3 out;
Fire OS has its own `firetv_hdmi_dolby_passthrough_available`). Setting was restored to 0. The one
successful Retry happened with passthrough still active → intermittent lock-in, not proof of fix.

## Session hygiene notes
- adb over WiFi; .84 authorized via on-TV dialog. Report device (742f25b81eae84aa) never accessed.
- encoded_surround_output restored to original 0 on .84. No app data touched.
