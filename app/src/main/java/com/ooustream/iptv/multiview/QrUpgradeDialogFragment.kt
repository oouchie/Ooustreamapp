package com.ooustream.iptv.multiview

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.CountDownTimer
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import com.ooustream.iptv.R
import dagger.hilt.android.AndroidEntryPoint

/**
 * Full-screen QR upgrade dialog for Basic plan users.
 * Shows a QR code linking to the upgrade portal and a 5-minute countdown timer.
 * Auto-dismisses when timer expires or on Back key press.
 */
@AndroidEntryPoint
class QrUpgradeDialogFragment : DialogFragment() {

    private lateinit var qrCodeImage: ImageView
    private lateinit var countdownText: TextView
    private var countdownTimer: CountDownTimer? = null

    companion object {
        private const val UPGRADE_URL = "https://ooustick.com/subscribe/pro"
        private const val COUNTDOWN_DURATION_MS = 5 * 60 * 1000L // 5 minutes
        private const val COUNTDOWN_INTERVAL_MS = 1000L // 1 second
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        dialog.window?.requestFeature(Window.FEATURE_NO_TITLE)
        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.dialog_qr_upgrade, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Make dialog full-screen with transparent background
        dialog?.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }

        qrCodeImage = view.findViewById(R.id.qr_code_image)
        countdownText = view.findViewById(R.id.countdown_text)

        // Generate and display QR code
        val qrBitmap = QrCodeGenerator.generate(UPGRADE_URL, 512)
        qrCodeImage.setImageBitmap(qrBitmap)

        // Start countdown timer
        startCountdown()

        // Handle Back key to dismiss
        view.isFocusableInTouchMode = true
        view.requestFocus()
        view.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                dismiss()
                true
            } else {
                false
            }
        }
    }

    private fun startCountdown() {
        countdownTimer = object : CountDownTimer(COUNTDOWN_DURATION_MS, COUNTDOWN_INTERVAL_MS) {
            override fun onTick(millisUntilFinished: Long) {
                val seconds = (millisUntilFinished / 1000).toInt()
                val minutes = seconds / 60
                val remainingSeconds = seconds % 60
                countdownText.text = String.format("Offer expires in %d:%02d", minutes, remainingSeconds)
            }

            override fun onFinish() {
                dismiss()
            }
        }.start()
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }
    }

    override fun onDestroyView() {
        countdownTimer?.cancel()
        countdownTimer = null
        super.onDestroyView()
    }
}
