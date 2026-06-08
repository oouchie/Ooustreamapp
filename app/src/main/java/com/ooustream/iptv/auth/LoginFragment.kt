package com.ooustream.iptv.auth

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.ooustream.iptv.R
import com.ooustream.iptv.common.DeviceUtils
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class LoginFragment : Fragment() {

    private val viewModel: AuthViewModel by viewModels()

    private lateinit var usernameInput: EditText
    private lateinit var passwordInput: EditText
    private lateinit var loginButton: Button
    private lateinit var statusText: TextView

    // Resting (unfocused) button alpha. TV dims slightly to make D-pad focus obvious; on a
    // phone there is no focus state so the button must stay at full opacity.
    private var restAlpha = 0.9f

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_login, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        usernameInput = view.findViewById(R.id.usernameInput)
        passwordInput = view.findViewById(R.id.passwordInput)
        loginButton = view.findViewById(R.id.loginButton)
        statusText = view.findViewById(R.id.statusText)

        val isTv = DeviceUtils.isTV(requireContext())
        restAlpha = if (isTv) 0.9f else 1.0f

        // Phone: widen the fixed 480dp card to fill a narrow screen (it would otherwise
        // overflow with no horizontal scroll). TV / tablet keep the 480dp centered card.
        if (!isTv) {
            view.findViewById<LinearLayout>(R.id.login_card)?.let { card ->
                card.layoutParams = card.layoutParams.apply {
                    width = ViewGroup.LayoutParams.MATCH_PARENT
                }
            }
        }

        // Login button click — server URL is hardcoded
        loginButton.setOnClickListener {
            val serverUrl = getString(R.string.default_server_url)
            val username = usernameInput.text.toString().trim()
            val password = passwordInput.text.toString().trim()
            viewModel.login(serverUrl, username, password)
        }

        // Phone fallback submit: with the keyboard up, the IME "Done" key must be able to
        // sign in even if the button is scrolled under the keyboard.
        passwordInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                loginButton.performClick()
                true
            } else {
                false
            }
        }

        // Focus animation for login button: scale up with gold outline on focus
        loginButton.setOnFocusChangeListener { v, hasFocus ->
            if (hasFocus) {
                val scaleX = ObjectAnimator.ofFloat(v, View.SCALE_X, 1.0f, 1.05f)
                val scaleY = ObjectAnimator.ofFloat(v, View.SCALE_Y, 1.0f, 1.05f)
                AnimatorSet().apply {
                    playTogether(scaleX, scaleY)
                    duration = 150
                    start()
                }
                // Gold outline effect via background tint overlay
                v.alpha = 1.0f
            } else {
                val scaleX = ObjectAnimator.ofFloat(v, View.SCALE_X, 1.05f, 1.0f)
                val scaleY = ObjectAnimator.ofFloat(v, View.SCALE_Y, 1.05f, 1.0f)
                AnimatorSet().apply {
                    playTogether(scaleX, scaleY)
                    duration = 150
                    start()
                }
                v.alpha = restAlpha
            }
        }

        // Resting alpha (full on phone, slight dim on TV to make focus apparent)
        loginButton.alpha = restAlpha

        // Auto-focus the username field for D-pad users. On a phone this would pop the IME
        // immediately over a non-scrolled card; let the user tap a field instead.
        if (isTv) {
            usernameInput.post { usernameInput.requestFocus() }
        }

        observeAuthState()
    }

    private fun observeAuthState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.authState.collect { state ->
                    when (state) {
                        is AuthState.Loading -> {
                            statusText.visibility = View.VISIBLE
                            statusText.text = getString(R.string.logging_in)
                            statusText.setTextColor(Color.parseColor("#9CA3AF"))
                            loginButton.isEnabled = false
                            loginButton.alpha = 0.5f
                        }
                        is AuthState.Success -> {
                            (activity as? com.ooustream.iptv.MainActivity)?.navigateToHome()
                        }
                        is AuthState.Error -> {
                            statusText.visibility = View.VISIBLE
                            statusText.text = state.message
                            statusText.setTextColor(Color.parseColor("#EF4444"))
                            loginButton.isEnabled = true
                            loginButton.alpha = restAlpha
                        }
                        is AuthState.Idle -> {
                            statusText.visibility = View.GONE
                            loginButton.isEnabled = true
                            loginButton.alpha = restAlpha
                        }
                    }
                }
            }
        }
    }
}
