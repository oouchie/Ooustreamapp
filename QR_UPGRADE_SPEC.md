# QR Upgrade Overlay - Visual Specification

## Feature Overview
Monetization overlay for Basic plan users attempting to access MultiView features.

## Components Created

### 1. QrCodeGenerator.kt
- Simple ZXing wrapper
- Generates 512x512px QR code bitmap
- Black code on white background
- Used for upgrade portal link

### 2. MultiViewLockedPopup.kt
- Simple `android.app.AlertDialog` (Leanback-safe)
- Title: "MultiView — Pro Feature"
- Message: Features + pricing
- Buttons: "Learn More" (opens QR dialog) / "Not Now" (dismiss)

### 3. QrUpgradeDialogFragment.kt
- Full-screen DialogFragment
- 5-minute countdown timer
- Auto-dismiss on timer end or Back key
- Generates QR code on view creation

### 4. dialog_qr_upgrade.xml Layout

```
┌─────────────────────────────────────────────────────────────────────┐
│ Full-screen overlay (#D906060A - 85% opacity dark)                  │
│                                                                      │
│     ┌───────────────────────────────────────────────────┐           │
│     │ Card (520dp wide, #1A1A2E bg, 16dp corners)       │           │
│     │                                                    │           │
│     │  ┌──────────┐ │ Upgrade to Pro (gold, 22sp bold) │           │
│     │  │          │ │                                   │           │
│     │  │  [QR]    │ │ Unlock MultiView (white 70%, 14sp)           │
│     │  │  CODE    │ │                                   │           │
│     │  │ 180x180  │ │ • 4+ simultaneous connections    │           │
│     │  │          │ │ • MultiView Sports Player        │           │
│     │  │          │ │ • Priority streaming             │           │
│     │  └──────────┘ │                                   │           │
│     │  (white bg)   │ $35/mo (gold, 28sp bold)         │           │
│     │               │                                   │           │
│     │               │ Offer expires in 4:58 (#9CA3AF)  │           │
│     └───────────────────────────────────────────────────┘           │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

## Color Palette
- Background overlay: `#D906060A` (85% opacity)
- Card background: `#1A1A2E` (reuses `bg_channel_picker_dialog`)
- Gold accent: `#FFC107` (titles, price, divider)
- White text: `#FFFFFF` (features)
- Secondary text: `#B3FFFFFF` (70% white - subtitle)
- Timer text: `#9CA3AF` (muted gray)
- QR container: `#FFFFFF` (white)

## Dimensions
- Card width: 520dp
- Card padding: 24dp all sides
- QR code size: 180x180dp
- QR container padding: 8dp
- Vertical divider: 1dp width, 20dp margins
- Corner radius: 16dp (from `bg_channel_picker_dialog`)

## Behavior

### User Flow
1. Basic user tries to access MultiView feature
2. `MultiViewLockedPopup.show()` displays simple alert
3. User taps "Learn More"
4. `QrUpgradeDialogFragment` opens full-screen
5. QR code generated and displayed
6. 5-minute countdown starts
7. User scans QR code with phone → opens upgrade portal
8. Dialog auto-dismisses after 5 minutes or on Back key

### Timer Behavior
- Starts at 5:00
- Updates every second
- Format: `M:SS` (e.g., "4:58", "0:32")
- Text: "Offer expires in {time}"
- Auto-dismiss at 0:00

### Dismissal
- Back key press
- Timer expiration
- No outside-tap-to-dismiss (focusable=false on background)

## QR Code URL
```
https://portal.ooustream.com/upgrade?from=multiview
```

## Integration Points

### Check if locked
```kotlin
if (!userPlanManager.isPro.value) {
    MultiViewLockedPopup.show(requireContext(), parentFragmentManager)
    return
}
```

### Show QR directly (from Settings, etc.)
```kotlin
val dialog = QrUpgradeDialogFragment()
dialog.show(parentFragmentManager, "qr_upgrade")
```

## Testing
- Force Basic plan: `userPlanManager.updateFromMaxConnections(1)`
- Force Pro plan: `userPlanManager.updateFromMaxConnections(4)`

## Dependencies
- ZXing Core: `com.google.zxing:core:3.5.2` (already in build.gradle.kts)
- Hilt: `@AndroidEntryPoint`, `@Inject`
- UserPlanManager: `com.ooustream.iptv.data.UserPlanManager`

## Files Created
1. `app/src/main/java/com/ooustream/iptv/multiview/QrCodeGenerator.kt`
2. `app/src/main/java/com/ooustream/iptv/multiview/QrUpgradeDialogFragment.kt`
3. `app/src/main/java/com/ooustream/iptv/multiview/MultiViewLockedPopup.kt`
4. `app/src/main/res/layout/dialog_qr_upgrade.xml`
5. `app/src/main/java/com/ooustream/iptv/multiview/USAGE_EXAMPLE.md` (examples)

## Production Notes
- AlertDialog uses `android.app.AlertDialog` (NOT AppCompat) for Leanback theme safety
- QR generation is synchronous but fast (<50ms for 512px)
- Countdown timer properly cleaned up in `onDestroyView()`
- Full-screen transparent window pattern matches `ChannelPickerDialogFragment`
