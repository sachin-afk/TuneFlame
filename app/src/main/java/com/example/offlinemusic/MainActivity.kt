@file:Suppress("UnsafeOptInUsageError")

package com.example.offlinemusic

import android.content.ComponentName
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import coil.compose.AsyncImage
import com.google.common.util.concurrent.ListenableFuture
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// PALETTE
private val Ink = Color(0xFF0B0416)
private val PurpleDeep = Color(0xFF2A0F4A)
private val PurpleMid = Color(0xFF4A1381)
private val PurpleGlow = Color(0xFF8B2ECF)
private val Magenta = Color(0xFFC127E7)
private val Pink = Color(0xFFEC4899)

private val TextWhite = Color(0xFFF6F1FF)
private val TextMuted = Color(0xFFB8ACC9)
private val TextFaint = Color(0xFF8C7EA0)

private val GlassSurface = Color.White.copy(alpha = 0.06f)
private val GlassBorder = Color.White.copy(alpha = 0.10f)

private fun appColorScheme(
    settings: PlaybackSettings,
    systemDark: Boolean
): ColorScheme {
    val accent = when (settings.accentHue) {
        "BLUE" -> Color(0xFF42A5F5)
        "CYAN" -> Color(0xFF26C6DA)
        "GREEN" -> Color(0xFF66BB6A)
        "ORANGE" -> Color(0xFFFFA726)
        "RED" -> Color(0xFFEF5350)
        "PINK" -> Pink
        else -> PurpleGlow
    }
    val light = settings.themeMode == "LIGHT" ||
        (settings.themeMode == "SYSTEM" && !systemDark)
    val amoled = settings.themeMode == "AMOLED"

    return if (light) {
        lightColorScheme(
            primary = accent,
            onPrimary = Color.White,
            background = Color(0xFFF7F4FA),
            onBackground = Color(0xFF201B24),
            surface = Color.White,
            onSurface = Color(0xFF201B24),
            surfaceVariant = Color(0xFFEDE7F0),
            onSurfaceVariant = Color(0xFF655D69)
        )
    } else {
        darkColorScheme(
            primary = accent,
            onPrimary = Color.White,
            background = if (amoled) Color.Black else Ink,
            onBackground = TextWhite,
            surface = if (amoled) Color(0xFF080808) else Color(0xFF1B0930),
            onSurface = TextWhite,
            surfaceVariant = Color(0xFF2C1445),
            onSurfaceVariant = TextMuted
        )
    }
}

class MainActivity : ComponentActivity() {
    private lateinit var controllerFuture: ListenableFuture<MediaController>
    private var controller by mutableStateOf<MediaController?>(null)
    private var connectionError by mutableStateOf<String?>(null)
    private var permissionGranted by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        permissionGranted = hasAudioPermission()

        controllerFuture = MediaController.Builder(
            this,
            SessionToken(this, ComponentName(this, PlaybackService::class.java))
        ).buildAsync()

        controllerFuture.addListener(
            {
                runCatching { controllerFuture.get() }
                    .onSuccess { controller = it }
                    .onFailure {
                        connectionError = it.cause?.message ?: it.message
                            ?: "Player connection failed."
                    }
            },
            ContextCompat.getMainExecutor(this)
        )

