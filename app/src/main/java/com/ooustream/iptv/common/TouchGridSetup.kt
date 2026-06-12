package com.ooustream.iptv.common

import android.graphics.Rect
import android.view.View
import androidx.leanback.widget.VerticalGridView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ooustream.iptv.R

/**
 * Configures a content grid for the device it's running on.
 *
 * On TV the layout inflates a Leanback [VerticalGridView] (D-pad focus scrolling, item alignment).
 * On a phone the layout inflates a plain [RecyclerView] — Leanback's grid does NOT scroll by finger
 * (it snaps content back to keep the "selected" item aligned), so the phone build hosts the same
 * ItemBridgeAdapter in a standard RecyclerView + GridLayoutManager, which touch-scrolls normally.
 *
 * Both inflate with the SAME id, and VerticalGridView IS-A RecyclerView, so callers do
 * `findViewById<RecyclerView>(id)` and route the Leanback-specific bits through here.
 */
object TouchGridSetup {

    /** Apply column count + spacing to either grid type. */
    fun configure(grid: RecyclerView, columns: Int, hSpacingPx: Int, vSpacingPx: Int) {
        if (grid is VerticalGridView) {
            grid.setNumColumns(columns)
            grid.setHorizontalSpacing(hSpacingPx)
            grid.setVerticalSpacing(vSpacingPx)
        } else {
            grid.layoutManager = GridLayoutManager(grid.context, columns)
            // GridLayoutManager has no built-in spacing — add it once.
            if (grid.getTag(R.id.tag_grid_spacing_added) == null) {
                grid.addItemDecoration(GridSpacingDecoration(columns, hSpacingPx, vSpacingPx))
                grid.setTag(R.id.tag_grid_spacing_added, true)
            }
            grid.clipToPadding = false
        }
    }

    /** Move selection (TV) / scroll position (phone) to [position], clamped. */
    fun setSelected(grid: RecyclerView, position: Int, itemCount: Int) {
        if (itemCount <= 0) return
        val pos = position.coerceIn(0, itemCount - 1)
        if (grid is VerticalGridView) {
            grid.selectedPosition = pos
        } else {
            grid.scrollToPosition(pos)
        }
    }

    /**
     * Phone-only: strip an item view's focusability (keeping it clickable) so a finger-tap fires
     * the click on the FIRST tap. Leanback item layouts set focusableInTouchMode=true for D-pad on
     * TV; on a touchscreen that makes the first tap only move focus ("have to double tap"). No-op on TV.
     */
    fun stripItemFocusForTouch(itemView: View) {
        if (!DeviceUtils.isTV(itemView.context)) {
            itemView.isFocusable = false
            itemView.isFocusableInTouchMode = false
        }
    }

    /** Current first-visible/selected position for save-on-leave. */
    fun currentPosition(grid: RecyclerView): Int =
        if (grid is VerticalGridView) grid.selectedPosition
        else (grid.layoutManager as? GridLayoutManager)?.findFirstVisibleItemPosition() ?: 0

    private class GridSpacingDecoration(
        private val columns: Int,
        private val hSpacing: Int,
        private val vSpacing: Int
    ) : RecyclerView.ItemDecoration() {
        override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
            val pos = parent.getChildAdapterPosition(view)
            if (pos == RecyclerView.NO_POSITION) return
            val col = pos % columns
            // Even horizontal gaps between columns; full vertical gap below each row.
            outRect.left = col * hSpacing / columns
            outRect.right = hSpacing - (col + 1) * hSpacing / columns
            if (pos >= columns) outRect.top = vSpacing
        }
    }
}
