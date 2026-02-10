package com.ooustream.iptv.data.remote

import android.util.Log
import com.google.gson.*
import com.ooustream.iptv.data.model.*
import java.lang.reflect.Type

/**
 * Custom Gson deserializer for SeriesInfo that handles Xtream API inconsistencies:
 * - "episodes": "" (empty string instead of object)
 * - "episodes": [] (array instead of object)
 * - "episodes": null
 * - "episodes": { "1": [...] } (normal case)
 * - "info": "" or "info": [] instead of object
 *
 * Uses a standalone Gson instance for sub-object parsing to avoid R8 ClassCastException
 * with context.deserialize() generic type erasure.
 */
class SafeSeriesInfoDeserializer : JsonDeserializer<SeriesInfo> {

    companion object {
        private const val TAG = "SafeSeriesDeserializer"
    }

    // Standalone Gson for sub-objects — avoids R8 context.deserialize() ClassCastException
    private val subGson = Gson()

    override fun deserialize(
        json: JsonElement,
        typeOfT: Type,
        context: JsonDeserializationContext
    ): SeriesInfo {
        val obj = json.asJsonObject

        // Parse "info" safely
        val info: SeriesDetail? = try {
            val infoElement = obj.get("info")
            if (infoElement != null && infoElement.isJsonObject) {
                subGson.fromJson(infoElement, SeriesDetail::class.java)
            } else {
                if (infoElement != null && !infoElement.isJsonNull) {
                    Log.i(TAG, "info field is not an object: ${infoElement.javaClass.simpleName}")
                }
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse info", e)
            null
        }

        // Parse "seasons" safely
        val seasons: List<Season>? = try {
            val seasonsElement = obj.get("seasons")
            if (seasonsElement != null && seasonsElement.isJsonArray) {
                val list = mutableListOf<Season>()
                seasonsElement.asJsonArray.forEach { elem ->
                    try {
                        list.add(subGson.fromJson(elem, Season::class.java))
                    } catch (e: Exception) {
                        Log.i(TAG, "Skipping malformed season entry", e)
                    }
                }
                list
            } else {
                if (seasonsElement != null && !seasonsElement.isJsonNull) {
                    Log.i(TAG, "seasons field is not an array: ${seasonsElement.javaClass.simpleName}")
                }
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse seasons", e)
            null
        }

        // Parse "episodes" safely — this is the critical part
        val episodes: Map<String, List<Episode>>? = try {
            val episodesElement = obj.get("episodes")
            when {
                episodesElement == null || episodesElement.isJsonNull -> {
                    Log.i(TAG, "episodes is null")
                    null
                }
                episodesElement.isJsonPrimitive -> {
                    // Handle "episodes": "" or "episodes": "some string"
                    Log.i(TAG, "episodes is a primitive (likely empty string): '${episodesElement.asString}'")
                    null
                }
                episodesElement.isJsonArray -> {
                    // Handle "episodes": [] (wrong type — should be object)
                    Log.i(TAG, "episodes is an array instead of object, size=${episodesElement.asJsonArray.size()}")
                    null
                }
                episodesElement.isJsonObject -> {
                    val map = mutableMapOf<String, List<Episode>>()
                    episodesElement.asJsonObject.entrySet().forEach { (key, value) ->
                        try {
                            if (value.isJsonArray) {
                                val episodeList = mutableListOf<Episode>()
                                value.asJsonArray.forEach { epElem ->
                                    try {
                                        episodeList.add(subGson.fromJson(epElem, Episode::class.java))
                                    } catch (e: Exception) {
                                        Log.i(TAG, "Skipping malformed episode in season $key", e)
                                    }
                                }
                                map[key] = episodeList
                            }
                        } catch (e: Exception) {
                            Log.i(TAG, "Failed to parse episodes for season key=$key", e)
                        }
                    }
                    Log.i(TAG, "Parsed episodes: ${map.size} seasons, keys=${map.keys}")
                    map
                }
                else -> null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse episodes entirely", e)
            null
        }

        return SeriesInfo(
            seasons = seasons,
            info = info,
            episodes = episodes
        )
    }
}
