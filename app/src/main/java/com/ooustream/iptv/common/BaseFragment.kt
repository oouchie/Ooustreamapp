package com.ooustream.iptv.common

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.ooustream.iptv.KeyEventHandler

abstract class BaseFragment : Fragment(), KeyEventHandler {

    protected fun showToast(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    protected fun showError(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
    }

    override fun onKeyEvent(keyCode: Int): Boolean = false

    /**
     * Override this to indicate whether the fragment's current focus state
     * is at the left edge, allowing the quick sidebar to open on DPAD_LEFT.
     */
    open fun isAtLeftEdge(): Boolean = false
}
