package com.example.offlinemusic

import kotlin.random.Random

data class MusicCollection(
    val id: String,
    val title: String,
    val subtitle: String,
    val tracks: List<Track>
)

object LibraryFeatures {
    fun albums(tracks: List<Track>): List<MusicCollection> = tracks.groupBy { it.album to it.artist }
        .map { (key, songs) -> MusicCollection("album:${key.first}:${key.second}", key.first, "${key.second} • ${songs.size} songs", songs) }
        .sortedBy { it.title.lowercase() }

    fun artists(tracks: List<Track>): List<MusicCollection> = tracks.groupBy { it.artist }
        .map { (artist, songs) -> MusicCollection("artist:$artist", artist, "${songs.size} songs", songs) }
        .sortedBy { it.title.lowercase() }

    fun folders(tracks: List<Track>): List<MusicCollection> = tracks.groupBy { it.folder }
        .map { (folder, songs) -> MusicCollection("folder:$folder", folder.substringAfterLast('/'), folder, songs) }
        .sortedBy { it.subtitle.lowercase() }

    fun recommendations(tracks: List<Track>, states: List<TrackState>, now: Long = System.currentTimeMillis()): List<MusicCollection> {
        val byId = states.associateBy { it.mediaId }
        val thirtyDays = 30L * 24 * 60 * 60 * 1000
        val neverPlayed = tracks.filter { (byId[it.id]?.playCount ?: 0L) == 0L }
        val forgotten = tracks.filter { byId[it.id]?.let { state -> state.favorite && state.lastPlayed < now - thirtyDays } == true }
        val recentlyAdded = tracks.sortedByDescending { it.dateAdded }.take(30)
        val daySeed = (now / 86_400_000L).toInt()
        val daily = tracks.sortedBy { it.id }.shuffled(Random(daySeed)).take(30)
        return listOf(
            MusicCollection("never", "Never played", "Find something you have not started yet", neverPlayed.take(30)),
            MusicCollection("forgotten", "Forgotten favorites", "Favorites you have not played in 30 days", forgotten.take(30)),
            MusicCollection("new", "Fresh in your library", "Your latest local additions", recentlyAdded),
            MusicCollection("daily", "Your daily shuffle", "A different selection each day", daily)
        ).filter { it.tracks.isNotEmpty() }
    }
}
