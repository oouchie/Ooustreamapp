package com.ooustream.iptv.common

import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.HorizontalGridView
import androidx.leanback.widget.VerticalGridView

/**
 * Safe adapter update and focus restoration utilities.
 *
 * Prevents IllegalArgumentException ("parameter must be a descendant of this view")
 * caused by D-pad focus navigation running on views that were removed during
 * adapter clear/rebuild operations.
 */

/**
 * Atomically replaces all items in an ArrayObjectAdapter.
 * Uses setItems() to minimize view churn and avoid the focus-loss window
 * that exists between clear() and add() calls.
 */
fun <T> ArrayObjectAdapter.safeReplaceAll(newItems: List<T>) {
    val currentSize = size()
    if (newItems.isEmpty()) {
        if (currentSize > 0) clear()
        return
    }
    // Replace existing items in-place where possible, then add/remove the difference
    val newSize = newItems.size
    val minSize = minOf(currentSize, newSize)
    for (i in 0 until minSize) {
        replace(i, newItems[i] as Any)
    }
    if (newSize > currentSize) {
        for (i in currentSize until newSize) {
            add(newItems[i] as Any)
        }
    } else if (currentSize > newSize) {
        removeItems(newSize, currentSize - newSize)
    }
}

/**
 * Safely sets the selected position on a HorizontalGridView,
 * coercing to valid bounds. No-op if adapter is empty.
 */
fun HorizontalGridView.safeSetSelectedPosition(position: Int, adapterSize: Int) {
    if (adapterSize <= 0) return
    selectedPosition = position.coerceIn(0, adapterSize - 1)
}

/**
 * Safely sets the selected position on a VerticalGridView,
 * coercing to valid bounds. No-op if adapter is empty.
 */
fun VerticalGridView.safeSetSelectedPosition(position: Int, adapterSize: Int) {
    if (adapterSize <= 0) return
    selectedPosition = position.coerceIn(0, adapterSize - 1)
}
