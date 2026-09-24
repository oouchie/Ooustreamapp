package com.ooustream.iptv.catchup

import android.os.Bundle
import android.text.format.DateFormat
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.leanback.widget.VerticalGridView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.ooustream.iptv.R
import com.ooustream.iptv.common.ChannelDisplayHelper
import com.ooustream.iptv.common.FragmentTransitions
import com.ooustream.iptv.common.TransitionDirection
import com.ooustream.iptv.data.model.ContentType
import com.ooustream.iptv.data.repository.CatchUpProgramme
import com.ooustream.iptv.player.OoustreamPlaybackFragment
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * Catch up (5.0) — replay past shows on the channels the provider archives.
 *
 * Left: archived channels (parental-filtered). Right: that channel's finished shows, newest first,
 * grouped by day. OK on a show plays it from its start via the panel's timeshift URL (built in UTC —
 * see StreamUrlBuilder.timeshift). Playback runs as VOD with `catchUp=true`: seekable, but writes no
 * watch history (the stream id is a CHANNEL id).
 */
@AndroidEntryPoint
class CatchUpFragment : Fragment() {

    private val viewModel: CatchUpViewModel by viewModels()

    private lateinit var subtitle: TextView
    private lateinit var channelsGrid: VerticalGridView
    private lateinit var programmesGrid: VerticalGridView
    private lateinit var heading: TextView
    private lateinit var message: TextView
    private lateinit var loading: ProgressBar

    private var selectedChannel: CatchUpViewModel.ChannelRow? = null
    private var selectedChannelIndex = 0

    private val channelAdapter = ChannelAdapter(
        onFocused = { row, index ->
            selectedChannel = row
            selectedChannelIndex = index
            heading.text = row.stream.name
            viewModel.showChannel(row.stream.streamId)
        },
        onClicked = { row ->
            viewModel.showChannel(row.stream.streamId, debounceMs = 0)
            programmesGrid.post { if (programmeAdapter.itemCount > 0) programmesGrid.requestFocus() }
        }
    )
    private val programmeAdapter = ProgrammeAdapter { play(it) }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_catch_up, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        subtitle = view.findViewById(R.id.cu_subtitle)
        channelsGrid = view.findViewById(R.id.cu_channels)
        programmesGrid = view.findViewById(R.id.cu_programmes)
        heading = view.findViewById(R.id.cu_channel_heading)
        message = view.findViewById(R.id.cu_message)
        loading = view.findViewById(R.id.cu_loading)

        channelsGrid.adapter = channelAdapter
        channelsGrid.itemAnimator = null
        programmesGrid.adapter = programmeAdapter
        programmesGrid.itemAnimator = null
        programmesGrid.windowAlignment = VerticalGridView.WINDOW_ALIGN_BOTH_EDGE

