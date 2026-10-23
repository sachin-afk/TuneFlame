package com.example.offlinemusic

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.compose.runtime.Immutable
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

fun audioPermission(): String =
    if (Build.VERSION.SDK_INT >= 33) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

fun Context.hasAudioPermission(): Boolean =
    ContextCompat.checkSelfPermission(
        this,
        audioPermission()
    ) == PackageManager.PERMISSION_GRANTED

@Immutable
data class Track(
    val id: String,
    val uri: Uri,
    val title: String,
    val artist: String,
    val album: String,
    val folder: String,
    val durationMs: Long,
    val dateAdded: Long,
    val artworkUri: Uri?
) {
    fun toMediaItem(): MediaItem =
        MediaItem.Builder()
            .setMediaId(id)
            .setUri(uri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(artist)
                    .setAlbumTitle(album)
                    .setArtworkUri(artworkUri)
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .build()
            )
            .build()
}

class MusicRepository(
    private val context: Context,
    private val scope: CoroutineScope
) {
    private val mutex = Mutex()

    private val _tracks = MutableStateFlow<List<Track>>(emptyList())
    val tracks = _tracks.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    private var loaded = false
    private var observing = false

    private val observer = object : ContentObserver(
        Handler(Looper.getMainLooper())
    ) {
        override fun onChange(selfChange: Boolean) {
            scope.launch {
                refresh()
            }
        }
    }

    fun startObserving() {
        if (observing || !context.hasAudioPermission()) return

        try {
            context.contentResolver.registerContentObserver(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                true,
                observer
            )
            observing = true
        } catch (_: SecurityException) {
            // Permission can be revoked between checking and registering.
        }
    }

    suspend fun ensureLoaded() {
        if (!loaded || !context.hasAudioPermission()) {
            refresh()
        }
    }

    suspend fun refresh() = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!context.hasAudioPermission()) {
                loaded = false
                _tracks.value = emptyList()
                _error.value = "Grant music access to load your library."
                return@withLock
            }

            try {
                val folderColumn =
                    if (Build.VERSION.SDK_INT >= 29) {
                        MediaStore.MediaColumns.RELATIVE_PATH
                    } else {
                        MediaStore.MediaColumns.DATA
                    }

                val projection = arrayOf(
                    MediaStore.Audio.Media._ID,
                    MediaStore.Audio.Media.TITLE,
                    MediaStore.Audio.Media.DISPLAY_NAME,
                    MediaStore.Audio.Media.ARTIST,
                    MediaStore.Audio.Media.ALBUM,
                    MediaStore.Audio.Media.ALBUM_ID,
                    MediaStore.Audio.Media.DURATION,
                    MediaStore.Audio.Media.DATE_ADDED,
                    folderColumn
                )

                val result = mutableListOf<Track>()
                val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI

                context.contentResolver.query(
                    collection,
                    projection,
                    "${MediaStore.Audio.Media.DURATION} >= ?",
                    arrayOf("30000"),
                    "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"
                )?.use { cursor ->
                    val idIndex = cursor.getColumnIndexOrThrow(
                        MediaStore.Audio.Media._ID
                    )
                    val titleIndex = cursor.getColumnIndexOrThrow(
                        MediaStore.Audio.Media.TITLE
                    )
                    val nameIndex = cursor.getColumnIndexOrThrow(
                        MediaStore.Audio.Media.DISPLAY_NAME
                    )
                    val artistIndex = cursor.getColumnIndexOrThrow(
                        MediaStore.Audio.Media.ARTIST
                    )
                    val albumIndex = cursor.getColumnIndexOrThrow(
                        MediaStore.Audio.Media.ALBUM
                    )
                    val albumIdIndex = cursor.getColumnIndexOrThrow(
                        MediaStore.Audio.Media.ALBUM_ID
                    )
                    val durationIndex = cursor.getColumnIndexOrThrow(
                        MediaStore.Audio.Media.DURATION
                    )
                    val addedIndex = cursor.getColumnIndexOrThrow(
                        MediaStore.Audio.Media.DATE_ADDED
                    )
                    val folderIndex = cursor.getColumnIndexOrThrow(folderColumn)

                    while (cursor.moveToNext()) {
                        val numericId = cursor.getLong(idIndex)
                        val albumId = cursor.getLong(albumIdIndex)
                        val rawFolder = cursor.getString(folderIndex).orEmpty()

                        val folder =
                            if (Build.VERSION.SDK_INT >= 29) {
                                rawFolder.trimEnd('/').ifBlank { "Storage" }
                            } else {
                                File(rawFolder).parent ?: "Storage"
                            }

                        result += Track(
                            id = numericId.toString(),
                            uri = ContentUris.withAppendedId(
                                collection,
                                numericId
                            ),
                            title = cursor.getString(titleIndex)
                                ?.takeIf { it.isNotBlank() }
                                ?: cursor.getString(nameIndex)
                                ?: "Untitled",
                            artist = cleanName(cursor.getString(artistIndex)),
                            album = cleanName(cursor.getString(albumIndex)),
                            folder = folder,
                            durationMs = cursor.getLong(durationIndex),
                            dateAdded = cursor.getLong(addedIndex) * 1000L,
                            artworkUri = if (albumId > 0) {
                                ContentUris.withAppendedId(
                                    "content://media/external/audio/albumart".toUri(),
                                    albumId
                                )
                            } else {
                                null
                            }
                        )
                    }
                }

                _tracks.value = result
                _error.value = null
                loaded = true
            } catch (_: SecurityException) {
                loaded = false
                _tracks.value = emptyList()
                _error.value = "Music access was revoked."
            } catch (error: Exception) {
                _error.value = error.message ?: "Could not read the music library."
            }
        }
    }

    private fun cleanName(value: String?): String =
        value?.takeUnless {
            it.isBlank() || it == "<unknown>"
        } ?: "Unknown"
}