        setContent {
            val app = application as MusicApp
            val settings by app.settings.settings.collectAsStateWithLifecycle()
            val systemDark = (this@MainActivity.resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

            MaterialTheme(colorScheme = appColorScheme(settings, systemDark)) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                    contentColor = MaterialTheme.colorScheme.onBackground
                ) {
                    val requestPermission = rememberLauncherForActivityResult(
                        ActivityResultContracts.RequestPermission()
                    ) { allowed ->
                        permissionGranted = allowed
                        if (allowed) refreshLibrary()
                    }

                    if (permissionGranted) {
                        AppRoot(
                            app = app,
                            controller = controller,
                            connectionError = connectionError
                        )
                    } else {
                        PermissionScreen {
                            requestPermission.launch(audioPermission())
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        permissionGranted = hasAudioPermission()
        if (permissionGranted) refreshLibrary()
    }

    private fun refreshLibrary() {
        val repository = (application as MusicApp).repository
        repository.startObserving()
        lifecycleScope.launch { repository.refresh() }
    }

    override fun onDestroy() {
        MediaController.releaseFuture(controllerFuture)
        super.onDestroy()
    }
}

// ASSETS
private class ReferenceAssets(context: Context) {
    private val names = runCatching {
        context.assets.list("reference")?.toSet().orEmpty()
    }.getOrDefault(emptySet())

    fun get(name: String): String? =
        if (name in names) "file:///android_asset/reference/$name" else null
}

private val LocalAssets =
    staticCompositionLocalOf<ReferenceAssets> {
        error("ReferenceAssets not provided")
    }

// PLAYBACK STATE
private data class PlaybackView(
    val item: MediaItem? = null,
    val playing: Boolean = false,
    val position: Long = 0,
    val duration: Long = 0,
    val shuffle: Boolean = false,
    val repeat: Int = Player.REPEAT_MODE_OFF,
    val queue: List<MediaItem> = emptyList(),
    val index: Int = -1,
    val error: String? = null
)

@Composable
private fun playbackView(controller: MediaController?): PlaybackView {
    var state by remember(controller) { mutableStateOf(PlaybackView()) }

    LaunchedEffect(controller) {
        if (controller == null) return@LaunchedEffect

        var timeline: androidx.media3.common.Timeline? = null
        var queue = emptyList<MediaItem>()

        while (true) {
            if (timeline !== controller.currentTimeline) {
                timeline = controller.currentTimeline
                queue = List(controller.mediaItemCount) {
                    controller.getMediaItemAt(it)
                }
            }

            state = PlaybackView(
                item = controller.currentMediaItem,
                playing = controller.isPlaying,
                position = controller.currentPosition.coerceAtLeast(0),
                duration = controller.duration
                    .takeUnless { it == C.TIME_UNSET || it < 0 } ?: 0L,
                shuffle = controller.shuffleModeEnabled,
                repeat = controller.repeatMode,
                queue = queue,
                index = controller.currentMediaItemIndex,
                error = controller.playerError?.localizedMessage
            )

            delay(250)
        }
    }

    return state
}

private fun togglePlay(controller: MediaController?) {
    controller ?: return

    if (controller.isPlaying) controller.pause()
    else {
        controller.prepare()
        controller.play()
    }
}

private fun cycleRepeat(controller: MediaController?) {
    controller ?: return

    controller.repeatMode = when (controller.repeatMode) {
        Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
        Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
        else -> Player.REPEAT_MODE_OFF
    }
}

// ROOT
private enum class Tab(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Filled.Home),
    LIBRARY("Library", Icons.AutoMirrored.Filled.LibraryBooks),
    SEARCH("Search", Icons.Filled.Search),
    PROFILE("Profile", Icons.Filled.Person)
}

private enum class Category(val label: String) {
    ALL("All"),
    NEW("New Release"),
    TRENDING("Trending"),
    MOST_PLAYED("Most played")
}

@Composable
private fun AppRoot(
    app: MusicApp,
    controller: MediaController?,
    connectionError: String?
) {
    val context = LocalContext.current
    val assets = remember(context) { ReferenceAssets(context) }
    val scope = rememberCoroutineScope()
    val dao = app.database.musicDao()

    val tracks by app.repository.tracks.collectAsStateWithLifecycle()
    val scanError by app.repository.error.collectAsStateWithLifecycle()
    val settings by app.settings.settings.collectAsStateWithLifecycle()

    val states by remember(dao) {
        dao.observeTrackStates()
    }.collectAsStateWithLifecycle(initialValue = emptyList())

    val playlists by remember(dao) {
        dao.observePlaylists()
    }.collectAsStateWithLifecycle(initialValue = emptyList())

    val entries by remember(dao) {
        dao.observePlaylistEntries()
    }.collectAsStateWithLifecycle(initialValue = emptyList())

    val statesById = remember(states) { states.associateBy { it.mediaId } }
    val tracksById = remember(tracks) { tracks.associateBy { it.id } }

    val playback = playbackView(controller)

    var tab by remember(settings.launchScreen) {
        mutableStateOf(
            when (settings.launchScreen) {
                "LIBRARY" -> Tab.LIBRARY
                "LAST_PLAYED" -> if (playback.item != null) Tab.HOME else Tab.LIBRARY
                else -> Tab.HOME
            }
        )
    }
    var category by remember { mutableStateOf(Category.ALL) }
    var searchQuery by remember { mutableStateOf("") }

    var playerOpen by remember { mutableStateOf(false) }
    var queueOpen by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }
    var libraryToolsOpen by remember { mutableStateOf(false) }
    var playlistsOpen by remember { mutableStateOf(false) }

    var songMenu by remember { mutableStateOf<Track?>(null) }
    var addToPlaylist by remember { mutableStateOf<Track?>(null) }

    var createPlaylist by remember { mutableStateOf(false) }
    var editProfileOpen by remember { mutableStateOf(false) }
    var renamePlaylist by remember { mutableStateOf<UserPlaylist?>(null) }
    var deletePlaylist by remember { mutableStateOf<UserPlaylist?>(null) }
    var openedPlaylist by remember { mutableStateOf<UserPlaylist?>(null) }

    fun startPlayback(list: List<Track>, index: Int = 0) {
        val player = controller ?: return
        if (list.isEmpty()) return

        player.setMediaItems(
            list.map(Track::toMediaItem),
            index.coerceIn(list.indices),
            0L
        )
        player.prepare()
        player.play()
        playerOpen = true
    }

    fun toggleFavorite(id: String) {
        scope.launch { dao.toggleFavorite(id) }
    }

    val filteredTracks = remember(tracks, statesById, category, searchQuery) {
        val filtered = tracks.filter {
            searchQuery.isBlank() ||
                it.title.contains(searchQuery, true) ||
                it.artist.contains(searchQuery, true) ||
                it.album.contains(searchQuery, true)
        }

        when (category) {
            Category.ALL -> filtered
            Category.NEW ->
                filtered.sortedByDescending { it.dateAdded }
            Category.TRENDING ->
                filtered
                    .filter { (statesById[it.id]?.lastPlayed ?: 0L) > 0 }
                    .sortedByDescending {
                        statesById[it.id]?.lastPlayed ?: 0L
                    }
            Category.MOST_PLAYED ->
                filtered
                    .filter { (statesById[it.id]?.playCount ?: 0L) > 0 }
                    .sortedByDescending {
                        statesById[it.id]?.playCount ?: 0L
                    }
        }
    }

    val discovery = remember(tracks) {
        tracks.sortedByDescending { it.dateAdded }.take(30)
    }

    val albumCollections = remember(tracks) {
        LibraryFeatures.albums(tracks).take(20)
    }

    BackHandler(enabled = playerOpen) {
        playerOpen = false
    }

    CompositionLocalProvider(LocalAssets provides assets) {
        if (playerOpen && playback.item != null) {
            NowPlayingScreen(
                playback = playback,
                favorite =
                    statesById[playback.item.mediaId]?.favorite == true,
                onBack = { playerOpen = false },
                onNotifications = { settingsOpen = true },
                onFavorite = {
                    toggleFavorite(playback.item.mediaId)
                },
                onPrevious = { controller?.seekToPrevious() },
                onNext = { controller?.seekToNext() },
                onPlayPause = { togglePlay(controller) },
                onShuffle = {
                    controller?.shuffleModeEnabled = !playback.shuffle
                },
                onRepeat = { cycleRepeat(controller) },
                onSeek = { controller?.seekTo(it) },
                onQueue = { queueOpen = true }
            )
        } else {
            AppScaffold(
                tab = tab,
                onTab = { tab = it },
                playback = playback,
                dynamicBackdrop = settings.dynamicBackdrop,
                onCenterPlay = {
                    if (playback.item != null) {
                        playerOpen = true
                    } else if (filteredTracks.isNotEmpty()) {
                        startPlayback(filteredTracks)
                    }
                }
            ) {
                when (tab) {
                    Tab.HOME -> HomeScreen(
                        profileName = settings.profileName,
                        connectionError = connectionError,
                        scanError = scanError,
                        category = category,
                        onCategory = { category = it },
                        discovery = discovery,
                        collections = albumCollections,
                        onPlayDiscovery = { startPlayback(discovery) },
                        onPlayCollection = { list -> startPlayback(list) },
                        onOpenNotifications = { settingsOpen = true },
                        onOpenSearch = { tab = Tab.SEARCH },
                        onOpenProfile = { tab = Tab.PROFILE }
                    )

                    Tab.LIBRARY -> LibraryScreen(
                        profileName = settings.profileName,
                        category = category,
                        onCategory = { category = it },
                        tracks = filteredTracks,
                        collections = albumCollections,
                        states = statesById,
                        onPlayCollection = { startPlayback(it) },
                        onPlayTrack = { list, index ->
                            startPlayback(list, index)
                        },
                        onOpenSearch = { tab = Tab.SEARCH },
                        onOpenNotifications = { settingsOpen = true },
                        onOpenProfile = { tab = Tab.PROFILE }
                    )

                    Tab.SEARCH -> SearchScreen(
                        query = searchQuery,
                        onQuery = { searchQuery = it },
                        results = filteredTracks,
                        onPlay = { list, index ->
                            startPlayback(list, index)
                        },
                        onMore = { songMenu = it }
                    )

                    Tab.PROFILE -> ProfileScreen(
                        playlists = playlists,
                        entries = entries,
                        tracks = tracks,
                        tracksById = tracksById,
                        states = states,
                        profileName = settings.profileName,
                        username = settings.username,
                        onOpenSettings = { settingsOpen = true },
                        onEditProfile = { editProfileOpen = true },
                        onOpenLibraryTools = { libraryToolsOpen = true },
                        onOpenPlaylist = { openedPlaylist = it },
                        onCreatePlaylist = { createPlaylist = true },
                        onPlayCollection = { startPlayback(it) }
                    )
                }
            }
        }

        if (queueOpen) {
            QueueDialog(
                playback = playback,
                controller = controller,
                onDismiss = { queueOpen = false }
            )
        }

        if (settingsOpen) {
            SettingsScreen(
                settings = settings,
                profileName = settings.profileName,
                username = settings.username,
                onBack = { settingsOpen = false },
                onSpeed = { scope.launch { app.settings.setSpeed(it) } },
                onPitch = { scope.launch { app.settings.setPitch(it) } },
                onGapless = { scope.launch { app.settings.setGaplessPlayback(it) } },
                onResumeOnLaunch = {
                    scope.launch { app.settings.setResumeOnLaunch(it) }
                },
                onDynamicBackdrop = {
                    scope.launch { app.settings.setDynamicBackdrop(it) }
                },
                onKeepScreenAwake = {
                    scope.launch { app.settings.setKeepScreenAwake(it) }
                },
                onLockScreenArt = {
                    scope.launch { app.settings.setLockScreenArt(it) }
                },
                onThemeMode = { scope.launch { app.settings.setThemeMode(it) } },
                onAccentHue = { scope.launch { app.settings.setAccentHue(it) } },
                onLaunchScreen = {
                    scope.launch { app.settings.setLaunchScreen(it) }
                },
                onFilterShortAudio = {
                    scope.launch { app.settings.setFilterShortAudioSeconds(it) }
                },
                onSleepTimer = {
                    scope.launch { app.settings.setSleepTimerMinutes(it) }
                    controller?.sendCustomCommand(
                        PlaybackService.SLEEP_COMMAND,
                        Bundle().apply {
                            putInt(PlaybackService.SLEEP_MINUTES, it)
                        }
                    )
                },
                onRefresh = { scope.launch { app.repository.refresh() } },
                onPlaylists = {
                    settingsOpen = false
                    playlistsOpen = true
                },
                onLibraryTools = {
                    settingsOpen = false
                    libraryToolsOpen = true
                },
                onReset = { scope.launch { app.settings.reset() } }
            )
        }

        if (editProfileOpen) {
            EditProfileDialog(
                profileName = settings.profileName,
                username = settings.username,
                onSave = { name, username ->
                    scope.launch {
                        app.settings.setProfileName(name)
                        app.settings.setUsername(username)
                    }
                    editProfileOpen = false
                },
                onDismiss = { editProfileOpen = false }
            )
        }

        if (libraryToolsOpen) {
            LibraryToolsDialog(
                tracks = tracks,
                states = states,
                controller = controller,
                onPlay = ::startPlayback,
                onDismiss = { libraryToolsOpen = false }
            )
        }

        songMenu?.let { song ->
            AlertDialog(
                onDismissRequest = { songMenu = null },
                title = { Text(song.title) },
                text = {
                    Column {
                        DialogAction("Play next") {
                            controller?.let {
                                val insertAt = if (it.mediaItemCount > 0) {
                                    (it.currentMediaItemIndex + 1)
                                        .coerceIn(0, it.mediaItemCount)
                                } else 0

                                it.addMediaItem(insertAt, song.toMediaItem())
                            }
                            songMenu = null
                        }

                        DialogAction("Add to queue") {
                            controller?.addMediaItem(song.toMediaItem())
                            songMenu = null
                        }

                        DialogAction(
                            if (statesById[song.id]?.favorite == true)
                                "Remove favorite" else "Add favorite"
                        ) {
                            toggleFavorite(song.id)
                            songMenu = null
                        }

                        DialogAction("Add to playlist") {
                            addToPlaylist = song
                            songMenu = null
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { songMenu = null }) {
                        Text("Close")
                    }
                }
            )
        }

        if (playlistsOpen || addToPlaylist != null) {
            PlaylistsDialog(
                playlists = playlists,
                addingSong = addToPlaylist,
                onDismiss = {
                    playlistsOpen = false
                    addToPlaylist = null
                },
                onCreate = { createPlaylist = true },
                onRename = { renamePlaylist = it },
                onDelete = { deletePlaylist = it },
                onOpen = {
                    openedPlaylist = it
                    playlistsOpen = false
                },
                onAdd = { song, playlist ->
                    scope.launch {
                        dao.addPlaylistEntry(
                            PlaylistEntry(
                                playlistId = playlist.id,
                                mediaId = song.id
                            )
                        )
                    }
                    addToPlaylist = null
                }
            )
        }

        openedPlaylist?.let { playlist ->
            val songs = entries
                .filter { it.playlistId == playlist.id }
                .mapNotNull { tracksById[it.mediaId] }

            PlaylistDetailsDialog(
                playlist = playlist,
                songs = songs,
                onDismiss = { openedPlaylist = null },
                onPlay = { list, index -> startPlayback(list, index) },
                onRemove = { song ->
                    scope.launch {
                        dao.removePlaylistEntry(playlist.id, song.id)
                    }
                }
            )
        }

        if (createPlaylist) {
            NameDialog(
                title = "Create playlist",
                initialName = "",
                onDismiss = { createPlaylist = false },
                onSave = {
                    scope.launch {
                        dao.insertPlaylist(UserPlaylist(name = it))
                    }
                    createPlaylist = false
                }
            )
        }

        renamePlaylist?.let { playlist ->
            NameDialog(
                title = "Rename playlist",
                initialName = playlist.name,
                onDismiss = { renamePlaylist = null },
                onSave = {
                    scope.launch {
                        dao.renamePlaylist(playlist.id, it)
                    }
                    renamePlaylist = null
                }
            )
        }

        deletePlaylist?.let { playlist ->
            AlertDialog(
                onDismissRequest = { deletePlaylist = null },
                title = { Text("Delete ${playlist.name}?") },
                text = { Text("Your audio files stay on the device.") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            scope.launch {
                                dao.deletePlaylist(playlist.id)
                            }
                            deletePlaylist = null
                        }
                    ) { Text("Delete") }
                },
                dismissButton = {
                    TextButton(onClick = { deletePlaylist = null }) {
                        Text("Cancel")
                    }
                }
            )
        }
    }
}

