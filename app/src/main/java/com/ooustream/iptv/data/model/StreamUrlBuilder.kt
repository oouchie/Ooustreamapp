package com.ooustream.iptv.data.model

object StreamUrlBuilder {
    fun live(server: String, user: String, pass: String, streamId: Int): String =
        "$server/live/$user/$pass/$streamId.ts"

    fun vod(server: String, user: String, pass: String, streamId: Int, ext: String): String =
        "$server/movie/$user/$pass/$streamId.$ext"

    fun series(server: String, user: String, pass: String, streamId: Int, ext: String): String =
        "$server/series/$user/$pass/$streamId.$ext"
}
