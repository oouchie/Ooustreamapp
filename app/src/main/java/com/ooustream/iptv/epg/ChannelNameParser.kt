package com.ooustream.iptv.epg

/**
 * Channel content type classification
 */
enum class ChannelContentType {
    SPORTS,
    NEWS,
    ENTERTAINMENT,
    KIDS,
    MOVIES,
    DOCUMENTARY,
    MUSIC,
    RELIGIOUS,
    UNKNOWN
}

/**
 * Parsed channel metadata extracted from channel name
 */
data class ParsedChannel(
    val network: String?,
    val isLocal: Boolean,
    val market: String?,
    val contentType: ChannelContentType,
    val quality: String?
)

/**
 * Stateless parser for extracting network brand and content type from messy IPTV channel names.
 * Handles country prefixes, quality suffixes, local affiliates, and network branding.
 */
object ChannelNameParser {

    private val NETWORK_MAP = mapOf(
        // Sports
        "ESPN" to ChannelContentType.SPORTS,
        "Fox Sports" to ChannelContentType.SPORTS,
        "NBC Sports" to ChannelContentType.SPORTS,
        "CBS Sports" to ChannelContentType.SPORTS,
        "NFL Network" to ChannelContentType.SPORTS,
        "NBA TV" to ChannelContentType.SPORTS,
        "MLB Network" to ChannelContentType.SPORTS,
        "NHL Network" to ChannelContentType.SPORTS,
        "beIN" to ChannelContentType.SPORTS,
        "Sky Sports" to ChannelContentType.SPORTS,
        "TNT Sports" to ChannelContentType.SPORTS,
        "DAZN" to ChannelContentType.SPORTS,
        "Golf Channel" to ChannelContentType.SPORTS,
        "Tennis Channel" to ChannelContentType.SPORTS,
        "FS1" to ChannelContentType.SPORTS,
        "FS2" to ChannelContentType.SPORTS,
        "SEC Network" to ChannelContentType.SPORTS,
        "Big Ten Network" to ChannelContentType.SPORTS,
        "Pac-12" to ChannelContentType.SPORTS,
        "ACC Network" to ChannelContentType.SPORTS,

        // News
        "CNN" to ChannelContentType.NEWS,
        "MSNBC" to ChannelContentType.NEWS,
        "Fox News" to ChannelContentType.NEWS,
        "BBC News" to ChannelContentType.NEWS,
        "CNBC" to ChannelContentType.NEWS,
        "Bloomberg" to ChannelContentType.NEWS,
        "Al Jazeera" to ChannelContentType.NEWS,
        "Newsmax" to ChannelContentType.NEWS,
        "HLN" to ChannelContentType.NEWS,
        "C-SPAN" to ChannelContentType.NEWS,
        "Sky News" to ChannelContentType.NEWS,
        "ABC News" to ChannelContentType.NEWS,
        "NewsNation" to ChannelContentType.NEWS,
        "WeatherNation" to ChannelContentType.NEWS,
        "Weather Channel" to ChannelContentType.NEWS,

        // Entertainment
        "USA" to ChannelContentType.ENTERTAINMENT,
        "TNT" to ChannelContentType.ENTERTAINMENT,
        "TBS" to ChannelContentType.ENTERTAINMENT,
        "FX" to ChannelContentType.ENTERTAINMENT,
        "AMC" to ChannelContentType.ENTERTAINMENT,
        "Bravo" to ChannelContentType.ENTERTAINMENT,
        "E!" to ChannelContentType.ENTERTAINMENT,
        "TLC" to ChannelContentType.ENTERTAINMENT,
        "Lifetime" to ChannelContentType.ENTERTAINMENT,
        "BET" to ChannelContentType.ENTERTAINMENT,
        "Comedy Central" to ChannelContentType.ENTERTAINMENT,
        "MTV" to ChannelContentType.ENTERTAINMENT,
        "VH1" to ChannelContentType.ENTERTAINMENT,
        "HGTV" to ChannelContentType.ENTERTAINMENT,
        "Food Network" to ChannelContentType.ENTERTAINMENT,
        "Syfy" to ChannelContentType.ENTERTAINMENT,
        "A&E" to ChannelContentType.ENTERTAINMENT,
        "WE tv" to ChannelContentType.ENTERTAINMENT,
        "OWN" to ChannelContentType.ENTERTAINMENT,
        "Freeform" to ChannelContentType.ENTERTAINMENT,
        "truTV" to ChannelContentType.ENTERTAINMENT,
        "Oxygen" to ChannelContentType.ENTERTAINMENT,
        "Paramount" to ChannelContentType.ENTERTAINMENT,
        "IFC" to ChannelContentType.ENTERTAINMENT,
        "TV Land" to ChannelContentType.ENTERTAINMENT,

        // Movies
        "HBO" to ChannelContentType.MOVIES,
        "Showtime" to ChannelContentType.MOVIES,
        "Starz" to ChannelContentType.MOVIES,
        "Cinemax" to ChannelContentType.MOVIES,
        "Epix" to ChannelContentType.MOVIES,
        "Hallmark" to ChannelContentType.MOVIES,
        "TCM" to ChannelContentType.MOVIES,
        "Sundance" to ChannelContentType.MOVIES,
        "MGM" to ChannelContentType.MOVIES,

        // Kids
        "Nickelodeon" to ChannelContentType.KIDS,
        "Disney" to ChannelContentType.KIDS,
        "Cartoon Network" to ChannelContentType.KIDS,
        "PBS Kids" to ChannelContentType.KIDS,
        "Nick Jr" to ChannelContentType.KIDS,
        "Disney Junior" to ChannelContentType.KIDS,
        "Disney XD" to ChannelContentType.KIDS,
        "Boomerang" to ChannelContentType.KIDS,
        "Universal Kids" to ChannelContentType.KIDS,
        "Nicktoons" to ChannelContentType.KIDS,
        "TeenNick" to ChannelContentType.KIDS,

        // Documentary
        "Discovery" to ChannelContentType.DOCUMENTARY,
        "National Geographic" to ChannelContentType.DOCUMENTARY,
        "Nat Geo" to ChannelContentType.DOCUMENTARY,
        "History" to ChannelContentType.DOCUMENTARY,
        "Science Channel" to ChannelContentType.DOCUMENTARY,
        "Animal Planet" to ChannelContentType.DOCUMENTARY,
        "Smithsonian" to ChannelContentType.DOCUMENTARY,
        "Investigation Discovery" to ChannelContentType.DOCUMENTARY,
        "Travel Channel" to ChannelContentType.DOCUMENTARY,
        "History Channel" to ChannelContentType.DOCUMENTARY,

        // Music
        "MTV Music" to ChannelContentType.MUSIC,
        "VH1 Classic" to ChannelContentType.MUSIC,
        "BET Soul" to ChannelContentType.MUSIC,
        "CMT" to ChannelContentType.MUSIC,
        "Music Choice" to ChannelContentType.MUSIC,
        "BET Jams" to ChannelContentType.MUSIC,

        // Religious
        "TBN" to ChannelContentType.RELIGIOUS,
        "EWTN" to ChannelContentType.RELIGIOUS,
        "Daystar" to ChannelContentType.RELIGIOUS,
        "BYUtv" to ChannelContentType.RELIGIOUS,
        "JUCE TV" to ChannelContentType.RELIGIOUS,
        "Hillsong" to ChannelContentType.RELIGIOUS
    )

