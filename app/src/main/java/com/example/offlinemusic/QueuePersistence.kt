package com.example.offlinemusic

import android.content.Context
import android.util.AtomicFile
import androidx.media3.common.Player
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class SavedQueue(
    val ids: List<String>,
    val index: Int,
    val positionMs: Long,
    val shuffle: Boolean,
    val repeatMode: Int
)

class QueuePersistence(context: Context) {
    private val mutex = Mutex()
    private val file = AtomicFile(File(context.filesDir, "playback_queue.json"))

    suspend fun read(): SavedQueue? = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching {
                val json = file.openRead().bufferedReader().use { JSONObject(it.readText()) }
                val array = json.getJSONArray("ids")
                SavedQueue(
                    ids = List(array.length()) { array.getString(it) },
                    index = json.optInt("index", 0),
                    positionMs = json.optLong("positionMs", 0L).coerceAtLeast(0L),
                    shuffle = json.optBoolean("shuffle", false),
                    repeatMode = json.optInt("repeatMode", Player.REPEAT_MODE_OFF)
                )
            }.getOrNull()
        }
    }

    suspend fun write(queue: SavedQueue) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val json = JSONObject().apply {
                put("ids", JSONArray().apply { queue.ids.forEach(::put) })
                put("index", queue.index)
                put("positionMs", queue.positionMs)
                put("shuffle", queue.shuffle)
                put("repeatMode", queue.repeatMode)
            }
            val output = file.startWrite()
            try {
                output.write(json.toString().toByteArray(Charsets.UTF_8))
                file.finishWrite(output)
            } catch (error: Exception) {
                file.failWrite(output)
                throw error
            }
        }
    }
}

fun Player.captureQueue(): SavedQueue = SavedQueue(
    ids = List(mediaItemCount) { getMediaItemAt(it).mediaId },
    index = currentMediaItemIndex.coerceAtLeast(0),
    positionMs = currentPosition.coerceAtLeast(0L),
    shuffle = shuffleModeEnabled,
    repeatMode = repeatMode
)
