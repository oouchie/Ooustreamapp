package com.ooustream.iptv.speedtest

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.ooustream.iptv.KeyEventHandler
import com.ooustream.iptv.R
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.util.Locale

@AndroidEntryPoint
class SpeedTestFragment : Fragment(), KeyEventHandler {

    private val viewModel: SpeedTestViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_speed_test, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val pingResult = view.findViewById<TextView>(R.id.ping_result)
        val downloadResult = view.findViewById<TextView>(R.id.download_result)
        val ratingBadge = view.findViewById<TextView>(R.id.rating_badge)
        val runTestButton = view.findViewById<Button>(R.id.run_test_button)
        val statusText = view.findViewById<TextView>(R.id.status_text)
        val progressBar = view.findViewById<ProgressBar>(R.id.progress_bar)

        runTestButton.setOnClickListener {
            viewModel.startTest()
        }

        // Observe test state
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.testState.collect { state ->
                    when (state) {
                        is TestState.Idle -> {
                            runTestButton.isEnabled = true
                            runTestButton.text = "Run Test"
                            statusText.visibility = View.GONE
                            progressBar.visibility = View.GONE
                            pingResult.text = "Ping: -- ms"
                            downloadResult.text = "Download: -- Mbps"
                            ratingBadge.visibility = View.GONE
                        }

                        is TestState.Testing -> {
                            runTestButton.isEnabled = false
                            runTestButton.text = "Testing..."
                            statusText.visibility = View.VISIBLE
                            statusText.text = state.phase
                            progressBar.visibility = View.VISIBLE
                            ratingBadge.visibility = View.GONE
                        }

                        is TestState.Complete -> {
                            runTestButton.isEnabled = true
                            runTestButton.text = "Run Test"
                            statusText.visibility = View.GONE
                            progressBar.visibility = View.GONE

                            val result = state.result

                            pingResult.text = String.format(
                                Locale.US, "Ping: %d ms", result.pingMs
                            )
                            downloadResult.text = String.format(
                                Locale.US, "Download: %.1f Mbps", result.downloadMbps
                            )

                            ratingBadge.visibility = View.VISIBLE
                            ratingBadge.text = result.rating.label
                            ratingBadge.setTextColor(result.rating.color)

                            // Set badge background tint to a semi-transparent version of the rating color
                            val bgColor = (result.rating.color and 0x00FFFFFF) or 0x33000000
                            ratingBadge.background?.setTint(bgColor)
                        }

                        is TestState.Error -> {
                            runTestButton.isEnabled = true
                            runTestButton.text = "Retry"
                            statusText.visibility = View.VISIBLE
                            statusText.text = state.message
                            statusText.setTextColor(0xFFEF4444.toInt())
                            progressBar.visibility = View.GONE
                            ratingBadge.visibility = View.GONE
                        }
                    }
                }
            }
        }

        // Observe toast events
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.toastEvent.collect { msg ->
                    Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onKeyEvent(keyCode: Int): Boolean = false
}
