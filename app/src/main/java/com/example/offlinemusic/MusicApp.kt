package com.example.offlinemusic

import android.app.Application
import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

private val Context.settingsStore by preferencesDataStore("playback_settings")

@Immutable
data class PlaybackSettings(
    val speed: Float = 1f,
    val pitch: Float = 1f,
    val gaplessPlayback: Boolean = false,
    val resumeOnLaunch: Boolean = true,
    val dynamicBackdrop: Boolean = true,
    val keepScreenAwake: Boolean = true,
    val lockScreenArt: Boolean = true,
    val themeMode: String = "AMOLED",
    val accentHue: String = "PURPLE",
    val launchScreen: String = "HOME",
    val profileName: String = "Listener",
    val username: String = "listener",
    val filterShortAudioSeconds: Int = 30,
    val sleepTimerMinutes: Int = 45
)

class SettingsRepository(
    private val context: Context,
    scope: CoroutineScope
) {
    private val speedKey = floatPreferencesKey("speed")
    private val pitchKey = floatPreferencesKey("pitch")
    private val gaplessKey = booleanPreferencesKey("gapless_playback")
    private val resumeKey = booleanPreferencesKey("resume_on_launch")
    private val backdropKey = booleanPreferencesKey("dynamic_backdrop")
    private val screenAwakeKey = booleanPreferencesKey("keep_screen_awake")
    private val lockArtKey = booleanPreferencesKey("lock_screen_art")
    private val themeKey = stringPreferencesKey("theme_mode")
    private val accentKey = stringPreferencesKey("accent_hue")
    private val launchKey = stringPreferencesKey("launch_screen")
    private val profileNameKey = stringPreferencesKey("profile_name")
    private val usernameKey = stringPreferencesKey("username")
    private val filterKey = intPreferencesKey("filter_short_audio")
    private val sleepKey = intPreferencesKey("sleep_timer_minutes")

    val settings = context.settingsStore.data
        .catch { error ->
            if (error is IOException) {
                emit(emptyPreferences())
            } else {
                throw error
            }
        }
        .map { preferences ->
            PlaybackSettings(
                speed = (preferences[speedKey] ?: 1f).coerceIn(0.5f, 2f),
                pitch = (preferences[pitchKey] ?: 1f).coerceIn(0.5f, 2f),
                gaplessPlayback = preferences[gaplessKey] ?: false,
                resumeOnLaunch = preferences[resumeKey] ?: true,
                dynamicBackdrop = preferences[backdropKey] ?: true,
                keepScreenAwake = preferences[screenAwakeKey] ?: true,
                lockScreenArt = preferences[lockArtKey] ?: true,
                themeMode = preferences[themeKey] ?: "AMOLED",
                accentHue = preferences[accentKey] ?: "PURPLE",
                launchScreen = preferences[launchKey] ?: "HOME",
                profileName = preferences[profileNameKey] ?: "Listener",
                username = preferences[usernameKey] ?: "listener",
                filterShortAudioSeconds = preferences[filterKey] ?: 30,
                sleepTimerMinutes = preferences[sleepKey] ?: 45
            )
        }
        .stateIn(
            scope,
            SharingStarted.Eagerly,
            PlaybackSettings()
        )

    suspend fun setSpeed(value: Float) {
        context.settingsStore.edit {
            it[speedKey] = value.coerceIn(0.5f, 2f)
        }
    }

    suspend fun setPitch(value: Float) {
        context.settingsStore.edit {
            it[pitchKey] = value.coerceIn(0.5f, 2f)
        }
    }

    suspend fun setGaplessPlayback(value: Boolean) = save { this[gaplessKey] = value }
    suspend fun setResumeOnLaunch(value: Boolean) = save { this[resumeKey] = value }
    suspend fun setDynamicBackdrop(value: Boolean) = save { this[backdropKey] = value }
    suspend fun setKeepScreenAwake(value: Boolean) = save { this[screenAwakeKey] = value }
    suspend fun setLockScreenArt(value: Boolean) = save { this[lockArtKey] = value }
    suspend fun setThemeMode(value: String) = save { this[themeKey] = value }
    suspend fun setAccentHue(value: String) = save { this[accentKey] = value }
    suspend fun setLaunchScreen(value: String) = save { this[launchKey] = value }
    suspend fun setProfileName(value: String) = save { this[profileNameKey] = value.trim() }
    suspend fun setUsername(value: String) = save { this[usernameKey] = value.trim().removePrefix("@") }
    suspend fun setFilterShortAudioSeconds(value: Int) = save {
        this[filterKey] = value.coerceIn(10, 120)
    }
    suspend fun setSleepTimerMinutes(value: Int) = save {
        this[sleepKey] = value.coerceIn(0, 180)
    }
    suspend fun reset() = context.settingsStore.edit { it.clear() }

    private suspend fun save(
        change: androidx.datastore.preferences.core.MutablePreferences.() -> Unit
    ) {
        context.settingsStore.edit { preferences ->
            change(preferences)
        }
    }
}

class MusicApp : Application() {
    val applicationScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate
    )

    val database by lazy {
        Room.databaseBuilder(
            applicationContext,
            MusicDatabase::class.java,
            "music.db"
        ).build()
    }

    val repository by lazy {
        MusicRepository(
            context = applicationContext,
            scope = applicationScope
        )
    }

    val settings by lazy {
        SettingsRepository(applicationContext, applicationScope)
    }

    val queuePersistence by lazy {
        QueuePersistence(applicationContext)
    }
}