    private val LOCAL_AFFILIATES = listOf("Fox", "CBS", "NBC", "ABC", "CW", "PBS", "My")

    private val COUNTRY_PREFIX_REGEX = Regex(
        "^(US|USA|UK|CA|AU|ES|FR|DE|IT|PT|BR|MX|NL|BE|SE|NO|DK|FI|PL|RO|GR|TR|AR|CL|CO|PE|IN|PK|BD|PH|ZA|KE|NG)\\s*[:|\\-]\\s*",
        RegexOption.IGNORE_CASE
    )

    private val QUALITY_SUFFIX_REGEX = Regex(
        "\\s*(HD|FHD|SD|4K|UHD|H265|HEVC)\\s*$",
        RegexOption.IGNORE_CASE
    )

    /**
     * Parse channel name to extract network brand, content type, and metadata.
     *
     * @param channelName Raw channel name from IPTV provider
     * @param categoryName Optional category name for fallback classification
     * @return ParsedChannel with extracted metadata
     */
    fun parse(channelName: String, categoryName: String? = null): ParsedChannel {
        var workingName = channelName.trim()

        // Extract quality suffix before stripping it
        val qualityMatch = QUALITY_SUFFIX_REGEX.find(channelName)
        val quality = qualityMatch?.groups?.get(1)?.value?.uppercase()

        // Strip country prefix
        workingName = workingName.replace(COUNTRY_PREFIX_REGEX, "")

        // Strip quality suffix
        workingName = workingName.replace(QUALITY_SUFFIX_REGEX, "")

        // Try to match against network map (longest match first)
        val sortedNetworks = NETWORK_MAP.keys.sortedByDescending { it.length }
        for (networkName in sortedNetworks) {
            if (workingName.contains(networkName, ignoreCase = true)) {
                val contentType = NETWORK_MAP[networkName]!!

                // Check if it's a local affiliate
                val affiliateMatch = LOCAL_AFFILIATES.find {
                    workingName.contains(it, ignoreCase = true)
                }

                if (affiliateMatch != null) {
                    // Extract market (e.g., "Fox 5 Atlanta" -> "Atlanta")
                    val market = extractMarket(workingName, affiliateMatch)
                    return ParsedChannel(
                        network = networkName,
                        isLocal = true,
                        market = market,
                        contentType = contentType,
                        quality = quality
                    )
                }

                return ParsedChannel(
                    network = networkName,
                    isLocal = false,
                    market = null,
                    contentType = contentType,
                    quality = quality
                )
            }
        }

        // Check for local affiliates without network match
        for (affiliate in LOCAL_AFFILIATES) {
            if (workingName.contains(affiliate, ignoreCase = true)) {
                val market = extractMarket(workingName, affiliate)
                return ParsedChannel(
                    network = affiliate,
                    isLocal = true,
                    market = market,
                    contentType = inferContentTypeFromCategory(categoryName),
                    quality = quality
                )
            }
        }

        // Fall back to category name keyword matching
        val contentType = inferContentTypeFromCategory(categoryName)

        return ParsedChannel(
            network = null,
            isLocal = false,
            market = null,
            contentType = contentType,
            quality = quality
        )
    }