// SCAFFOLD
@Composable
private fun AppScaffold(
    tab: Tab,
    onTab: (Tab) -> Unit,
    playback: PlaybackView,
    dynamicBackdrop: Boolean,
    onCenterPlay: () -> Unit,
    content: @Composable () -> Unit
) {
    Box(Modifier.fillMaxSize()) {
        BackgroundWash(dynamicBackdrop)

        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
        ) {
            Box(Modifier.weight(1f)) { content() }

            BottomBar(
                tab = tab,
                onTab = onTab,
                playing = playback.playing,
                onCenterPlay = onCenterPlay
            )
        }
    }
}

@Composable
private fun BackgroundWash(dynamicBackdrop: Boolean = true) {
    val accentColor = MaterialTheme.colorScheme.primary
    val backgroundColor = MaterialTheme.colorScheme.background
    val surfaceColor = MaterialTheme.colorScheme.surface

    Box(
        Modifier
            .fillMaxSize()
            .background(
                if (backgroundColor.luminance() > 0.5f) {
                    Brush.verticalGradient(
                        listOf(
                            backgroundColor,
                            surfaceColor
                        )
                    )
                } else {
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0f to PurpleMid,
                            0.35f to PurpleDeep,
                            1f to backgroundColor
                        )
                    )
                }
            )
    ) {
        if (dynamicBackdrop) {
            Canvas(Modifier.matchParentSize()) {
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(
                            accentColor.copy(alpha = 0.35f),
                            Color.Transparent
                        ),
                        center = Offset(size.width * 0.5f, size.height * 0.05f),
                        radius = size.width * 0.8f
                    ),
                    center = Offset(size.width * 0.5f, size.height * 0.05f),
                    radius = size.width * 0.8f
                )
            }
        }
    }
}

@Composable
private fun BottomBar(
    tab: Tab,
    onTab: (Tab) -> Unit,
    playing: Boolean,
    onCenterPlay: () -> Unit
) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(84.dp)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(Color.Black.copy(alpha = 0.45f))
                .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(28.dp))
        )

        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TabButton(Tab.HOME, tab == Tab.HOME) { onTab(Tab.HOME) }
            TabButton(Tab.LIBRARY, tab == Tab.LIBRARY) { onTab(Tab.LIBRARY) }

            Spacer(Modifier.width(60.dp))

            TabButton(Tab.SEARCH, tab == Tab.SEARCH) { onTab(Tab.SEARCH) }
            TabButton(Tab.PROFILE, tab == Tab.PROFILE) { onTab(Tab.PROFILE) }
        }

        Box(
            Modifier
                .align(Alignment.Center)
                .offset(y = (-10).dp)
        ) {
            CenterPlayButton(playing = playing, onClick = onCenterPlay)
        }
    }
}

@Composable
private fun TabButton(
    tab: Tab,
    selected: Boolean,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            tab.icon,
            contentDescription = tab.label,
            tint = if (selected) PurpleGlow else TextFaint,
            modifier = Modifier.size(22.dp)
        )

        Text(
            tab.label,
            fontSize = 9.sp,
            color = if (selected) PurpleGlow else TextFaint
        )

        Box(
            modifier = Modifier
                .padding(top = 3.dp)
                .size(4.dp)
                .clip(CircleShape)
                .background(
                    if (selected) PurpleGlow else Color.Transparent
                )
        )
    }
}

@Composable
private fun CenterPlayButton(playing: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(64.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.matchParentSize()) {
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(
                        PurpleGlow.copy(alpha = 0.55f),
                        Color.Transparent
                    ),
                    radius = size.minDimension * 0.7f
                ),
                radius = size.minDimension * 0.7f
            )
        }

        FilledIconButton(
            onClick = onClick,
            modifier = Modifier.size(56.dp),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = PurpleGlow,
                contentColor = Color.White
            )
        ) {
            Icon(
                if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (playing) "Pause" else "Play",
                modifier = Modifier.size(26.dp)
            )
        }
    }
}

// HEADER
@Composable
private fun UserHeader(
    profileName: String,
    onOpenSearch: () -> Unit,
    onOpenNotifications: () -> Unit,
    onOpenProfile: () -> Unit
) {
    val avatar = LocalAssets.current.get("profile.webp")

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(CircleShape)
                .border(
                    2.dp,
                    Brush.sweepGradient(
                        listOf(PurpleGlow, Pink, PurpleGlow)
                    ),
                    CircleShape
                )
                .padding(3.dp)
                .clip(CircleShape)
                .background(PurpleDeep)
                .clickable(onClick = onOpenProfile),
            contentAlignment = Alignment.Center
        ) {
            if (avatar != null) {
                AsyncImage(
                    model = avatar,
                    contentDescription = "Profile",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().clip(CircleShape)
                )
            } else {
                Icon(
                    Icons.Filled.Person,
                    contentDescription = null,
                    tint = TextWhite
                )
            }
        }

        Spacer(Modifier.width(12.dp))

        Column(Modifier.weight(1f)) {
            Text(
                "Hello 👋",
                color = TextMuted,
                fontSize = 12.sp
            )
            Text(
                profileName,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold
            )
        }

        GlassCircle(Icons.Filled.Search, "Search", onClick = onOpenSearch)
        Spacer(Modifier.width(10.dp))
        GlassCircle(
            Icons.Filled.Notifications,
            "Notifications",
            onClick = onOpenNotifications
        )
    }
}

@Composable
private fun GlassCircle(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(GlassSurface)
            .border(1.dp, GlassBorder, CircleShape)
    ) {
        Icon(
            icon,
            contentDescription = description,
            tint = TextWhite,
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun CategoryChips(
    selected: Category,
    onSelect: (Category) -> Unit
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(Category.entries) { item ->
            val active = item == selected
            val shape = RoundedCornerShape(50)

            Box(
                modifier = Modifier
                    .clip(shape)
                    .background(
                        if (active)
                            Brush.horizontalGradient(
                                listOf(PurpleGlow, Magenta)
                            )
                        else
                            Brush.horizontalGradient(
                                listOf(GlassSurface, GlassSurface)
                            )
                    )
                    .border(
                        1.dp,
                        if (active) Color.Transparent else GlassBorder,
                        shape
                    )
                    .clickable { onSelect(item) }
                    .padding(horizontal = 20.dp, vertical = 10.dp)
            ) {
                Text(
                    item.label,
                    color = if (active) Color.White else TextMuted,
                    fontSize = 13.sp,
                    fontWeight = if (active) FontWeight.SemiBold
                    else FontWeight.Normal
                )
            }
        }
    }
}

// HOME SCREEN
@Composable
private fun HomeScreen(
    profileName: String,
    connectionError: String?,
    scanError: String?,
    category: Category,
    onCategory: (Category) -> Unit,
    discovery: List<Track>,
    collections: List<MusicCollection>,
    onPlayDiscovery: () -> Unit,
    onPlayCollection: (List<Track>) -> Unit,
    onOpenNotifications: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenProfile: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 30.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            UserHeader(
                profileName = profileName,
                onOpenSearch = onOpenSearch,
                onOpenNotifications = onOpenNotifications,
                onOpenProfile = onOpenProfile
            )
        }

        val error = connectionError ?: scanError
        if (error != null) {
            item {
                Text(
                    error,
                    color = Pink,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 20.dp)
                )
            }
        }

        item {
            CategoryChips(selected = category, onSelect = onCategory)
        }

        item {
            SectionHeader(
                title = "Curated & trending",
                action = null
            )
        }

        item {
            DiscoverWeeklyCard(
                discovery = discovery,
                onPlay = onPlayDiscovery
            )
        }

        item {
            SectionHeader(
                title = "Top daily playlists",
                action = "See all",
                onAction = onOpenSearch
            )
        }

        if (collections.isEmpty()) {
            item {
                Text(
                    "Play some music, and daily collections will build here.",
                    color = TextMuted,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 20.dp)
                )
            }
        }

        items(collections, key = { it.id }) { collection ->
            PlaylistRow(
                collection = collection,
                onPlay = { onPlayCollection(collection.tracks) }
            )
        }
    }
}

@Composable
private fun SectionHeader(
    title: String,
    action: String?,
    onAction: () -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f)
        )

        if (action != null) {
            Text(
                action,
                color = TextMuted,
                fontSize = 12.sp,
                modifier = Modifier
                    .clickable(onClick = onAction)
                    .padding(4.dp)
            )
        }
    }
}

