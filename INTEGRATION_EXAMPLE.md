# QR Upgrade Integration Example

## Quick Integration into MultiViewFragment

Add this check in `MultiViewFragment.onCreate()` or `onViewCreated()`:

```kotlin
// In MultiViewFragment.kt
@AndroidEntryPoint
class MultiViewFragment : Fragment(), KeyEventHandler {

    @Inject lateinit var userPlanManager: UserPlanManager
    // ... existing injections

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Check if user has access to MultiView
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                userPlanManager.isPro.collect { isPro ->
                    if (!isPro) {
                        // Show locked popup and navigate back
                        MultiViewLockedPopup.show(requireContext(), parentFragmentManager)

                        // Navigate back to previous screen
                        requireActivity().onBackPressed()
                    }
                }
            }
        }

        // ... rest of existing code
    }
}
```

## Alternative: Check Before Navigation

Add the check BEFORE navigating to MultiViewFragment:

```kotlin
// In HomeFragment.kt or wherever MultiView is launched
private fun launchMultiView() {
    if (!userPlanManager.isPro.value) {
        // Show locked popup
        MultiViewLockedPopup.show(requireContext(), parentFragmentManager)
        return
    }

    // Navigate to MultiView
    val fragment = MultiViewFragment()
    requireActivity().supportFragmentManager.beginTransaction()
        .replace(R.id.fragment_container, fragment)
        .addToBackStack(null)
        .commit()
}
```

## Add to Settings Menu

In `SettingsFragment.kt`, add an "Upgrade to Pro" option:

```kotlin
private fun buildSettingsActions(): List<GuidedAction> {
    val actions = mutableListOf<GuidedAction>()

    // Show upgrade option for Basic users
    if (!userPlanManager.isPro.value) {
        actions.add(
            GuidedAction.Builder(requireContext())
                .id(ACTION_UPGRADE_PRO)
                .title("Upgrade to Pro")
                .description("Unlock MultiView • 4+ connections • Priority streaming")
                .icon(R.drawable.ic_star_gold) // optional
                .build()
        )
    }

    // ... existing actions
    return actions
}

override fun onGuidedActionClicked(action: GuidedAction) {
    when (action.id) {
        ACTION_UPGRADE_PRO -> {
            val dialog = QrUpgradeDialogFragment()
            dialog.show(parentFragmentManager, "qr_upgrade")
        }
        // ... existing cases
    }
}
```

## Test Flow

1. Build and install APK
2. Set user to Basic plan: `userPlanManager.updateFromMaxConnections(1)`
3. Try to access MultiView feature
4. Popup appears: "MultiView — Pro Feature"
5. Tap "Learn More"
6. Full-screen QR dialog shows with countdown
7. Scan QR code with phone (redirects to upgrade portal)
8. Press Back or wait 5 minutes to dismiss

## Files Summary

**Created:**
- `QrCodeGenerator.kt` - ZXing QR code generator
- `QrUpgradeDialogFragment.kt` - Full-screen dialog with countdown
- `MultiViewLockedPopup.kt` - Simple alert dialog
- `dialog_qr_upgrade.xml` - QR dialog layout
- `USAGE_EXAMPLE.md` - Detailed usage examples

**Modified (suggested):**
- `MultiViewFragment.kt` - Add plan check in `onViewCreated()`
- OR navigation code wherever MultiView is launched

**Dependencies (already present):**
- ZXing: `com.google.zxing:core:3.5.2`
- Hilt DI
- UserPlanManager

## QR Code Content
```
https://portal.ooustream.com/upgrade?from=multiview
```

## Visual Flow
```
[Basic User] → [MultiView Button]
                     ↓
            [MultiViewLockedPopup]
              "Pro Feature Required"
              [Learn More] [Not Now]
                     ↓
           [QrUpgradeDialogFragment]
         ┌─────────────────────────┐
         │  [QR]  │  Upgrade to Pro │
         │  CODE  │  $35/mo         │
         │        │  Expires 4:58   │
         └─────────────────────────┘
                     ↓
            [User Scans QR]
                     ↓
         [Opens Upgrade Portal]
```