    /**
     * Extract market name from channel name (e.g., "Fox 5 Atlanta" -> "Atlanta")
     */
    private fun extractMarket(channelName: String, affiliate: String): String? {
        // Remove affiliate name and numbers, take remaining text
        val pattern = Regex("$affiliate\\s*\\d*\\s*(.+)", RegexOption.IGNORE_CASE)
        val match = pattern.find(channelName)
        val market = match?.groups?.get(1)?.value?.trim()

        // Only return if market is reasonable length and doesn't look like quality suffix
        return if (market != null && market.length in 3..30 &&
                   !QUALITY_SUFFIX_REGEX.matches(market)) {
            market
        } else {
            null
        }
    }

    /**
     * Infer content type from category name when network match fails
     */
    private fun inferContentTypeFromCategory(categoryName: String?): ChannelContentType {
        if (categoryName == null) return ChannelContentType.UNKNOWN

        val lower = categoryName.lowercase()
        return when {
            lower.contains("sport") -> ChannelContentType.SPORTS
            lower.contains("news") -> ChannelContentType.NEWS
            lower.contains("kid") -> ChannelContentType.KIDS
            lower.contains("movie") -> ChannelContentType.MOVIES
            lower.contains("music") -> ChannelContentType.MUSIC
            lower.contains("doc") -> ChannelContentType.DOCUMENTARY
            lower.contains("entertain") -> ChannelContentType.ENTERTAINMENT
            else -> ChannelContentType.UNKNOWN
        }
    }
}