@Composable
private fun DiscoverWeeklyCard(
    discovery: List<Track>,
    onPlay: () -> Unit
) {
    val asset = LocalAssets.current.get("discover.webp")

    Box(
        modifier = Modifier
            .padding(horizontal = 20.dp)
            .fillMaxWidth()
            .height(190.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(GlassSurface)
            .border(1.dp, GlassBorder, RoundedCornerShape(24.dp))
    ) {
        AsyncImage(
            model = asset ?: discovery.firstOrNull()?.artworkUri,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .fillMaxWidth(0.55f)
        )

        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        colorStops = arrayOf(
                            0f to PurpleDeep.copy(alpha = 0.9f),
                            0.55f to PurpleDeep.copy(alpha = 0.55f),
                            1f to Color.Transparent
                        )
                    )
                )
        )

        Column(
            Modifier
                .fillMaxSize()
                .padding(18.dp)
        ) {
            Text(
                "Discover weekly",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = TextWhite
            )

            Spacer(Modifier.height(6.dp))

            Text(
                "The original slow instrumental best play lists.",
                color = TextMuted,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                modifier = Modifier.widthIn(max = 200.dp)
            )

            Spacer(Modifier.weight(1f))

            Row(verticalAlignment = Alignment.CenterVertically) {
                FilledIconButton(
                    onClick = onPlay,
                    enabled = discovery.isNotEmpty(),
                    modifier = Modifier.size(44.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = PurpleGlow,
                        contentColor = Color.White
                    )
                ) {
                    Icon(
                        Icons.Filled.PlayArrow,
                        contentDescription = "Play discovery collection"
                    )
                }

                Spacer(Modifier.width(14.dp))

                Icon(
                    Icons.Outlined.FavoriteBorder,
                    contentDescription = null,
                    tint = TextMuted,
                    modifier = Modifier.size(20.dp)
                )

                Spacer(Modifier.width(14.dp))

                Icon(
                    Icons.Outlined.FileDownload,
                    contentDescription = null,
                    tint = TextMuted,
                    modifier = Modifier.size(20.dp)
                )

                Spacer(Modifier.width(14.dp))

                Icon(
                    Icons.Filled.MoreHoriz,
                    contentDescription = null,
                    tint = TextMuted,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
private fun PlaylistRow(
    collection: MusicCollection,
    onPlay: () -> Unit
) {
    Row(
        modifier = Modifier
            .padding(horizontal = 20.dp)
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(GlassSurface)
            .border(1.dp, GlassBorder, RoundedCornerShape(20.dp))
            .clickable(onClick = onPlay)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Artwork(
            model = collection.tracks.firstOrNull()?.artworkUri,
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(14.dp))
        )

        Spacer(Modifier.width(14.dp))

        Column(Modifier.weight(1f)) {
            Text(
                collection.title,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(Modifier.height(4.dp))

            Text(
                collection.subtitle,
                color = TextMuted,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(GlassSurface)
                .border(1.dp, GlassBorder, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Filled.PlayArrow,
                contentDescription = "Play ${collection.title}",
                tint = TextWhite,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun Artwork(model: Any?, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.background(
            Brush.linearGradient(
                listOf(PurpleGlow, Magenta, Pink)
            )
        ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.Filled.MusicNote,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.55f),
            modifier = Modifier.size(22.dp)
        )

        AsyncImage(
            model = model,
            contentDescription = "Album artwork",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
    }
}

// LIBRARY SCREEN
@Composable
private fun LibraryScreen(
    profileName: String,
    category: Category,
    onCategory: (Category) -> Unit,
    tracks: List<Track>,
    collections: List<MusicCollection>,
    states: Map<String, TrackState>,
    onPlayCollection: (List<Track>) -> Unit,
    onPlayTrack: (List<Track>, Int) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenNotifications: () -> Unit,
    onOpenProfile: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 30.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            UserHeader(
                profileName = profileName,
                onOpenSearch = onOpenSearch,
                onOpenNotifications = onOpenNotifications,
                onOpenProfile = onOpenProfile
            )
        }

        item {
            Text(
                "Your Music Library",
                fontSize = 30.sp,
                fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.padding(horizontal = 20.dp)
            )
        }

        item {
            CategoryChips(selected = category, onSelect = onCategory)
        }

        if (collections.isNotEmpty()) {
            items(collections, key = { "col:${it.id}" }) { collection ->
                PlaylistRow(
                    collection = collection,
                    onPlay = { onPlayCollection(collection.tracks) }
                )
            }
        }

        if (tracks.isEmpty() && collections.isEmpty()) {
            item {
                Text(
                    "No music found. Try a different filter or refresh.",
                    color = TextMuted,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 20.dp)
                )
            }
        }

        if (tracks.isNotEmpty()) {
            item {
                Text(
                    "All songs",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextMuted,
                    modifier = Modifier.padding(
                        start = 20.dp, top = 8.dp
                    )
                )
            }

            itemsIndexed(
                tracks.take(40),
                key = { _, song -> "song:${song.id}" }
            ) { index, song ->
                SongRow(
                    song = song,
                    favorite = states[song.id]?.favorite == true,
                    onClick = { onPlayTrack(tracks, index) }
                )
            }
        }
    }
}

@Composable
private fun SongRow(
    song: Track,
    favorite: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .padding(horizontal = 20.dp)
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(GlassSurface)
            .border(1.dp, GlassBorder, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Artwork(
            model = song.artworkUri,
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(12.dp))
        )

        Spacer(Modifier.width(12.dp))

        Column(Modifier.weight(1f)) {
            Text(
                song.title,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Text(
                "By ${song.artist} • ${formatTime(song.durationMs)}",
                color = TextMuted,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        if (favorite) {
            Icon(
                Icons.Filled.Favorite,
                contentDescription = "Favorite",
                tint = Pink,
                modifier = Modifier.size(14.dp)
            )
        }

        Spacer(Modifier.width(8.dp))

        Icon(
            Icons.Filled.PlayArrow,
            contentDescription = "Play",
            tint = TextWhite,
            modifier = Modifier.size(20.dp)
        )
    }
}

// SEARCH SCREEN
@Composable
private fun SearchScreen(
    query: String,
    onQuery: (String) -> Unit,
    results: List<Track>,
    onPlay: (List<Track>, Int) -> Unit,
    onMore: (Track) -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        Spacer(Modifier.height(20.dp))

        Text(
            "Search",
            fontSize = 26.sp,
            fontWeight = FontWeight.ExtraBold,
            modifier = Modifier.padding(horizontal = 20.dp)
        )

        Spacer(Modifier.height(14.dp))

        BasicTextField(
            value = query,
            onValueChange = onQuery,
            singleLine = true,
            textStyle = TextStyle(color = TextWhite, fontSize = 14.sp),
            cursorBrush = Brush.linearGradient(listOf(PurpleGlow, PurpleGlow)),
            modifier = Modifier
                .padding(horizontal = 20.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(GlassSurface)
                .border(1.dp, GlassBorder, RoundedCornerShape(20.dp))
                .padding(horizontal = 16.dp, vertical = 14.dp),
            decorationBox = { input ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Search,
                        contentDescription = null,
                        tint = TextMuted,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Box(Modifier.weight(1f)) {
                        if (query.isEmpty()) {
                            Text(
                                "Search songs, artists, albums…",
                                color = TextMuted,
                                fontSize = 13.sp
                            )
                        }
                        input()
                    }
                }
            }
        )

        Spacer(Modifier.height(12.dp))

        LazyColumn(
            contentPadding = PaddingValues(bottom = 30.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (results.isEmpty()) {
                item {
                    Text(
                        "No matches yet.",
                        color = TextMuted,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 20.dp)
                    )
                }
            }

            itemsIndexed(
                results,
                key = { _, song -> "search:${song.id}" }
            ) { index, song ->
                Row(
                    modifier = Modifier
                        .padding(horizontal = 20.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(GlassSurface)
                        .border(1.dp, GlassBorder, RoundedCornerShape(16.dp))
                        .pointerInput(song.id) {
                            detectTapGestures(
                                onTap = { onPlay(results, index) },
                                onLongPress = { onMore(song) }
                            )
                        }
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Artwork(
                        model = song.artworkUri,
                        modifier = Modifier
                            .size(46.dp)
                            .clip(RoundedCornerShape(12.dp))
                    )

                    Spacer(Modifier.width(12.dp))

                    Column(Modifier.weight(1f)) {
                        Text(
                            song.title,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )

                        Text(
                            "By ${song.artist}",
                            color = TextMuted,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Icon(
                        Icons.Filled.MoreVert,
                        contentDescription = "Options",
                        tint = TextMuted,
                        modifier = Modifier
                            .size(20.dp)
                            .clickable { onMore(song) }
                    )
                }
            }
        }
    }
}

// PROFILE SCREEN
@Composable
private fun ProfileScreen(
    playlists: List<UserPlaylist>,
    entries: List<PlaylistEntry>,
    tracks: List<Track>,
    tracksById: Map<String, Track>,
    states: List<TrackState>,
    profileName: String,
    username: String,
    onOpenSettings: () -> Unit,
    onEditProfile: () -> Unit,
    onOpenLibraryTools: () -> Unit,
    onOpenPlaylist: (UserPlaylist) -> Unit,
    onCreatePlaylist: () -> Unit,
    onPlayCollection: (List<Track>) -> Unit
) {
    val avatar = LocalAssets.current.get("profile.webp")

    val listeningTimeMinutes = remember(states, tracksById) {
        states.sumOf { state ->
            val duration = tracksById[state.mediaId]?.durationMs ?: 0L
            (duration / 60000L) * state.playCount
        }
    }

    val topArtists = remember(states, tracksById) {
        states
            .mapNotNull { s ->
                tracksById[s.mediaId]?.let { it.artist to s.playCount }
            }
            .groupBy({ it.first }, { it.second })
            .mapValues { it.value.sum() }
            .entries
            .sortedByDescending { it.value }
            .take(4)
            .map { it.key }
    }

    val favoritesCount = remember(states) {
        states.count { it.favorite }
    }

    val hours = listeningTimeMinutes / 60.0

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 30.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Profile",
                    fontSize = 26.sp,
                    fontWeight = FontWeight.ExtraBold
                )

                Spacer(Modifier.width(10.dp))

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(PurpleDeep)
                        .border(1.dp, PurpleGlow, RoundedCornerShape(12.dp))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(
                        "VIP",
                        color = PurpleGlow,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(Modifier.weight(1f))

                GlassCircle(
                    Icons.Outlined.FileUpload,
                    "Share profile",
                    onClick = onOpenLibraryTools
                )

                Spacer(Modifier.width(10.dp))

                GlassCircle(
                    Icons.Filled.Settings,
                    "Settings",
                    onClick = onOpenSettings
                )
            }
        }

        item {
            Column(
                modifier = Modifier
                    .padding(horizontal = 20.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .background(GlassSurface)
                    .border(1.dp, GlassBorder, RoundedCornerShape(24.dp))
                    .padding(vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier.size(120.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(120.dp)
                            .clip(CircleShape)
                            .border(
                                3.dp,
                                Brush.sweepGradient(
                                    listOf(
                                        PurpleGlow,
                                        Pink,
                                        Color(0xFF67E8F9),
                                        PurpleGlow
                                    )
                                ),
                                CircleShape
                            )
                            .padding(4.dp)
                            .clip(CircleShape)
                            .background(PurpleDeep),
                        contentAlignment = Alignment.Center
                    ) {
                        if (avatar != null) {
                            AsyncImage(
                                model = avatar,
                                contentDescription = "Avatar",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize().clip(CircleShape)
                            )
                        } else {
                            Icon(
                                Icons.Filled.Person,
                                contentDescription = null,
                                tint = TextWhite,
                                modifier = Modifier.size(48.dp)
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(6.dp)
                            .size(20.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF10B981))
                            .border(2.dp, Ink, CircleShape)
                    )
                }

                Spacer(Modifier.height(14.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        profileName,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(Modifier.width(6.dp))

                    Box(
                        modifier = Modifier
                            .size(18.dp)
                            .clip(CircleShape)
                            .background(PurpleGlow),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(12.dp)
                        )
                    }
                }

                Text(
                    "@$username • Pro Listener",
                    color = TextMuted,
                    fontSize = 12.sp
                )

                Spacer(Modifier.height(14.dp))

                Row(
                    modifier = Modifier
                        .padding(horizontal = 20.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.Black.copy(alpha = 0.25f))
                        .padding(vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    ProfileStat(value = tracks.size.toString(), label = "SONGS")
                    ProfileStat(value = playlists.size.toString(), label = "PLAYLISTS")
                    ProfileStat(value = favoritesCount.toString(), label = "FAVORITES")
                }

                OutlinedButton(
                    onClick = onEditProfile,
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier.padding(top = 14.dp)
                ) {
                    Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Edit profile")
                }

                Spacer(Modifier.height(16.dp))

                Row(
                    modifier = Modifier.padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = onOpenLibraryTools,
                        modifier = Modifier
                            .weight(1f)
                            .height(46.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = PurpleGlow,
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(30.dp)
                    ) {
                        Text("Browse library", fontWeight = FontWeight.SemiBold)
                    }

                    OutlinedButton(
                        onClick = onOpenSettings,
                        modifier = Modifier
                            .weight(1f)
                            .height(46.dp),
                        shape = RoundedCornerShape(30.dp)
                    ) {
                        Text("Settings")
                    }
                }
            }
        }

        item {
            SectionHeader(title = "Music Insights", action = "This Month")
        }

        item {
            Row(
                modifier = Modifier
                    .padding(horizontal = 20.dp)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                InsightCard(
                    title = "Listening Time",
                    icon = Icons.Filled.AccessTime,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        String.format(Locale.US, "%.1f hrs", hours),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Based on your play history",
                        color = TextMuted,
                        fontSize = 10.sp
                    )
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(
                        progress = { (hours / 40.0).toFloat().coerceIn(0f, 1f) },
                        color = PurpleGlow,
                        trackColor = Color.White.copy(alpha = 0.08f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(CircleShape)
                    )
                }

                InsightCard(
                    title = "Top Artist",
                    icon = Icons.Filled.MusicNote,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        topArtists.firstOrNull() ?: "—",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Most streamed this month",
                        color = TextMuted,
                        fontSize = 10.sp
                    )
                    Spacer(Modifier.height(10.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(PurpleDeep)
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            "Top listener",
                            color = PurpleGlow,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }

        item { TopArtistsCard(artists = topArtists) }

        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("My Playlists", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(6.dp))
                Text("(${playlists.size})", color = TextMuted, fontSize = 13.sp)
                Spacer(Modifier.weight(1f))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(PurpleDeep)
                        .clickable(onClick = onCreatePlaylist)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = null,
                            tint = PurpleGlow,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "New",
                            color = PurpleGlow,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }

        if (playlists.isEmpty()) {
            item {
                Text(
                    "You have not created any playlists yet.",
                    color = TextMuted,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 20.dp)
                )
            }
        }

        items(playlists, key = { "profilePl:${it.id}" }) { playlist ->
            val songs = entries
                .filter { it.playlistId == playlist.id }
                .mapNotNull { tracksById[it.mediaId] }

            Row(
                modifier = Modifier
                    .padding(horizontal = 20.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(GlassSurface)
                    .border(1.dp, GlassBorder, RoundedCornerShape(16.dp))
                    .clickable { onOpenPlaylist(playlist) }
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Artwork(
                    model = songs.firstOrNull()?.artworkUri,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(12.dp))
                )

                Spacer(Modifier.width(12.dp))

                Column(Modifier.weight(1f)) {
                    Text(
                        playlist.name,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "${songs.size} songs",
                        color = TextMuted,
                        fontSize = 11.sp
                    )
                }

                IconButton(
                    onClick = { onPlayCollection(songs) },
                    enabled = songs.isNotEmpty()
                ) {
                    Icon(
                        Icons.Filled.PlayArrow,
                        contentDescription = "Play playlist",
                        tint = if (songs.isNotEmpty()) TextWhite else TextFaint
                    )
                }
            }
        }
    }
}

@Composable
private fun ProfileStat(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text(label, color = TextMuted, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun InsightCard(
    title: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(GlassSurface)
            .border(1.dp, GlassBorder, RoundedCornerShape(20.dp))
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                fontSize = 12.sp,
                color = TextMuted,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )

            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(PurpleDeep),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = PurpleGlow,
                    modifier = Modifier.size(14.dp)
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        content()
    }
}

@Composable
private fun TopArtistsCard(artists: List<String>) {
    Row(
        modifier = Modifier
            .padding(horizontal = 20.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(GlassSurface)
            .border(1.dp, GlassBorder, RoundedCornerShape(20.dp))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text("Top Artists", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "Most streamed artists this week",
                color = TextMuted,
                fontSize = 11.sp
            )
        }

        Row {
            artists.take(3).forEachIndexed { index, _ ->
                Box(
                    modifier = Modifier
                        .offset(x = (-8 * index).dp)
                        .size(30.dp)
                        .clip(CircleShape)
                        .border(2.dp, Ink, CircleShape)
                        .background(
                            Brush.linearGradient(
                                listOf(PurpleGlow, Pink)
                            )
                        )
                )
            }

            if (artists.size > 3) {
                Box(
                    modifier = Modifier
                        .offset(x = (-24).dp)
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(PurpleDeep)
                        .border(2.dp, Ink, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "+${artists.size - 3}",
                        color = TextWhite,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

// NOW PLAYING
@Composable
private fun NowPlayingScreen(
    playback: PlaybackView,
    favorite: Boolean,
    onBack: () -> Unit,
    onNotifications: () -> Unit,
    onFavorite: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onPlayPause: () -> Unit,
    onShuffle: () -> Unit,
    onRepeat: () -> Unit,
    onSeek: (Long) -> Unit,
    onQueue: () -> Unit
) {
    val item = playback.item ?: return
    val nowArt = LocalAssets.current.get("now_playing.webp")

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Ink)
    ) {
        AsyncImage(
            model = nowArt ?: item.mediaMetadata.artworkUri,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )

        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0f to Color.Black.copy(alpha = 0.4f),
                            0.4f to Color.Transparent,
                            1f to Ink
                        )
                    )
                )
        )

        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                GlassCircle(
                    Icons.Filled.ChevronLeft,
                    "Back",
                    onClick = onBack
                )

                Spacer(Modifier.weight(1f))

                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Color.Black.copy(alpha = 0.35f))
                        .border(1.dp, GlassBorder, RoundedCornerShape(50))
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    PulsingDot()
                    Spacer(Modifier.width(8.dp))
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "NOW",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.5.sp
                        )
                        Text(
                            "PLAYING",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.5.sp
                        )
                    }
                }

                Spacer(Modifier.weight(1f))

                GlassCircle(
                    Icons.Filled.Notifications,
                    "Notifications",
                    onClick = onNotifications
                )
            }

            Spacer(Modifier.weight(1f))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.42f)
                    .clip(
                        RoundedCornerShape(
                            topStart = 48.dp, topEnd = 48.dp
                        )
                    )
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                PurpleDeep,
                                Color(0xFF1B0E2E)
                            )
                        )
                    )
                    .border(
                        1.dp,
                        Color.White.copy(alpha = 0.06f),
                        RoundedCornerShape(topStart = 48.dp, topEnd = 48.dp)
                    )
            ) {
                PlayerConsole(
                    playback = playback,
                    favorite = favorite,
                    onFavorite = onFavorite,
                    onPrevious = onPrevious,
                    onNext = onNext,
                    onPlayPause = onPlayPause,
                    onShuffle = onShuffle,
                    onRepeat = onRepeat,
                    onSeek = onSeek,
                    onQueue = onQueue
                )
            }
        }

        playback.error?.let {
            Text(
                it,
                color = Color.White,
                fontSize = 12.sp,
                modifier = Modifier
                    .safeDrawingPadding()
                    .padding(top = 80.dp, start = 20.dp, end = 20.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(12.dp)
            )
        }
    }
}

