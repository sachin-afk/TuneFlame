package com.example.offlinemusic

import android.content.Context
import android.media.audiofx.Equalizer
import android.os.Bundle
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult

object EqualizerCommands {
    const val GET_ACTION = "com.example.offlinemusic.EQUALIZER_GET"
    const val SET_ACTION = "com.example.offlinemusic.EQUALIZER_SET"
    val GET = SessionCommand(GET_ACTION, Bundle.EMPTY)
    val SET = SessionCommand(SET_ACTION, Bundle.EMPTY)
}

class DeviceEqualizer(context: Context) {
    private val preferences = context.getSharedPreferences("device_equalizer", Context.MODE_PRIVATE)
    private var effect: Equalizer? = null

    fun attach(audioSessionId: Int) {
        release()
        if (audioSessionId <= 0) return
        effect = runCatching {
            Equalizer(0, audioSessionId).also { equalizer ->
                val range = equalizer.bandLevelRange
                repeat(equalizer.numberOfBands.toInt()) { index ->
                    val saved = preferences.getInt("band_$index", 0).coerceIn(range[0].toInt(), range[1].toInt())
                    equalizer.setBandLevel(index.toShort(), saved.toShort())
                }
                equalizer.enabled = preferences.getBoolean("enabled", false)
            }
        }.getOrNull()
    }

    fun getState(): SessionResult {
        val equalizer = effect ?: return SessionResult(SessionResult.RESULT_SUCCESS, Bundle().apply { putBoolean("supported", false) })
        return runCatching {
            val count = equalizer.numberOfBands.toInt()
            val range = equalizer.bandLevelRange
            SessionResult(SessionResult.RESULT_SUCCESS, Bundle().apply {
                putBoolean("supported", true)
                putBoolean("enabled", equalizer.enabled)
                putInt("minimum", range[0].toInt())
                putInt("maximum", range[1].toInt())
                putIntArray("frequencies", IntArray(count) { equalizer.getCenterFreq(it.toShort()) / 1000 })
                putIntArray("levels", IntArray(count) { equalizer.getBandLevel(it.toShort()).toInt() })
            })
        }.getOrElse { SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED) }
    }

    fun apply(arguments: Bundle): SessionResult {
        val equalizer = effect ?: return SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED)
        return runCatching {
            val editor = preferences.edit()
            val range = equalizer.bandLevelRange
            if (arguments.containsKey("enabled")) {
                val enabled = arguments.getBoolean("enabled")
                equalizer.enabled = enabled
                editor.putBoolean("enabled", enabled)
            }
            if (arguments.containsKey("band")) {
                val band = arguments.getInt("band")
                require(band in 0 until equalizer.numberOfBands.toInt())
                val level = arguments.getInt("level").coerceIn(range[0].toInt(), range[1].toInt())
                equalizer.setBandLevel(band.toShort(), level.toShort())
                editor.putInt("band_$band", level)
            }
            if (arguments.getBoolean("reset", false)) {
                repeat(equalizer.numberOfBands.toInt()) { band ->
                    equalizer.setBandLevel(band.toShort(), 0)
                    editor.putInt("band_$band", 0)
                }
            }
            editor.apply()
            getState()
        }.getOrElse { SessionResult(SessionResult.RESULT_ERROR_BAD_VALUE) }
    }

    fun release() {
        runCatching { effect?.release() }
        effect = null
    }
}
