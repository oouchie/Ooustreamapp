package com.ooustream.iptv.data.remote

import com.google.gson.Gson
import com.google.gson.JsonParseException
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.ooustream.iptv.data.model.VodStream
import java.io.Reader

/**
 * Reads a `get_vod_streams` JSON array one movie at a time, handing each to [onItem] and keeping
 * nothing itself. The caller decides what to retain — so a row it drops (an adult title, say) is
 * garbage the moment [onItem] returns instead of sitting in a 100k-element list.
 *
 * Uses the app's own [Gson] adapter for [VodStream], so field names and alternates
 * (`tmdb` / `tmdb_id`) match the Retrofit path exactly.
 */
object VodListReader {

    fun read(source: Reader, gson: Gson, onItem: (VodStream) -> Unit) {
        val adapter = gson.getAdapter(VodStream::class.java)
        JsonReader(source).use { reader ->
            reader.isLenient = true
            // Retrofit's List<VodStream> conversion fails on anything but an array; keep that
            // behaviour so callers' error handling is unchanged.
            if (reader.peek() != JsonToken.BEGIN_ARRAY) {
                throw JsonParseException("get_vod_streams: expected an array, got ${reader.peek()}")
            }
            reader.beginArray()
            while (reader.hasNext()) {
                val item = adapter.read(reader) ?: continue
                onItem(item)
            }
            reader.endArray()
        }
    }

    /**
     * Shrinks movies as they are read, for lists that are kept in memory.
     *
     * The provider repeats a handful of values across 135k movies ("movie", the category id,
     * "mkv", the rating, the added date), and Gson makes a new String for every one of them:
     * about 100 MB of heap for the bulk list on 2026-11-04, on 128-256 MB heaps. One shared copy
     * per distinct value, plus dropping the three fields nothing reads (num, custom_sid,
     * direct_source), cuts that sharply. Use one Slimmer per list read; it is not thread-safe
     * and its pool is dropped with it.
     */
    class Slimmer {
        private val pool = HashMap<Any, Any>(256)

        @Suppress("UNCHECKED_CAST")
        private fun <T : Any> shared(value: T?): T? =
            if (value == null) null else pool.getOrPut(value) { value } as T

        fun slim(movie: VodStream): VodStream = movie.copy(
            num = null,
            streamType = shared(movie.streamType),
            rating = shared(movie.rating),
            rating5based = shared(movie.rating5based),
            added = shared(movie.added),
            categoryId = shared(movie.categoryId),
            containerExtension = shared(movie.containerExtension),
            customSid = null,
            directSource = null
        )
    }
}
