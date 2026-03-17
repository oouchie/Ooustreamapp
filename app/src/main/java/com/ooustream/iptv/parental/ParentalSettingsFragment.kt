package com.ooustream.iptv.parental

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ooustream.iptv.R
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class ParentalSettingsFragment : Fragment() {

    private val viewModel: ParentalSettingsViewModel by viewModels()

    private lateinit var tabLive: TextView
    private lateinit var tabVod: TextView
    private lateinit var tabSeries: TextView
    private lateinit var sectionHeader: TextView
    private lateinit var categoryList: RecyclerView
    private lateinit var statusText: TextView

    private val adapter = CategoryToggleAdapter { item ->
        viewModel.toggleCategory(item)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_parental_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        tabLive = view.findViewById(R.id.tab_live)
        tabVod = view.findViewById(R.id.tab_vod)
        tabSeries = view.findViewById(R.id.tab_series)
        sectionHeader = view.findViewById(R.id.section_header)
        categoryList = view.findViewById(R.id.category_list)
        statusText = view.findViewById(R.id.status_text)

        categoryList.layoutManager = LinearLayoutManager(requireContext())
        categoryList.adapter = adapter

        setupTabs()
        setupQuickActions(view)
        observeState()
    }

    private fun setupTabs() {
        val tabClick = View.OnClickListener { v ->
            val section = when (v.id) {
                R.id.tab_live -> "live"
                R.id.tab_vod -> "vod"
                R.id.tab_series -> "series"
                else -> return@OnClickListener
            }
            viewModel.selectSection(section)
        }
        tabLive.setOnClickListener(tabClick)
        tabVod.setOnClickListener(tabClick)
        tabSeries.setOnClickListener(tabClick)
    }

    private fun setupQuickActions(view: View) {
        view.findViewById<Button>(R.id.btn_block_adult).setOnClickListener {
            showBlockAdultConfirmation()
        }

        view.findViewById<Button>(R.id.btn_allow_all).setOnClickListener {
            showAllowAllConfirmation()
        }

        view.findViewById<Button>(R.id.btn_change_pin).setOnClickListener {
            requireActivity().supportFragmentManager.beginTransaction()
                .replace(
                    R.id.main_container,
                    ParentalPinFragment.newInstance(ParentalPinFragment.MODE_CHANGE)
                )
                .addToBackStack(null)
                .commit()
        }
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.categoryItems.collect { items ->
                        adapter.submitList(items)
                        val blockedCount = items.count { it.isBlocked }
                        val totalCount = items.size
                        if (blockedCount > 0) {
                            sectionHeader.text = "$blockedCount of $totalCount categories blocked"
                        } else {
                            sectionHeader.text = "$totalCount categories — all allowed"
                        }
                    }
                }
                launch {
                    viewModel.activeSection.collect { section ->
                        updateTabAppearance(section)
                    }
                }
                launch {
                    viewModel.blockedCounts.collect { (live, vod, series) ->
                        updateTabBadges(live, vod, series)
                        val total = live + vod + series
                        statusText.text = if (total > 0) "ON — $total blocked" else "ON"
                    }
                }
                launch {
                    viewModel.toastEvent.collect { message ->
                        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
                    }
                }
                launch {
                    viewModel.error.collect { message ->
                        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun updateTabAppearance(activeSection: String) {
        val tabs = listOf(tabLive to "live", tabVod to "vod", tabSeries to "series")
        for ((tab, section) in tabs) {
            if (section == activeSection) {
                tab.setBackgroundResource(R.drawable.bg_tab_active)
                tab.setTextColor(0xFFFFFFFF.toInt())
            } else {
                tab.setBackgroundResource(R.drawable.bg_tab_inactive)
                tab.setTextColor(0x80FFFFFF.toInt())
            }
        }
    }

    private fun updateTabBadges(live: Int, vod: Int, series: Int) {
        tabLive.text = if (live > 0) "Live TV ($live)" else "Live TV"
        tabVod.text = if (vod > 0) "Movies ($vod)" else "Movies"
        tabSeries.text = if (series > 0) "Series ($series)" else "Series"
    }

    private fun showBlockAdultConfirmation() {
        AlertDialog.Builder(requireContext())
            .setTitle("Block All Adult Content?")
            .setMessage(
                "This will automatically detect and block all categories containing " +
                        "adult content across Live TV, Movies, and Series."
            )
            .setPositiveButton("Block All Adult") { _, _ ->
                viewModel.blockAllAdult()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showAllowAllConfirmation() {
        val sectionLabel = when (viewModel.activeSection.value) {
            "live" -> "Live TV"
            "vod" -> "Movies"
            "series" -> "Series"
            else -> "this section"
        }
        AlertDialog.Builder(requireContext())
            .setTitle("Allow All in $sectionLabel?")
            .setMessage(
                "This will unblock ALL categories in $sectionLabel, " +
                        "including any adult content. Are you sure?"
            )
            .setPositiveButton("Allow All") { _, _ ->
                viewModel.allowAllInSection()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
