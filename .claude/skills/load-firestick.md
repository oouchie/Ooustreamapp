---
name: load-firestick
description: Load a Fire TV Stick (or Ooustick) with BOTH the Ooustream app and IPTV Smarters over adb. Use when the user says "send the app to <ip>", "load the firestick at <ip>", "install on <ip>", or names a device by its last octet (e.g. "send it to 147" = 192.168.1.147). Handles adb auth, ABI matching, the IPTV Smarters Cloudflare-blocked download, and on-device version verification.
---

# Load Firestick

Install BOTH apps on a stick on the LAN, verified end to end: the current Ooustream release build **and** IPTV Smarters (the comparison player). A load is not complete until both are confirmed on the device. Proven flow from the 2026-08-20 load of 192.168.1.147 (AFTMA08C15).

## Inputs
- **Device IP** — a bare last octet like "147" means `192.168.1.147`. Known devices: `.82` / `.84` (AFTKRT), `.147` / `.154` / `.155` / `.216` / `.244` (AFTMA08C15), `.222` / `.245` (Ooustick).
- Nothing else is needed. **Both apps go on by default** — do not ask whether to include Smarters. Only skip it if the user explicitly says Ooustream only.

## Steps

### 1. Connect + authorize
`adb` is NOT on PATH — always use `~/Library/Android/sdk/platform-tools/adb`.

```bash
ADB=~/Library/Android/sdk/platform-tools/adb
$ADB connect <IP>:5555
$ADB -s <IP>:5555 shell getprop ro.product.model
$ADB -s <IP>:5555 shell getprop ro.product.cpu.abilist
```

If you get `device unauthorized`: an "Allow USB debugging?" dialog is on the TV screen. Tell the user to accept it (Always allow) and **stop the turn** — polling for ~a minute does not help; wait for them to confirm. If no dialog appears, have them toggle Settings → My Fire TV → Developer Options → ADB Debugging off/on, then reconnect.

### 2. Pick the APK by ABI
- `abilist` starts with `arm64-v8a` → `app-arm64-v8a-release.apk`
- `abilist` is `armeabi-v7a,armeabi` (all AFTMA08C15 + AFTKRT sticks) → `app-armeabi-v7a-release.apk`

### 3. Verify the local build BEFORE installing (No-Assume rule)
```bash
ls -la app/build/outputs/apk/release/
grep -E '"versionCode"|"versionName"' app/build/outputs/apk/release/output-metadata.json
```
The versionName/versionCode must match the current version in CLAUDE.md. If APKs are missing or stale, build first: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew assembleRelease`.

### 4. Install Ooustream
```bash
$ADB -s <IP>:5555 install -r app/build/outputs/apk/release/app-<abi>-release.apk
```
`-r` preserves existing user data (favorites, watch progress). Expect `Success`.

### 5. IPTV Smarters (always — part of every load)
**Use the copy already on this machine: `~/Downloads/smarters.apk`** — the official build, downloaded 2026-08-20 and installed successfully on .147. Expect ~84MB, package `com.nst.iptvsmarterstvbox`, versionName **3.1.5.1** (versionCode 112). It's a universal APK, so the same file installs on every stick regardless of ABI. Don't re-download it if it's there.

If it's been cleaned out of Downloads, re-fetch from `https://www.iptvsmarters.com/smarters.apk` — but **Cloudflare 403-blocks curl even with a browser User-Agent**, returning a ~5KB HTML challenge page instead of the APK. Do NOT waste retries on curl. Download through the user's Chrome via claude-in-chrome: load the browser tools, `tabs_context_mcp{createIfEmpty:true}`, then `navigate` the tab to the APK URL — Chrome passes the challenge and drops the file in `~/Downloads`. Close the tab afterward.

Verify before installing — required for a fresh download, and a cheap sanity check on the cached one:
```bash
file ~/Downloads/smarters.apk                      # must be "Zip archive data", NOT "HTML document"
AAPT=$(ls ~/Library/Android/sdk/build-tools/*/aapt | tail -1)
$AAPT dump badging ~/Downloads/smarters.apk | grep -E "^package|native-code"
```
Expected: package `com.nst.iptvsmarterstvbox`, native-code includes `armeabi-v7a`. An "HTML document" here means a Cloudflare challenge page got saved under the .apk name — delete it and use the Chrome path. Then:
```bash
$ADB -s <IP>:5555 install -r ~/Downloads/smarters.apk
```

### 6. Verify on-device (never report success from the install output alone)
```bash
for p in com.ooustream.iptv com.nst.iptvsmarterstvbox; do
  echo -n "$p -> "
  $ADB -s <IP>:5555 shell "dumpsys package $p" 2>/dev/null | grep -m1 versionName
done
```
Run `dumpsys` per package and pipe stderr to /dev/null — chaining both in one shell call emits `Failed to write while dumping service package: Broken pipe` noise, and `grep -m1 versionName` on the combined dump can return the wrong app's line. Report the exact versions confirmed on the device, one per app. **Both lines must come back.** An empty result for a package means it is NOT installed — `pm list packages | grep -i smarters` is the quick pre-check.

## Notes
- 64-bit sticks can install the 32-bit APK, but 32-bit sticks FAIL on arm64 with `INSTALL_FAILED_NO_MATCHING_ABIS (-113)` — when in doubt, armeabi-v7a is the safe choice.
- This skill sideloads only; it does not touch `update.json` or the OTA/release flow (see CLAUDE.md "Release Process" for that).
- Smarters needs the Xtream login entered on first launch — remind the user.
