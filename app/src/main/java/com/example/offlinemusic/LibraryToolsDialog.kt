@file:Suppress("UnsafeOptInUsageError")

package com.example.offlinemusic

import android.os.Bundle
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.session.MediaController
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch

@Composable
fun LibraryToolsDialog(
    tracks: List<Track>,
    states: List<TrackState>,
    controller: MediaController?,
    onPlay: (List<Track>, Int) -> Unit,
    onDismiss: () -> Unit
) {
    var section by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<MusicCollection?>(null) }
    val collections = remember(section, tracks, states) {
        when (section) {
            "Albums" -> LibraryFeatures.albums(tracks)
            "Artists" -> LibraryFeatures.artists(tracks)
            "Folders" -> LibraryFeatures.folders(tracks)
            "Recommendations" -> LibraryFeatures.recommendations(tracks, states)
            else -> emptyList()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(selected?.title ?: section ?: "Music tools") },
        text = {
            when {
                section == "Equalizer" -> EqualizerPanel(controller)
                selected != null -> {
                    val collection = selected!!
                    LazyColumn(Modifier.heightIn(max = 430.dp)) {
                        item {
                            TextButton(onClick = { onPlay(collection.tracks, 0); onDismiss() }) {
                                Text("Play all")
                            }
                        }
                        itemsIndexed(collection.tracks, key = { _, song -> song.id }) { index, song ->
                            Column(
                                Modifier.fillMaxWidth().clickable {
                                    onPlay(collection.tracks, index)
                                    onDismiss()
                                }.padding(vertical = 12.dp)
                            ) {
                                Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(song.artist, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                section != null -> {
                    LazyColumn(Modifier.heightIn(max = 430.dp)) {
                        if (collections.isEmpty()) item { Text("No collections available yet.") }
                        items(collections, key = { it.id }) { collection ->
                            Column(
                                Modifier.fillMaxWidth().clickable { selected = collection }.padding(vertical = 13.dp)
                            ) {
                                Text(collection.title)
                                Text(collection.subtitle, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                else -> Column {
                    listOf("Albums", "Artists", "Folders", "Recommendations", "Equalizer").forEach { name ->
                        TextButton(onClick = { section = name }, modifier = Modifier.fillMaxWidth()) {
                            Text(name, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        dismissButton = {
            if (section != null) {
                TextButton(onClick = { if (selected != null) selected = null else section = null }) {
                    Text("Back")
                }
            }
        }
    )
}

@Composable
private fun EqualizerPanel(controller: MediaController?) {
    val scope = rememberCoroutineScope()
    var data by remember { mutableStateOf<Bundle?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    suspend fun refresh() {
        if (controller == null) {
            error = "Player is not connected."
            return
        }
        runCatching { controller.sendCustomCommand(EqualizerCommands.GET, Bundle.EMPTY).await() }
            .onSuccess { data = it.extras }
            .onFailure { error = it.message ?: "Equalizer unavailable." }
    }

    fun send(arguments: Bundle) {
        val connectedController = controller ?: return
        scope.launch {
            runCatching { connectedController.sendCustomCommand(EqualizerCommands.SET, arguments).await() }
                .onSuccess {
                    if (it.resultCode == 0) data = it.extras
                    else error = "This equalizer change was not supported."
                }
                .onFailure { error = it.message ?: "Could not update equalizer." }
        }
    }

    LaunchedEffect(controller) { refresh() }
    val current = data

    when {
        error != null -> Text(error!!)
        current == null -> CircularProgressIndicator()
        !current.getBoolean("supported", false) -> Column {
            Text("Equalizer unavailable. Start a song and try again. Some devices or audio routes do not support it.")
            TextButton(onClick = { scope.launch { refresh() } }) { Text("Retry") }
        }
        else -> {
            val frequencies = current.getIntArray("frequencies") ?: intArrayOf()
            val levels = current.getIntArray("levels") ?: intArrayOf()
            val minimum = current.getInt("minimum").toFloat()
            val maximum = current.getInt("maximum").toFloat()
            val enabled = current.getBoolean("enabled")

            LazyColumn(Modifier.heightIn(max = 430.dp)) {
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Enable equalizer")
                        Switch(checked = enabled, onCheckedChange = {
                            send(Bundle().apply { putBoolean("enabled", it) })
                        })
                    }
                }
                items(levels.size) { index ->
                    var value by remember(index, levels[index]) { mutableStateOf(levels[index].toFloat()) }
                    Column {
                        Text("${frequencies.getOrElse(index) { 0 }} Hz · ${value.toInt() / 100f} dB")
                        Slider(
                            value = value.coerceIn(minimum, maximum),
                            onValueChange = { value = it },
                            onValueChangeFinished = {
                                send(Bundle().apply {
                                    putInt("band", index)
                                    putInt("level", value.toInt())
                                })
                            },
                            enabled = enabled && maximum > minimum,
                            valueRange = minimum..(maximum.takeIf { it > minimum } ?: minimum + 1f)
                        )
                    }
                }
                item {
                    TextButton(onClick = { send(Bundle().apply { putBoolean("reset", true) }) }) {
                        Text("Reset bands")
                    }
                }
            }
        }
    }
}
