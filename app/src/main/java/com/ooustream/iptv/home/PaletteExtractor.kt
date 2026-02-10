package com.ooustream.iptv.home

import android.graphics.Bitmap
import android.graphics.Color
import androidx.palette.graphics.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object PaletteExtractor {
    suspend fun extractDominant(bitmap: Bitmap): Int = withContext(Dispatchers.Default) {
        try {
            val palette = Palette.from(bitmap)
                .maximumColorCount(8)
                .generate()
            palette.darkMutedSwatch?.rgb
                ?: palette.mutedSwatch?.rgb
                ?: palette.darkVibrantSwatch?.rgb
                ?: palette.dominantSwatch?.rgb
                ?: Color.TRANSPARENT
        } catch (e: Exception) {
            Color.TRANSPARENT
        }
    }
}
