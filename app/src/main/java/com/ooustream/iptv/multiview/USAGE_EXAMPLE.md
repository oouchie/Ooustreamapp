# QR Upgrade Overlay - Usage Examples

## Overview
The QR upgrade overlay system provides monetization for MultiView features on Basic plan users.

## Files Created
1. `QrCodeGenerator.kt` - Utility for generating QR code bitmaps
2. `QrUpgradeDialogFragment.kt` - Full-screen QR dialog with 5-minute countdown
3. `MultiViewLockedPopup.kt` - Simple locked feature popup
4. `dialog_qr_upgrade.xml` - QR dialog layout

## Integration Examples

### Example 1: Show locked popup when MultiView button clicked
```kotlin
// In any Fragment (e.g., HomeFragment, LiveTvFragment)
@AndroidEntryPoint
class HomeFragment : Fragment() {

    @Inject
    lateinit var userPlanManager: UserPlanManager

    private fun onMultiViewButtonClicked() {
        // Check if user is Pro
        if (!userPlanManager.isPro.value) {
            // Show locked popup with upgrade CTA
            MultiViewLockedPopup.show(requireContext(), parentFragmentManager)
            return
        }

        // User is Pro, launch MultiView
        launchMultiView()
    }
}
```

### Example 2: Show QR dialog directly
```kotlin
// Directly show the QR upgrade dialog (e.g., from Settings)
val dialog = QrUpgradeDialogFragment()
dialog.show(parentFragmentManager, "qr_upgrade")
```

### Example 3: Gate MultiView feature access
```kotlin
// In MultiViewFragment or similar
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    lifecycleScope.launch {
        userPlanManager.isPro.collect { isPro ->
            if (!isPro) {
                // Basic user accessed MultiView fragment somehow, show popup and navigate back
                MultiViewLockedPopup.show(requireContext(), parentFragmentManager)
                findNavController().navigateUp()
            }
        }
    }
}
```

### Example 4: Add to existing toolbar/menu
```kotlin
// Add "Upgrade to Pro" menu item for Basic users
override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
    super.onCreateOptionsMenu(menu, inflater)

    if (!userPlanManager.isPro.value) {
        menu.add("Upgrade to Pro").apply {
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            setOnMenuItemClickListener {
                val dialog = QrUpgradeDialogFragment()
                dialog.show(parentFragmentManager, "qr_upgrade")
                true
            }
        }
    }
}
```

## Customization

### Change upgrade URL
Edit `QrUpgradeDialogFragment.kt`:
```kotlin
private const val UPGRADE_URL = "https://your-domain.com/upgrade?from=app"
```

### Adjust countdown duration
Edit `QrUpgradeDialogFragment.kt`:
```kotlin
private const val COUNTDOWN_DURATION_MS = 3 * 60 * 1000L // 3 minutes instead of 5
```

### Customize popup message
Edit `MultiViewLockedPopup.kt`:
```kotlin
.setMessage("Custom message here")
```

## Testing

### Test as Basic user
```kotlin
// Force Basic plan state for testing
userPlanManager.updateFromMaxConnections(1) // Basic = 1 connection
```

### Test as Pro user
```kotlin
// Force Pro plan state for testing
userPlanManager.updateFromMaxConnections(4) // Pro = 4+ connections
```

## Notes
- All AlertDialogs use `android.app.AlertDialog` (NOT AppCompat) for Leanback theme compatibility
- QR code generated at 512x512 pixels for optimal scanning
- Dialog auto-dismisses after 5 minutes
- Back key dismisses the dialog
- Countdown updates every second
- QR code links to: `https://portal.ooustream.com/upgrade?from=multiview`
