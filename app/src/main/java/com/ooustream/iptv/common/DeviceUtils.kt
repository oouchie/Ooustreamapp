package com.ooustream.iptv.common

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.annotation.VisibleForTesting

/**
 * Device form-factor classification.
 *
 * ## Why this is not just `UiModeManager.currentModeType`
 *
 * The original implementation trusted `UI_MODE_TYPE_TELEVISION` alone. That is a *sufficient*
 * signal but NOT a *necessary* one: it is only set on devices that ship a Google/Amazon-certified
 * TV system image. Generic AOSP set-top boxes (Allwinner H616 "OOUSTICK", Rockchip, Amlogic
 * droidlogic builds, etc.) report `UI_MODE_TYPE_NORMAL` and declare **no** leanback/television
 * system feature, even though the only input device is a D-pad remote.
 *
 * On such a box the old code returned `isTV=false`, and because `smallestScreenWidthDp` is 540
 * (720p @ density 213) it then returned `isPhone=true`. Every "phone" branch in the app fired —
 * most destructively `TouchGridSetup.stripItemFocusForTouch()` and `HomeFragment.setupHeroClickListener()`,
 * which set `isFocusable = false` on every card and hero button. Focus parked on a row container
 * with zero focusable descendants and D-pad navigation died completely.
 *
 * ## The decisive signal: absence of a touchscreen
 *
 * Any device Google will certify as a phone or tablet **must** declare
 * `android.hardware.touchscreen`. A remote-driven box does not. So "no touchscreen" cleanly
 * separates box-from-phone even when every TV-specific marker is missing.
 *
 * ## Fail-safe direction: ambiguity resolves to TV
 *
 * The two failure modes are not symmetric:
 *  - TV misread as phone  → focus stripped → app is **unusable** (the bug this fixes).
 *  - Phone misread as TV  → TV-shaped layout + tap-to-focus-then-tap-to-click → **degraded but
 *    fully navigable**, and phones are served by the separate Flutter app (ooustream-mobile).
 *
 * So every inconclusive case falls to TV. Note a real phone is never inconclusive — it always
 * has a touchscreen — so this rule cannot capture one.
 */
object DeviceUtils {

    /** Deprecated in the SDK since API 21 but still reported by real TV images; referenced as a
     *  literal to avoid the deprecation warning on [PackageManager.FEATURE_TELEVISION]. */
    private const val FEATURE_TELEVISION_LEGACY = "android.hardware.type.television"

    @Volatile private var cachedIsTv: Boolean? = null
    @Volatile private var cachedSignals: String? = null

    /**
     * True when the app should render its D-pad / 10-foot experience.
     *
     * Result is computed once and cached — none of the inputs can change without the process
     * being recreated. Thread-safe via double-checked locking.
     */
    fun isTV(context: Context): Boolean {
        cachedIsTv?.let { return it }
        return synchronized(this) {
            cachedIsTv ?: computeIsTv(context.applicationContext ?: context)
                .also { cachedIsTv = it }
        }
    }

    fun isPhone(context: Context): Boolean {
        if (isTV(context)) return false
        return context.resources.configuration.smallestScreenWidthDp < 600
    }

    fun isTablet(context: Context): Boolean {
        if (isTV(context)) return false
        return context.resources.configuration.smallestScreenWidthDp >= 600
    }

    private fun computeIsTv(ctx: Context): Boolean {
        val pm = ctx.packageManager
        val config = ctx.resources.configuration

        // (1) Certified TV system image. Sufficient, not necessary — Fire TV sets it, the
        //     Allwinner box does not.
        val uiModeTv = (ctx.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager)
            ?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION

        // (2) Declared TV system features. Also sufficient-not-necessary: present on Fire TV /
        //     Google TV, absent on uncertified AOSP boxes.
        val leanback = pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
        val televisionFeature = pm.hasSystemFeature(FEATURE_TELEVISION_LEGACY)

        // (3) No touchscreen — the signal that actually catches generic boxes. Two independent
        //     sources are OR-ed so one buggy/omitted value cannot strand the device in "phone"
        //     mode (OR is the fail-safe direction: it can only push toward TV).
        val noTouchFeature = !pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
        val noTouchConfig = config.touchscreen == Configuration.TOUCHSCREEN_NOTOUCH
        val noTouch = noTouchFeature || noTouchConfig

        val result = uiModeTv || leanback || televisionFeature || noTouch

        cachedSignals = "uiModeTv=$uiModeTv leanback=$leanback tvFeature=$televisionFeature " +
            "noTouchFeature=$noTouchFeature noTouchConfig=$noTouchConfig " +
            "swDp=${config.smallestScreenWidthDp} => isTV=$result"

        return result
    }

    /**
     * One-line dump of every classification input, for `StreamDiagnosticLogger` / debug reports.
     * The next unknown box is then self-diagnosing instead of needing a live adb session.
     */
    fun describe(context: Context): String {
        isTV(context)
        return cachedSignals ?: "unavailable"
    }

    /** Force a value (or `null` to restore real detection). Tests only. */
    @VisibleForTesting
    fun setTvOverrideForTest(value: Boolean?) {
        cachedIsTv = value
        if (value == null) cachedSignals = null
    }
}
