package com.example.offlinemusic

import androidx.media3.common.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class PlaybackRestoration(
    private val app: MusicApp,
    private val player: Player,
    private val serviceScope: CoroutineScope,
    private val onPlaybackChanged: () -> Unit
) {
    private var ready = false
    private var closed = false
    private var touchedDuringRestore = false
    private var ticker: Job? = null

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (!ready) {
                if (events.contains(Player.EVENT_TIMELINE_CHANGED) ||
                    events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) {
                    touchedDuringRestore = true
                }
                return
            }

            if (events.contains(Player.EVENT_TIMELINE_CHANGED) ||
                events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION) ||
                events.contains(Player.EVENT_POSITION_DISCONTINUITY) ||
                events.contains(Player.EVENT_IS_PLAYING_CHANGED) ||
                events.contains(Player.EVENT_REPEAT_MODE_CHANGED) ||
                events.contains(Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED)) {
                save()
                onPlaybackChanged()
            }
        }
    }

    fun start() {
        player.addListener(listener)
        serviceScope.launch {
            val saved = app.queuePersistence.read()
            app.repository.ensureLoaded()

            if (!closed && !touchedDuringRestore && player.mediaItemCount == 0 &&
                saved != null && saved.ids.isNotEmpty()) {
                val tracksById = app.repository.tracks.value.associateBy { it.id }
                val restored = saved.ids.mapIndexedNotNull { index, id ->
                    tracksById[id]?.let { index to it }
                }

                if (restored.isNotEmpty()) {
                    val exactIndex = restored.indexOfFirst { it.first == saved.index }
                    val newIndex = if (exactIndex >= 0) exactIndex else {
                        restored.indexOfFirst { it.first > saved.index }.takeIf { it >= 0 } ?: 0
                    }
                    val position = if (exactIndex >= 0) {
                        saved.positionMs.coerceAtMost(restored[newIndex].second.durationMs.coerceAtLeast(0L))
                    } else 0L

                    player.setMediaItems(restored.map { it.second.toMediaItem() }, newIndex, position)
                    player.shuffleModeEnabled = saved.shuffle
                    player.repeatMode = when (saved.repeatMode) {
                        Player.REPEAT_MODE_ONE, Player.REPEAT_MODE_ALL -> saved.repeatMode
                        else -> Player.REPEAT_MODE_OFF
                    }
                    player.playWhenReady = false
                    player.prepare()
                }
            }

            if (closed) return@launch
            ready = true
            onPlaybackChanged()
            ticker = serviceScope.launch {
                while (true) {
                    delay(5_000)
                    if (player.isPlaying) save()
                }
            }
        }
    }

    private fun save() {
        if (!ready || closed) return
        val snapshot = player.captureQueue()
        app.applicationScope.launch { runCatching { app.queuePersistence.write(snapshot) } }
    }

    fun close() {
        if (closed) return
        save()
        closed = true
        ticker?.cancel()
        player.removeListener(listener)
    }
}