        // LEFT out of the shows list goes back to the channel being browsed, not whichever
        // channel row happens to be level with the focused show.
        programmesGrid.setOnKeyInterceptListener { e ->
            if (e.keyCode != KeyEvent.KEYCODE_DPAD_LEFT) return@setOnKeyInterceptListener false
            if (e.action == KeyEvent.ACTION_DOWN) {
                channelsGrid.selectedPosition = selectedChannelIndex
                channelsGrid.requestFocus()
            }
            true
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.channels.collect(::renderChannels) }
                launch { viewModel.programmes.collect(::renderProgrammes) }
            }
        }
        viewModel.load()
    }

    private fun renderChannels(list: List<CatchUpViewModel.ChannelRow>?) {
        if (list == null) {
            loading.visibility = View.VISIBLE
            subtitle.text = getString(R.string.cu_loading_channels)
            return
        }
        loading.visibility = View.GONE
        if (list.isEmpty()) {
            subtitle.text = getString(R.string.cu_no_channels)
            heading.text = ""
            return
        }
        subtitle.text = if (viewModel.archiveDays > 0)
            getString(R.string.cu_subtitle_days, viewModel.archiveDays, list.size)
        else getString(R.string.cu_subtitle, list.size)
        val first = channelAdapter.itemCount == 0
        channelAdapter.submitList(list) {
            // Synchronously in-bounds — see project_vod_grid_position_minus1.
            channelsGrid.selectedPosition = selectedChannelIndex.coerceIn(0, list.size - 1)
            if (first) channelsGrid.requestFocus()
        }
    }

    private fun renderProgrammes(state: CatchUpViewModel.ProgrammesState) {
        when (state) {
            CatchUpViewModel.ProgrammesState.Idle -> Unit
            CatchUpViewModel.ProgrammesState.Loading -> showMessage(getString(R.string.cu_loading))
            CatchUpViewModel.ProgrammesState.Failed -> {
                programmeAdapter.submitList(emptyList())
                showMessage(getString(R.string.cu_failed))
            }
            is CatchUpViewModel.ProgrammesState.Loaded -> {
                val rows = groupByDay(state.channelId, state.programmes)
                programmeAdapter.submitList(rows) {
                    if (rows.isNotEmpty()) programmesGrid.selectedPosition = 1.coerceAtMost(rows.size - 1)
                }
                if (rows.isEmpty()) showMessage(getString(R.string.cu_none_for_channel)) else showMessage(null)
            }
        }
    }

    private fun showMessage(text: String?) {
        message.visibility = if (text == null) View.GONE else View.VISIBLE
        message.text = text
        programmesGrid.visibility = if (text == null) View.VISIBLE else View.INVISIBLE
    }

    private fun groupByDay(channelId: Int, programmes: List<CatchUpProgramme>): List<Row> {
        val out = ArrayList<Row>()
        var lastDay = ""
        for (p in programmes) {
            val day = dayLabel(p.startEpochSec)
            if (day != lastDay) {
                out += Row.Day(day)
                lastDay = day
            }
            out += Row.Show(channelId, p)
        }
        return out
    }

    /** "Today" / "Yesterday" / "Tue 22 Sep" — in the viewer's LOCAL time, unlike the URL. */
    private fun dayLabel(epochSec: Long): String {
        val cal = Calendar.getInstance().apply { timeInMillis = epochSec * 1000 }
        val today = Calendar.getInstance()
        val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
        fun same(a: Calendar, b: Calendar) =
            a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
        return when {
            same(cal, today) -> getString(R.string.cu_today)
            same(cal, yesterday) -> getString(R.string.cu_yesterday)
            else -> DateFormat.format("EEE d MMM", cal).toString()
        }
    }

    private fun play(show: Row.Show) {
        val channel = selectedChannel?.takeIf { it.stream.streamId == show.channelId } ?: return
        val url = viewModel.buildUrl(show.channelId, show.programme)
        val fragment = OoustreamPlaybackFragment.newInstance(
            streamUrl = url,
            contentType = ContentType.VOD,
            streamId = show.channelId.toString(),
            streamName = "${show.programme.title} (${channel.stream.name})",
            streamIcon = channel.stream.streamIcon.orEmpty(),
            forceStartFromBeginning = true,
            catchUp = true
        )
        requireActivity().supportFragmentManager.beginTransaction()
            .also { FragmentTransitions.apply(it, TransitionDirection.PLAYER) }
            .replace(R.id.main_container, fragment)
            .addToBackStack(null)
            .commit()
    }

    // ── Adapters ─────────────────────────────────────────────────────────────

    sealed interface Row {
        data class Day(val label: String) : Row
        data class Show(val channelId: Int, val programme: CatchUpProgramme) : Row
    }

    private class ChannelAdapter(
        private val onFocused: (CatchUpViewModel.ChannelRow, Int) -> Unit,
        private val onClicked: (CatchUpViewModel.ChannelRow) -> Unit
    ) : ListAdapter<CatchUpViewModel.ChannelRow, ChannelAdapter.Holder>(
        object : DiffUtil.ItemCallback<CatchUpViewModel.ChannelRow>() {
            override fun areItemsTheSame(o: CatchUpViewModel.ChannelRow, n: CatchUpViewModel.ChannelRow) =
                o.stream.streamId == n.stream.streamId
            override fun areContentsTheSame(o: CatchUpViewModel.ChannelRow, n: CatchUpViewModel.ChannelRow) = o == n
        }
    ) {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_catchup_channel, parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(getItem(position))

        inner class Holder(v: View) : RecyclerView.ViewHolder(v) {
            private val logo: ImageView = v.findViewById(R.id.cc_logo)
            private val initials: TextView = v.findViewById(R.id.cc_initials)
            private val name: TextView = v.findViewById(R.id.cc_name)
            private val category: TextView = v.findViewById(R.id.cc_category)

            init {
                v.setOnFocusChangeListener { _, has ->
                    val pos = bindingAdapterPosition
                    if (has && pos != RecyclerView.NO_POSITION) onFocused(getItem(pos), pos)
                }
                v.setOnClickListener {
                    bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }?.let { onClicked(getItem(it)) }
                }
            }

            fun bind(row: CatchUpViewModel.ChannelRow) {
                name.text = row.stream.name
                category.text = row.categoryName
                category.visibility = if (row.categoryName.isBlank()) View.GONE else View.VISIBLE
                ChannelDisplayHelper.loadLogo(logo, initials, row.stream.streamIcon, row.stream.name)
            }
        }
    }

    private class ProgrammeAdapter(
        private val onPlay: (Row.Show) -> Unit
    ) : ListAdapter<Row, RecyclerView.ViewHolder>(
        object : DiffUtil.ItemCallback<Row>() {
            override fun areItemsTheSame(o: Row, n: Row) = when {
                o is Row.Day && n is Row.Day -> o.label == n.label
                o is Row.Show && n is Row.Show ->
                    o.channelId == n.channelId && o.programme.startEpochSec == n.programme.startEpochSec
                else -> false
            }
            override fun areContentsTheSame(o: Row, n: Row) = o == n
        }
    ) {
        override fun getItemViewType(position: Int) = if (getItem(position) is Row.Day) 0 else 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inf = LayoutInflater.from(parent.context)
            return if (viewType == 0) DayHolder(inf.inflate(R.layout.item_catchup_day, parent, false) as TextView)
            else ShowHolder(inf.inflate(R.layout.item_catchup_programme, parent, false))
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = getItem(position)) {
                is Row.Day -> (holder as DayHolder).tv.text = row.label
                is Row.Show -> (holder as ShowHolder).bind(row)
            }
        }

        class DayHolder(val tv: TextView) : RecyclerView.ViewHolder(tv)

        inner class ShowHolder(v: View) : RecyclerView.ViewHolder(v) {
            private val time: TextView = v.findViewById(R.id.cp_time)
            private val title: TextView = v.findViewById(R.id.cp_title)
            private val length: TextView = v.findViewById(R.id.cp_length)
            private val desc: TextView = v.findViewById(R.id.cp_desc)

            init {
                v.setOnClickListener {
                    bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }
                        ?.let { (getItem(it) as? Row.Show)?.let(onPlay) }
                }
                // Description opens up only on the focused show, like the series episode list.
                v.setOnFocusChangeListener { _, has -> desc.maxLines = if (has) 3 else 1 }
            }

            fun bind(row: Row.Show) {
                val p = row.programme
                time.text = DateFormat.getTimeFormat(itemView.context).format(java.util.Date(p.startEpochSec * 1000))
                title.text = p.title
                val mins = ((p.stopEpochSec - p.startEpochSec) / 60).toInt()
                length.text = if (mins >= 60 && mins % 60 == 0) "${mins / 60} h"
                    else if (mins > 60) "${mins / 60} h ${mins % 60} min" else "$mins min"
                desc.text = p.description
                desc.visibility = if (p.description.isBlank()) View.GONE else View.VISIBLE
                desc.maxLines = if (itemView.hasFocus()) 3 else 1
            }
        }
    }
}
