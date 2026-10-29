package app.playlistplayer.core.consolidation

/** Real (credential-free) names captured in the U1 spike. */
object NameFixtures {
    val live: List<String> by lazy { load("/names/live_names.txt") }
    val vod: List<String> by lazy { load("/names/vod_names.txt") }

    /** Returns the real name verbatim, failing if the fixture does not contain it. */
    fun liveName(name: String): String = name.also { require(it in live) { "not a real live name: $it" } }
    fun vodName(name: String): String = name.also { require(it in vod) { "not a real VOD name: $it" } }

    private fun load(path: String): List<String> =
        requireNotNull(NameFixtures::class.java.getResourceAsStream(path)) { "missing $path" }
            .bufferedReader(Charsets.UTF_8)
            .readLines()
            .map { it.removePrefix("﻿") }
            .filter { it.isNotBlank() }
}
