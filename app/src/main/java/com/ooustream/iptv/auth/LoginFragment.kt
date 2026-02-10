package com.ooustream.iptv.auth

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.ooustream.iptv.R
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class LoginFragment : Fragment() {

    private val viewModel: AuthViewModel by viewModels()

    private lateinit var usernameInput: EditText
    private lateinit var passwordInput: EditText
    private lateinit var loginButton: Button
    private lateinit var statusText: TextView

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

        // Login button click — server URL is hardcoded
        loginButton.setOnClickListener {
            val serverUrl = getString(R.string.default_server_url)
            val username = usernameInput.text.toString().trim()
            val password = passwordInput.text.toString().trim()
            viewModel.login(serverUrl, username, password)
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
                v.alpha = 0.9f
            }
        }

        // Slight dim on unfocused state to make focus more apparent
        loginButton.alpha = 0.9f

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
                            loginButton.alpha = 0.9f
                        }
                        is AuthState.Idle -> {
                            statusText.visibility = View.GONE
                            loginButton.isEnabled = true
                            loginButton.alpha = 0.9f
                        }
                    }
                }
            }
        }
    }
}
