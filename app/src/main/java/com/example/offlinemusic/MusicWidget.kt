package com.example.offlinemusic

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

class MusicWidget : AppWidgetProvider() {
    companion object {
        private const val PREVIOUS = "com.example.offlinemusic.widget.PREVIOUS"
        private const val TOGGLE = "com.example.offlinemusic.widget.TOGGLE"
        private const val NEXT = "com.example.offlinemusic.widget.NEXT"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

        fun updateFromPlayer(context: Context, player: Player) {
            context.getSharedPreferences("widget_state", Context.MODE_PRIVATE).edit()
                .putString("title", player.currentMediaItem?.mediaMetadata?.title?.toString() ?: "TuneHive")
                .putString("artist", player.currentMediaItem?.mediaMetadata?.artist?.toString() ?: "Your offline music")
                .putBoolean("playing", player.isPlaying)
                .apply()
            updateAll(context)
        }

        private fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, MusicWidget::class.java))
            ids.forEach { id -> manager.updateAppWidget(id, createViews(context)) }
        }

        private fun action(context: Context, value: String, requestCode: Int): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                requestCode,
                Intent(context, MusicWidget::class.java).apply { action = value },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

        private fun createViews(context: Context): RemoteViews {
            val preferences = context.getSharedPreferences("widget_state", Context.MODE_PRIVATE)
            return RemoteViews(context.packageName, R.layout.music_widget).apply {
                setTextViewText(R.id.widget_title, preferences.getString("title", "TuneHive"))
                setTextViewText(R.id.widget_artist, preferences.getString("artist", "Your offline music"))
                setImageViewResource(
                    R.id.widget_play,
                    if (preferences.getBoolean("playing", false)) android.R.drawable.ic_media_pause
                    else android.R.drawable.ic_media_play
                )
                setOnClickPendingIntent(R.id.widget_previous, action(context, PREVIOUS, 101))
                setOnClickPendingIntent(R.id.widget_play, action(context, TOGGLE, 102))
                setOnClickPendingIntent(R.id.widget_next, action(context, NEXT, 103))
                setOnClickPendingIntent(
                    R.id.widget_title,
                    PendingIntent.getActivity(
                        context,
                        104,
                        Intent(context, MainActivity::class.java),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                )
            }
        }
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        updateAll(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val action = intent.action
        if (action !in setOf(PREVIOUS, TOGGLE, NEXT)) return
        val pending = goAsync()
        val appContext = context.applicationContext
        scope.launch {
            val future = MediaController.Builder(
                appContext,
                SessionToken(appContext, ComponentName(appContext, PlaybackService::class.java))
            ).buildAsync()
            try {
                withTimeout(8_000) {
                    val controller = future.await()
                    var attempts = 0
                    while (controller.mediaItemCount == 0 && attempts < 20) {
                        delay(100)
                        attempts++
                    }
                    when (action) {
                        PREVIOUS -> controller.seekToPrevious()
                        NEXT -> controller.seekToNext()
                        TOGGLE -> if (controller.isPlaying) controller.pause()
                        else if (controller.mediaItemCount > 0) {
                            controller.prepare()
                            controller.play()
                        }
                    }
                    updateFromPlayer(appContext, controller)
                }
            } catch (_: Exception) {
                // The widget remains usable once the service is available.
            } finally {
                MediaController.releaseFuture(future)
                pending.finish()
            }
        }
    }
}
