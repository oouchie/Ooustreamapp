package com.ooustream.iptv.onboarding

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.ooustream.iptv.R

class OnboardingOverlay(context: Context) : FrameLayout(context) {

    private val stepIndicator: TextView
    private val icon: ImageView
    private val title: TextView
    private val subtitle: TextView
    private val hint: TextView
    private val skipButton: TextView
    private var currentStep = 0
    private var pulseAnimator: ObjectAnimator? = null
    var onCompleted: (() -> Unit)? = null

    private data class Step(
        val iconRes: Int,
        val title: String,
        val subtitle: String,
        val hint: String,
        val acceptedKeys: Set<Int>
    )

    private val steps = listOf(
        Step(
            iconRes = R.drawable.ic_live_tv,
            title = "Navigate with D-pad",
            subtitle = "Use the arrow keys on your remote to move around",
            hint = "Press any arrow key...",
            acceptedKeys = setOf(
                KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_RIGHT
            )
        ),
        Step(
            iconRes = R.drawable.ic_search,
            title = "Select Items",
            subtitle = "Press the center button to select and open content",
            hint = "Press SELECT / OK...",
            acceptedKeys = setOf(
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER
            )
        ),
        Step(
            iconRes = R.drawable.ic_favorites,
            title = "Go Back",
            subtitle = "Press BACK to return to the previous screen",
            hint = "Press BACK...",
            acceptedKeys = setOf(KeyEvent.KEYCODE_BACK)
        ),
        Step(
            iconRes = R.drawable.ic_movies,
            title = "Quick Options",
            subtitle = "Long-press on channels to add favorites",
            hint = "Press any key to continue...",
            acceptedKeys = emptySet() // Any key advances
        )
    )

    init {
        LayoutInflater.from(context).inflate(R.layout.overlay_onboarding_step, this, true)
        stepIndicator = findViewById(R.id.onboarding_step_indicator)
        icon = findViewById(R.id.onboarding_icon)
        title = findViewById(R.id.onboarding_title)
        subtitle = findViewById(R.id.onboarding_subtitle)
        hint = findViewById(R.id.onboarding_hint)
        skipButton = findViewById(R.id.onboarding_skip)

        skipButton.setOnClickListener { complete() }
        skipButton.setOnFocusChangeListener { v, hasFocus ->
            (v as TextView).setTextColor(
                if (hasFocus) 0xFFFFFFFF.toInt() else 0xFF6B7280.toInt()
            )
        }

        visibility = GONE
    }

    fun show() {
        visibility = VISIBLE
        alpha = 0f
        animate().alpha(1f).setDuration(300).start()
        currentStep = 0
        showStep(currentStep)
    }

    private fun showStep(index: Int) {
        if (index >= steps.size) {
            complete()
            return
        }

        val step = steps[index]
        stepIndicator.text = "${index + 1} of ${steps.size}"
        icon.setImageResource(step.iconRes)
        title.text = step.title
        subtitle.text = step.subtitle
        hint.text = step.hint

        // Pulse animation on hint text
        pulseAnimator?.cancel()
        pulseAnimator = ObjectAnimator.ofFloat(hint, "alpha", 1f, 0.3f).apply {
            duration = 800
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            start()
        }

        // Fade transition between steps
        icon.alpha = 0f
        title.alpha = 0f
        subtitle.alpha = 0f
        icon.animate().alpha(1f).setDuration(300).setStartDelay(100).start()
        title.animate().alpha(1f).setDuration(300).setStartDelay(200).start()
        subtitle.animate().alpha(1f).setDuration(300).setStartDelay(300).start()
    }

    /**
     * Handle a key event routed from the fragment/activity.
     * Returns true if the event was consumed by the onboarding overlay.
     */
    fun handleKeyEvent(keyCode: Int): Boolean {
        if (visibility != VISIBLE) return false

        val step = steps.getOrNull(currentStep) ?: return false

        // Let DPAD_CENTER/ENTER through when skip button has focus so its click listener fires
        if (skipButton.isFocused &&
            (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
        ) {
            return false
        }

        // Allow D-pad up/down for focus navigation to/from skip button
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            return false
        }

        if (step.acceptedKeys.isEmpty() || keyCode in step.acceptedKeys) {
            currentStep++
            showStep(currentStep)
            return true
        }

        // Consume other keys while onboarding is active
        return true
    }

    private fun complete() {
        pulseAnimator?.cancel()
        animate().alpha(0f).setDuration(300).withEndAction {
            visibility = GONE
            onCompleted?.invoke()
        }.start()
    }

    val isShowing: Boolean get() = visibility == VISIBLE
}
