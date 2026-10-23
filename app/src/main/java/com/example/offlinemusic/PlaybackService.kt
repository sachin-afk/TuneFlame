package com.example.offlinemusic

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.launch

@UnstableApi
class PlaybackService : MediaLibraryService() {
    companion object {
        const val ROOT_ID = "library_root"
        const val SLEEP_ACTION = "com.example.offlinemusic.SLEEP"
        const val SLEEP_MINUTES = "minutes"

        val SLEEP_COMMAND = SessionCommand(SLEEP_ACTION, Bundle.EMPTY)
    }

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate
    )

    private val app get() = application as MusicApp

    private lateinit var player: ExoPlayer
    private lateinit var session: MediaLibrarySession
    private lateinit var restoration: PlaybackRestoration
    private lateinit var deviceEqualizer: DeviceEqualizer

    private var sleepJob: Job? = null
    private var countedCurrentItem = false

    override fun onCreate() {
        super.onCreate()

        player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()

        deviceEqualizer = DeviceEqualizer(this)

        player.addListener(
            object : Player.Listener {
                override fun onAudioSessionIdChanged(audioSessionId: Int) {
                    deviceEqualizer.attach(audioSessionId)
                }
            }
        )

        deviceEqualizer.attach(player.audioSessionId)

        player.addListener(
            object : Player.Listener {
                override fun onMediaItemTransition(
                    mediaItem: MediaItem?,
                    reason: Int
                ) {
                    countedCurrentItem = false
                    recordStartedTrack()
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    recordStartedTrack()
                }
            }
        )

        val activityIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        session = MediaLibrarySession.Builder(
            this,
            player,
            LibraryCallback()
        )
            .setSessionActivity(activityIntent)
            .build()

        restoration = PlaybackRestoration(
            app = app,
            player = player,
            serviceScope = scope,
            onPlaybackChanged = {
                MusicWidget.updateFromPlayer(applicationContext, player)
            }
        )
        restoration.start()

        app.repository.startObserving()

        scope.launch {
            app.repository.refresh()
        }

        scope.launch {
            app.repository.tracks.collectLatest { tracks ->
                session.notifyChildrenChanged(
                    ROOT_ID,
                    tracks.size,
                    null
                )
            }
        }

        scope.launch {
            app.settings.settings.collectLatest { settings ->
                player.playbackParameters = PlaybackParameters(
                    settings.speed,
                    settings.pitch
                )
            }
        }
    }

    private fun recordStartedTrack() {
        if (!player.isPlaying || countedCurrentItem) return

        val mediaId = player.currentMediaItem?.mediaId ?: return
        countedCurrentItem = true

        app.applicationScope.launch {
            app.database.musicDao().recordPlay(mediaId)
        }
    }

    override fun onGetSession(
        controllerInfo: MediaSession.ControllerInfo
    ): MediaLibrarySession = session

    private fun setSleepTimer(minutes: Int) {
        sleepJob?.cancel()
        sleepJob = null

        if (minutes <= 0) return

        sleepJob = scope.launch {
            val durationMs = minutes.coerceAtMost(1440) * 60_000L
            val fadeMs = minOf(5_000L, durationMs)

            delay(durationMs - fadeMs)

            val originalVolume = player.volume

            try {
                val steps = 50

                for (step in 1..steps) {
                    player.volume =
                        originalVolume * (1f - step.toFloat() / steps)
                    delay(fadeMs / steps)
                }

                player.pause()
            } finally {
                player.volume = originalVolume
            }
        }
    }

    private inner class LibraryCallback : MediaLibrarySession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            val commands =
                MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS
                    .buildUpon()
                    .apply {
                        // Only our app needs access to the sleep-timer command.
                        if (controller.packageName == packageName) {
                            add(SLEEP_COMMAND)
                            add(EqualizerCommands.GET)
                            add(EqualizerCommands.SET)
                        }
                    }
                    .build()

            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(commands)
                .build()
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val root = MediaItem.Builder()
                .setMediaId(ROOT_ID)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle("Offline Music")
                        .setIsBrowsable(true)
                        .setIsPlayable(false)
                        .build()
                )
                .build()

            return Futures.immediateFuture(
                LibraryResult.ofItem(root, params)
            )
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
            scope.future {
                if (parentId != ROOT_ID || page < 0 || pageSize <= 0) {
                    return@future LibraryResult.ofError(
                        SessionResult.RESULT_ERROR_BAD_VALUE
                    )
                }

                app.repository.ensureLoaded()
                val tracks = app.repository.tracks.value

                val start = (page.toLong() * pageSize)
                    .coerceAtMost(tracks.size.toLong())
                    .toInt()

                val end = (start.toLong() + pageSize)
                    .coerceAtMost(tracks.size.toLong())
                    .toInt()

                LibraryResult.ofItemList(
                    tracks.subList(start, end).map(Track::toMediaItem),
                    params
                )
            }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String
        ): ListenableFuture<LibraryResult<MediaItem>> =
            scope.future {
                app.repository.ensureLoaded()

                val track = app.repository.tracks.value
                    .firstOrNull { it.id == mediaId }

                if (track == null) {
                    LibraryResult.ofError(SessionResult.RESULT_ERROR_BAD_VALUE)
                } else {
                    LibraryResult.ofItem(track.toMediaItem(), null)
                }
            }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>
        ): ListenableFuture<List<MediaItem>> =
            scope.future {
                app.repository.ensureLoaded()
                val byId = app.repository.tracks.value.associateBy { it.id }

                // Resolve only known local media IDs. Ignore arbitrary URIs.
                mediaItems.mapNotNull { item ->
                    byId[item.mediaId]?.toMediaItem()
                }
            }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            if (controller.packageName == packageName) {
                when (customCommand.customAction) {
                    EqualizerCommands.GET_ACTION -> {
                        return Futures.immediateFuture(deviceEqualizer.getState())
                    }

                    EqualizerCommands.SET_ACTION -> {
                        return Futures.immediateFuture(deviceEqualizer.apply(args))
                    }
                }
            }

            if (
                customCommand.customAction == SLEEP_ACTION &&
                controller.packageName == packageName
            ) {
                setSleepTimer(args.getInt(SLEEP_MINUTES, 0))
                return Futures.immediateFuture(
                    SessionResult(SessionResult.RESULT_SUCCESS)
                )
            }

            return Futures.immediateFuture(
                SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED)
            )
        }
    }

    override fun onDestroy() {
        restoration.close()
        // Cancel first so a pending fade can restore volume before release.
        scope.cancel()
        deviceEqualizer.release()
        session.release()
        player.release()
        super.onDestroy()
    }
}
