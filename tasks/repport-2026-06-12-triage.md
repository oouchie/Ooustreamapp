# Customer debug-report triage — Repport.docx (user "Oouchie", AFTKRT/mt8696, app v4.0.1)

Triaged 2026-06-15. Source: `~/Documents/Repport.docx` (Send-Debug-Log export). Sessions on v4.0.0/v4.0.1.

## Findings (video/network were HEALTHY throughout — fps≈24, 10–29s buffer, 2400kbps steady)
1. **Kung Fu Panda (2008)** (VOD id 1375882) unplayable — `code 3003 / UnrecognizedInputFormatException`,
   codec=null, ~19 identical retries. Wrong/unresolved container (`.m2ts` served at a `.mp4` URL).
   → **ALREADY FIXED in v4.2.0**: `OoustreamPlaybackFragment.kt:1167‑1210` catches the unrecognized-container
     error, re‑probes the authoritative extension via `get_vod_info` (`PlayerViewModel.fetchRealVodExtension`),
     rewrites to `.m2ts`, plays through the M2TS‑aware source (one retry → plays). Comment names the title.
2. **156× `AUDIO_SINK_ERROR` = `UnexpectedDiscontinuityException`** on 6‑ch AC3/DTS movies (Meet the Robinsons)
   tripped the audio‑stall recovery ladder → a full mid‑movie FFmpeg `PLAYER_REBUILD`. Benign source PTS jumps.
   → **ALREADY FIXED in v4.2.0**: `ExoPlayerDiagnosticListener.kt:157` gates `onAudioSinkFault` on
     `!is AudioSink.UnexpectedDiscontinuityException` — logged only, no rebuild. Comment names the title.
3. **14× `EPG_EMPTY`** — cosmetic, server‑side EPG gap for that account (per the CLAUDE.md triage reference).
   No crashes / FATAL anywhere in the report.

## Verdict
Both real issues are already fixed in the shipped **v4.2.0**; the customer is on **v4.0.1** (two versions
behind) → **the OTA update to 4.2.0 resolves their reported symptoms.** Verified against current source, not
inferred.

## Residual polish applied (NOT yet released — bundle into the next version bump)
`HomeFragment.kt` "Pick Up & New" watch‑again VOD tap hardcoded `buildVodStreamUrl(id, "mp4")`, ignoring the
saved `WatchProgressEntity.extra` URL (which carries the corrected extension after a prior play). For an
`.m2ts` title launched from that rail the first attempt failed before the container‑ext retry recovered it.
**Fix:** reuse `item.extra` first, fall back to the `"mp4"` rebuild (mirrors the Continue Watching path).
`:app:compileDebugKotlin` BUILD SUCCESSFUL. No version bump, no GitHub release (per user: "fix residual,
don't release yet"). **Action for next release:** include this HomeFragment change in the next versionCode bump.