@Composable
private fun PulsingDot() {
    val transition = rememberInfiniteTransition(label = "pulse")

    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )

    Box(
        Modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(Color(0xFF67E8F9).copy(alpha = alpha))
    )
}

@Composable
private fun PlayerConsole(
    playback: PlaybackView,
    favorite: Boolean,
    onFavorite: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onPlayPause: () -> Unit,
    onShuffle: () -> Unit,
    onRepeat: () -> Unit,
    onSeek: (Long) -> Unit,
    onQueue: () -> Unit
) {
    var dragPosition by remember(playback.item?.mediaId) {
        mutableStateOf<Float?>(null)
    }

    val displayPosition = dragPosition?.toLong() ?: playback.position

    val progress = if (playback.duration > 0L)
        (displayPosition.toFloat() / playback.duration).coerceIn(0f, 1f)
    else 0f

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
        ) {
            NeonArc(progress = progress, modifier = Modifier.fillMaxSize())

            IconButton(
                onClick = onFavorite,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 10.dp)
                    .size(40.dp)
            ) {
                Icon(
                    if (favorite) Icons.Filled.Favorite
                    else Icons.Outlined.FavoriteBorder,
                    contentDescription = if (favorite) "Remove favorite"
                    else "Add favorite",
                    tint = if (favorite) Pink else TextWhite,
                    modifier = Modifier.size(22.dp)
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onShuffle) {
                Icon(
                    Icons.Filled.Shuffle,
                    contentDescription = "Shuffle",
                    tint = if (playback.shuffle) PurpleGlow
                    else TextMuted,
                    modifier = Modifier.size(20.dp)
                )
            }

            Text(
                "${formatTime(displayPosition)} / ${formatTime(playback.duration)}",
                color = TextMuted,
                fontSize = 11.sp
            )

            IconButton(onClick = onRepeat) {
                Icon(
                    if (playback.repeat == Player.REPEAT_MODE_ONE)
                        Icons.Filled.RepeatOne else Icons.Filled.Repeat,
                    contentDescription = "Repeat",
                    tint = if (playback.repeat != Player.REPEAT_MODE_OFF)
                        PurpleGlow else TextMuted,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            IconButton(
                onClick = onPrevious,
                modifier = Modifier.size(54.dp)
            ) {
                Icon(
                    Icons.Filled.SkipPrevious,
                    contentDescription = "Previous",
                    tint = TextWhite,
                    modifier = Modifier.size(28.dp)
                )
            }

            GlowingPlayButton(
                playing = playback.playing,
                onClick = onPlayPause
            )

            IconButton(
                onClick = onNext,
                modifier = Modifier.size(54.dp)
            ) {
                Icon(
                    Icons.Filled.SkipNext,
                    contentDescription = "Next",
                    tint = TextWhite,
                    modifier = Modifier.size(28.dp)
                )
            }
        }

        Spacer(Modifier.height(6.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Slider(
                value = (dragPosition ?: playback.position.toFloat())
                    .coerceIn(
                        0f,
                        playback.duration.coerceAtLeast(1L).toFloat()
                    ),
                onValueChange = { dragPosition = it },
                onValueChangeFinished = {
                    dragPosition?.let { onSeek(it.toLong()) }
                    dragPosition = null
                },
                valueRange =
                    0f..playback.duration.coerceAtLeast(1L).toFloat(),
                enabled = playback.duration > 0L,
                modifier = Modifier.weight(1f),
                colors = SliderDefaults.colors(
                    thumbColor = TextWhite,
                    activeTrackColor = PurpleGlow,
                    inactiveTrackColor = Color.White.copy(alpha = 0.12f)
                )
            )

            IconButton(onClick = onQueue) {
                Icon(
                    Icons.AutoMirrored.Filled.QueueMusic,
                    contentDescription = "Queue",
                    tint = TextMuted,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
private fun NeonArc(progress: Float, modifier: Modifier) {
    val density = LocalDensity.current

    Canvas(modifier) {
        val startPoint = Offset(size.width * 0.08f, size.height * 0.9f)
        val controlPoint = Offset(size.width * 0.5f, -size.height * 0.2f)
        val endPoint = Offset(size.width * 0.92f, size.height * 0.9f)

        val fullPath = Path().apply {
            moveTo(startPoint.x, startPoint.y)
            quadraticTo(
                controlPoint.x, controlPoint.y,
                endPoint.x, endPoint.y
            )
        }

        drawPath(
            path = fullPath,
            color = Color.White.copy(alpha = 0.15f),
            style = Stroke(
                width = with(density) { 2.dp.toPx() },
                cap = StrokeCap.Round
            )
        )

        val t = progress.coerceIn(0f, 1f)
        val playedPath = Path().apply {
            moveTo(startPoint.x, startPoint.y)
            for (step in 1..24) {
                val sampleT = t * step / 24f
                val inverseSampleT = 1f - sampleT
                lineTo(
                    inverseSampleT * inverseSampleT * startPoint.x +
                        2f * inverseSampleT * sampleT * controlPoint.x +
                        sampleT * sampleT * endPoint.x,
                    inverseSampleT * inverseSampleT * startPoint.y +
                        2f * inverseSampleT * sampleT * controlPoint.y +
                        sampleT * sampleT * endPoint.y
                )
            }
        }

        drawPath(
            path = playedPath,
            brush = Brush.linearGradient(
                colors = listOf(Color(0xFF67E8F9), PurpleGlow, Color.White),
                start = startPoint,
                end = endPoint
            ),
            style = Stroke(
                width = with(density) { 3.dp.toPx() },
                cap = StrokeCap.Round
            )
        )

        val inverseT = 1f - t
        val center = Offset(
            inverseT * inverseT * startPoint.x +
                2f * inverseT * t * controlPoint.x +
                t * t * endPoint.x,
            inverseT * inverseT * startPoint.y +
                2f * inverseT * t * controlPoint.y +
                t * t * endPoint.y
        )

        drawCircle(
            color = PurpleGlow.copy(alpha = 0.6f),
            radius = with(density) { 12.dp.toPx() },
            center = center
        )

        drawCircle(
            color = Color.White,
            radius = with(density) { 6.dp.toPx() },
            center = center
        )
    }
}

@Composable
private fun GlowingPlayButton(playing: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier.size(88.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.matchParentSize()) {
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(
                        PurpleGlow.copy(alpha = 0.9f),
                        PurpleGlow.copy(alpha = 0.4f),
                        Color.Transparent
                    ),
                    radius = size.minDimension * 0.55f
                ),
                radius = size.minDimension * 0.55f
            )
        }

        FilledIconButton(
            onClick = onClick,
            modifier = Modifier.size(72.dp),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = Color.White,
                contentColor = Color(0xFF1B0930)
            )
        ) {
            Icon(
                if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (playing) "Pause" else "Play",
                modifier = Modifier.size(32.dp)
            )
        }
    }
}

// DIALOGS
@Composable
private fun QueueDialog(
    playback: PlaybackView,
    controller: MediaController?,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Up Next") },
        text = {
            LazyColumn(Modifier.heightIn(max = 430.dp)) {
                if (playback.queue.isEmpty()) {
                    item { Text("Your queue is empty.") }
                }

                itemsIndexed(playback.queue) { index, item ->
                    Column(Modifier.padding(vertical = 8.dp)) {
                        Text(
                            text = buildString {
                                if (index == playback.index) append("▶ ")
                                append(item.mediaMetadata.title ?: "Untitled")
                            },
                            color = if (index == playback.index)
                                PurpleGlow else TextWhite,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (
                                        controller != null &&
                                        index < controller.mediaItemCount
                                    ) {
                                        controller.seekToDefaultPosition(index)
                                        controller.prepare()
                                        controller.play()
                                    }
                                }
                        )

                        Row {
                            IconButton(
                                enabled = index > 0,
                                onClick = {
                                    if (
                                        controller != null &&
                                        index < controller.mediaItemCount
                                    ) controller.moveMediaItem(index, index - 1)
                                }
                            ) {
                                Icon(
                                    Icons.Filled.KeyboardArrowUp,
                                    contentDescription = "Move up"
                                )
                            }

                            IconButton(
                                enabled = index < playback.queue.lastIndex,
                                onClick = {
                                    if (
                                        controller != null &&
                                        index + 1 < controller.mediaItemCount
                                    ) controller.moveMediaItem(index, index + 1)
                                }
                            ) {
                                Icon(
                                    Icons.Filled.KeyboardArrowDown,
                                    contentDescription = "Move down"
                                )
                            }

                            IconButton(
                                onClick = {
                                    if (
                                        controller != null &&
                                        index < controller.mediaItemCount
                                    ) controller.removeMediaItem(index)
                                }
                            ) {
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = "Remove"
                                )
                            }
                        }

                        HorizontalDivider(
                            color = Color.White.copy(alpha = 0.10f)
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        }
    )
}

@Composable
private fun UtilitiesDialog(
    settings: PlaybackSettings,
    connected: Boolean,
    onSpeed: (Float) -> Unit,
    onPitch: (Float) -> Unit,
    onSleep: (Int) -> Unit,
    onRefresh: () -> Unit,
    onPlaylists: () -> Unit,
    onLibraryTools: () -> Unit,
    onDismiss: () -> Unit
) {
    var speed by remember(settings.speed) { mutableStateOf(settings.speed) }
    var pitch by remember(settings.pitch) { mutableStateOf(settings.pitch) }
    var message by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Player utilities") },
        text = {
            LazyColumn(Modifier.heightIn(max = 460.dp)) {
                item {
                    Text("Speed · ${decimal(speed)}×")

                    Slider(
                        value = speed,
                        onValueChange = { speed = it },
                        onValueChangeFinished = { onSpeed(speed) },
                        valueRange = 0.5f..2f,
                        steps = 14
                    )

                    Text("Pitch · ${decimal(pitch)}×")

                    Slider(
                        value = pitch,
                        onValueChange = { pitch = it },
                        onValueChangeFinished = { onPitch(pitch) },
                        valueRange = 0.5f..2f,
                        steps = 14
                    )

                    DialogAction("Reset speed and pitch") {
                        speed = 1f
                        pitch = 1f
                        onSpeed(1f)
                        onPitch(1f)
                    }

                    HorizontalDivider()
                    Spacer(Modifier.height(12.dp))

                    Text("Sleep timer")
                    Text(
                        "Fades during its final five seconds.",
                        fontSize = 11.sp,
                        color = TextMuted
                    )

                    Row {
                        listOf(15, 30, 60).forEach { minutes ->
                            TextButton(
                                enabled = connected,
                                onClick = {
                                    onSleep(minutes)
                                    message = "$minutes-minute timer requested."
                                }
                            ) { Text("${minutes}m") }
                        }
                    }

                    TextButton(
                        enabled = connected,
                        onClick = {
                            onSleep(0)
                            message = "Timer cancellation requested."
                        }
                    ) { Text("Cancel timer") }

                    message?.let {
                        Text(it, fontSize = 11.sp, color = TextMuted)
                    }

                    HorizontalDivider()

                    DialogAction(
                        "Albums, artists, folders, recommendations & EQ",
                        onLibraryTools
                    )
                    DialogAction("Your playlists", onPlaylists)

                    DialogAction("Refresh local library") {
                        onRefresh()
                        message = "Library refresh requested."
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        }
    )
}

@Composable
private fun PlaylistsDialog(
    playlists: List<UserPlaylist>,
    addingSong: Track?,
    onDismiss: () -> Unit,
    onCreate: () -> Unit,
    onRename: (UserPlaylist) -> Unit,
    onDelete: (UserPlaylist) -> Unit,
    onOpen: (UserPlaylist) -> Unit,
    onAdd: (Track, UserPlaylist) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (addingSong != null) "Add to playlist"
                else "Your playlists"
            )
        },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                item {
                    DialogAction("+ Create playlist", onCreate)
                }

                if (playlists.isEmpty()) {
                    item {
                        Text(
                            "No playlists yet.",
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }

                items(playlists, key = { it.id }) { playlist ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            playlist.name,
                            modifier = Modifier
                                .weight(1f)
                                .clickable {
                                    if (addingSong != null)
                                        onAdd(addingSong, playlist)
                                    else onOpen(playlist)
                                }
                                .padding(vertical = 14.dp)
                        )

                        if (addingSong == null) {
                            IconButton(onClick = { onRename(playlist) }) {
                                Icon(
                                    Icons.Filled.Edit,
                                    contentDescription = "Rename"
                                )
                            }
                            IconButton(onClick = { onDelete(playlist) }) {
                                Icon(
                                    Icons.Filled.DeleteOutline,
                                    contentDescription = "Delete"
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

@Composable
private fun PlaylistDetailsDialog(
    playlist: UserPlaylist,
    songs: List<Track>,
    onDismiss: () -> Unit,
    onPlay: (List<Track>, Int) -> Unit,
    onRemove: (Track) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(playlist.name) },
        text = {
            LazyColumn(Modifier.heightIn(max = 430.dp)) {
                if (songs.isEmpty()) {
                    item { Text("This playlist is empty.") }
                }

                itemsIndexed(
                    songs,
                    key = { _, song -> song.id }
                ) { index, song ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            song.title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .weight(1f)
                                .clickable {
                                    onPlay(songs, index)
                                    onDismiss()
                                }
                                .padding(vertical = 14.dp)
                        )

                        IconButton(onClick = { onRemove(song) }) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = "Remove"
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = songs.isNotEmpty(),
                onClick = {
                    onPlay(songs, 0)
                    onDismiss()
                }
            ) { Text("Play all") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

@Composable
private fun NameDialog(
    title: String,
    initialName: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var name by remember(initialName) { mutableStateOf(initialName) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text("Playlist name") }
            )
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = { onSave(name.trim()) }
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun EditProfileDialog(
    profileName: String,
    username: String,
    onSave: (String, String) -> Unit,
    onDismiss: () -> Unit
) {
    var editedName by remember(profileName) { mutableStateOf(profileName) }
    var editedUsername by remember(username) { mutableStateOf(username) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit profile") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = editedName,
                    onValueChange = { editedName = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = editedUsername.removePrefix("@"),
                    onValueChange = { editedUsername = it.removePrefix("@") },
                    label = { Text("Username") },
                    prefix = { Text("@") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = editedName.isNotBlank() && editedUsername.isNotBlank(),
                onClick = { onSave(editedName.trim(), editedUsername.trim()) }
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun DialogAction(label: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(label, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun SettingsScreen(
    settings: PlaybackSettings,
    profileName: String,
    username: String,
    onBack: () -> Unit,
    onSpeed: (Float) -> Unit,
    onPitch: (Float) -> Unit,
    onGapless: (Boolean) -> Unit,
    onResumeOnLaunch: (Boolean) -> Unit,
    onDynamicBackdrop: (Boolean) -> Unit,
    onKeepScreenAwake: (Boolean) -> Unit,
    onLockScreenArt: (Boolean) -> Unit,
    onThemeMode: (String) -> Unit,
    onAccentHue: (String) -> Unit,
    onLaunchScreen: (String) -> Unit,
    onFilterShortAudio: (Int) -> Unit,
    onSleepTimer: (Int) -> Unit,
    onRefresh: () -> Unit,
    onPlaylists: () -> Unit,
    onLibraryTools: () -> Unit,
    onReset: () -> Unit
) {
    var showSpeedPitch by remember { mutableStateOf(false) }
    var showSleepTimer by remember { mutableStateOf(false) }
    var showFilter by remember { mutableStateOf(false) }
    var showReset by remember { mutableStateOf(false) }

    BackHandler(onBack = onBack)

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        BackgroundWash(settings.dynamicBackdrop)
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                GlassCircle(Icons.AutoMirrored.Filled.ArrowBack, "Back", onBack)
                Spacer(Modifier.width(12.dp))
                Text("Preferences", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Text("TuneHive", color = TextMuted, fontSize = 12.sp)
            }

            LazyColumn(
                contentPadding = PaddingValues(bottom = 36.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    SettingsProfileHeader(
                        profileName = profileName,
                        username = username
                    )
                }
                item {
                    SettingsSectionTitle("AUDIO & PLAYBACK", Icons.Filled.MusicNote)
                }
                item {
                    SettingsRow(
                        Icons.Filled.Speed,
                        "Speed & Micro-Pitch",
                        "${decimal(settings.speed)}x speed · ${decimal(settings.pitch)}x pitch",
                        onClick = { showSpeedPitch = true }
                    )
                }
                item {
                    SettingsToggleRow(
                        Icons.Filled.LinkOff,
                        "Gapless Playback",
                        "Zero cross-fade latency between tracks",
                        settings.gaplessPlayback,
                        onGapless
                    )
                }
                item {
                    SettingsToggleRow(
                        Icons.Filled.PlayArrow,
                        "Resume on App Launch",
                        "Remember the last playback position",
                        settings.resumeOnLaunch,
                        onResumeOnLaunch
                    )
                }
                item { SettingsSectionTitle("LIBRARY & STORAGE", Icons.Filled.LibraryMusic) }
                item {
                    SettingsRow(
                        Icons.Filled.Refresh,
                        "Manual Library Rescan",
                        "Refresh local audio files",
                        onClick = onRefresh,
                        action = "Scan"
                    )
                }
                item {
                    SettingsRow(
                        Icons.Filled.FilterAlt,
                        "Filter Short Audio",
                        "Exclude clips shorter than ${settings.filterShortAudioSeconds}s",
                        onClick = { showFilter = true },
                        action = "Change"
                    )
                }
                item {
                    SettingsRow(
                        Icons.AutoMirrored.Filled.PlaylistPlay,
                        "Your Playlists",
                        "Manage playlists and songs",
                        onClick = onPlaylists
                    )
                }
                item { SettingsSectionTitle("LOOK & FEEL", Icons.Filled.Palette) }
                item {
                    SettingsChoiceRow(
                        title = "Theme Mode",
                        options = listOf("DARK", "LIGHT", "AMOLED", "SYSTEM"),
                        selected = settings.themeMode,
                        onSelected = onThemeMode
                    )
                }
                item {
                    SettingsChoiceRow(
                        title = "Accent Hue",
                        options = listOf("PURPLE", "BLUE", "CYAN", "GREEN", "ORANGE", "RED", "PINK"),
                        selected = settings.accentHue,
                        onSelected = onAccentHue
                    )
                }
                item {
                    SettingsChoiceRow(
                        title = "Launch Screen",
                        options = listOf("HOME", "LIBRARY", "LAST_PLAYED"),
                        selected = settings.launchScreen,
                        onSelected = onLaunchScreen
                    )
                }
                item {
                    SettingsToggleRow(
                        Icons.Filled.AutoAwesome,
                        "Dynamic Ambient Backdrop",
                        "Use album art colors in the player background",
                        settings.dynamicBackdrop,
                        onDynamicBackdrop
                    )
                }
                item { SettingsSectionTitle("HEADSET & HARDWARE", Icons.Filled.Headphones) }
                item {
                    SettingsToggleRow(
                        Icons.Filled.ScreenLockPortrait,
                        "Keep Screen Awake",
                        "Prevent sleep while Now Playing is open",
                        settings.keepScreenAwake,
                        onKeepScreenAwake
                    )
                }
                item {
                    SettingsToggleRow(
                        Icons.Filled.Lock,
                        "Lock Screen Art",
                        "Show artwork in media controls",
                        settings.lockScreenArt,
                        onLockScreenArt
                    )
                }
                item { SettingsSectionTitle("SYSTEM & UTILITIES", Icons.Filled.Settings) }
                item {
                    SettingsRow(
                        Icons.Filled.Bedtime,
                        "Sleep Timer",
                        if (settings.sleepTimerMinutes == 0) "Off"
                        else "Stops after ${settings.sleepTimerMinutes} minutes",
                        onClick = { showSleepTimer = true },
                        action = "Set"
                    )
                }
                item {
                    SettingsRow(
                        Icons.Filled.Tune,
                        "Library Tools & Equalizer",
                        "Albums, folders, recommendations and EQ",
                        onClick = onLibraryTools
                    )
                }
                item { SettingsSectionTitle("BACKUP & DATA", Icons.Filled.CloudUpload) }
                item {
                    SettingsRow(
                        Icons.Filled.RestartAlt,
                        "Reset All Settings",
                        "Restore playback preferences to defaults",
                        onClick = { showReset = true },
                        tint = Pink,
                        action = "Reset"
                    )
                }
                item {
                    Text(
                        "TuneHive · offline music player",
                        color = TextFaint,
                        fontSize = 11.sp,
                        modifier = Modifier.fillMaxWidth().padding(20.dp),
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }

    if (showSpeedPitch) {
        SpeedPitchSettingsDialog(
            speed = settings.speed,
            pitch = settings.pitch,
            onSpeed = onSpeed,
            onPitch = onPitch,
            onDismiss = { showSpeedPitch = false }
        )
    }
    if (showSleepTimer) {
        SleepTimerSettingsDialog(
            selected = settings.sleepTimerMinutes,
            onSelected = onSleepTimer,
            onDismiss = { showSleepTimer = false }
        )
    }
    if (showFilter) {
        FilterSettingsDialog(
            selected = settings.filterShortAudioSeconds,
            onSelected = onFilterShortAudio,
            onDismiss = { showFilter = false }
        )
    }
    if (showReset) {
        AlertDialog(
            onDismissRequest = { showReset = false },
            title = { Text("Reset settings?") },
            text = { Text("Playback and appearance preferences will return to their defaults.") },
            confirmButton = {
                TextButton(onClick = { onReset(); showReset = false }) { Text("Reset") }
            },
            dismissButton = {
                TextButton(onClick = { showReset = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun SettingsProfileHeader(profileName: String, username: String) {
    Row(
        Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(56.dp).clip(RoundedCornerShape(16.dp))
                .background(Brush.linearGradient(listOf(PurpleGlow, Pink))),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.Person, "Profile", tint = Color.White, modifier = Modifier.size(30.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(profileName, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Text("VIP Pro Listener", color = TextMuted, fontSize = 12.sp)
            Text("@$username", color = TextFaint, fontSize = 11.sp)
        }
    }
}

@Composable
private fun SettingsSectionTitle(title: String, icon: ImageVector) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(title, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SettingsRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    action: String? = null,
    tint: Color? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    val iconTint = tint ?: MaterialTheme.colorScheme.primary

    Row(
        Modifier.padding(horizontal = 20.dp).fillMaxWidth()
            .clip(RoundedCornerShape(18.dp)).background(GlassSurface)
            .border(1.dp, GlassBorder, RoundedCornerShape(18.dp))
            .clickable(onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(iconTint.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = iconTint, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        when {
            trailing != null -> trailing()
            action != null -> Text(action, color = iconTint, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            else -> Icon(Icons.Filled.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun SettingsToggleRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    SettingsRow(
        icon = icon,
        title = title,
        subtitle = subtitle,
        onClick = { onCheckedChange(!checked) },
        trailing = {
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    )
}

@Composable
private fun SettingsChoiceRow(
    title: String,
    options: List<String>,
    selected: String,
    onSelected: (String) -> Unit
) {
    Column(Modifier.padding(horizontal = 20.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(GlassSurface).padding(12.dp)) {
        Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(options) { option ->
                val active = option == selected
                Text(
                    option.lowercase().replaceFirstChar { it.uppercase() },
                    color = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                    modifier = Modifier.clip(RoundedCornerShape(20.dp))
                        .background(if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { onSelected(option) }.padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun SpeedPitchSettingsDialog(
    speed: Float,
    pitch: Float,
    onSpeed: (Float) -> Unit,
    onPitch: (Float) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Speed & Micro-Pitch") },
        text = {
            Column {
                Text("Speed · ${decimal(speed)}x", color = TextMuted)
                Slider(speed, onValueChange = onSpeed, valueRange = 0.5f..2f)
                Text("Pitch · ${decimal(pitch)}x", color = TextMuted)
                Slider(pitch, onValueChange = onPitch, valueRange = 0.5f..2f)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } }
    )
}

@Composable
private fun SleepTimerSettingsDialog(selected: Int, onSelected: (Int) -> Unit, onDismiss: () -> Unit) {
    val options = listOf(0, 15, 30, 45, 60, 90, 120)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sleep Timer") },
        text = {
            Column { options.forEach { minutes ->
                Row(Modifier.fillMaxWidth().clickable { onSelected(minutes) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected == minutes, onClick = { onSelected(minutes) })
                    Text(if (minutes == 0) "Off" else "$minutes minutes")
                }
            } }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } }
    )
}

@Composable
private fun FilterSettingsDialog(selected: Int, onSelected: (Int) -> Unit, onDismiss: () -> Unit) {
    val options = listOf(10, 15, 20, 30, 45, 60)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Filter Short Audio") },
        text = {
            Column { options.forEach { seconds ->
                Row(Modifier.fillMaxWidth().clickable { onSelected(seconds) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected == seconds, onClick = { onSelected(seconds) })
                    Text("$seconds seconds")
                }
            } }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } }
    )
}

// PERMISSION SCREEN
@Composable
private fun PermissionScreen(onGrant: () -> Unit) {
    val context = LocalContext.current
    val assets = remember(context) { ReferenceAssets(context) }

    CompositionLocalProvider(LocalAssets provides assets) {
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(PurpleMid, PurpleDeep, Ink)
                    )
                )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
                    .padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    Icons.Filled.Headphones,
                    contentDescription = null,
                    tint = PurpleGlow,
                    modifier = Modifier.size(72.dp)
                )

                Spacer(Modifier.height(20.dp))

                Text(
                    "Welcome to TuneHive",
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold
                )

                Spacer(Modifier.height(10.dp))

                Text(
                    "Allow access to the audio files on your device. " +
                        "Your music stays offline.",
                    color = TextMuted,
                    textAlign = TextAlign.Center,
                    fontSize = 13.sp
                )

                Spacer(Modifier.height(24.dp))

                Button(
                    onClick = onGrant,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PurpleGlow,
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp)
                ) { Text("Allow music access") }

                Spacer(Modifier.height(16.dp))

                Text(
                    "If permission was permanently denied, enable audio " +
                        "access in Android Settings → Apps → TuneHive.",
                    color = TextFaint,
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

// FORMATTING
private fun formatTime(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0L) / 1000L
    val hours = seconds / 3600L
    val minutes = seconds % 3600L / 60L
    val remaining = seconds % 60L

    return if (hours > 0L) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, remaining)
    } else {
        String.format(Locale.US, "%d:%02d", minutes, remaining)
    }
}

private fun decimal(value: Float): String =
    String.format(Locale.US, "%.1f", value)
