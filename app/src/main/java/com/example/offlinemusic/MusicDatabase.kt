package com.example.offlinemusic

import androidx.compose.runtime.Immutable
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Immutable
@Entity(tableName = "track_state")
data class TrackState(
    @PrimaryKey val mediaId: String,
    val favorite: Boolean = false,
    val playCount: Long = 0,
    val lastPlayed: Long = 0,
)

@Immutable
@Entity(tableName = "playlists")
data class UserPlaylist(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String
)

@Immutable
@Entity(
    tableName = "playlist_entries",
    primaryKeys = ["playlistId", "mediaId"],
    foreignKeys = [
        ForeignKey(
            entity = UserPlaylist::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("playlistId")]
)
data class PlaylistEntry(
    val playlistId: Long,
    val mediaId: String,
    val addedAt: Long = System.currentTimeMillis()
)

@Dao
abstract class MusicDao {
    @Query("SELECT * FROM track_state")
    abstract fun observeTrackStates(): Flow<List<TrackState>>

    @Query("SELECT * FROM playlists ORDER BY name COLLATE NOCASE")
    abstract fun observePlaylists(): Flow<List<UserPlaylist>>

    @Query(
        """
        SELECT * FROM playlist_entries
        ORDER BY addedAt, mediaId
        """
    )
    abstract fun observePlaylistEntries(): Flow<List<PlaylistEntry>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun ensureTrackState(state: TrackState)

    @Query(
        """
        UPDATE track_state
        SET favorite = CASE WHEN favorite = 1 THEN 0 ELSE 1 END
        WHERE mediaId = :mediaId
        """
    )
    abstract suspend fun flipFavorite(mediaId: String)

    @Transaction
    open suspend fun toggleFavorite(mediaId: String) {
        ensureTrackState(TrackState(mediaId))
        flipFavorite(mediaId)
    }

    @Query(
        """
        UPDATE track_state
        SET playCount = playCount + 1, lastPlayed = :timestamp
        WHERE mediaId = :mediaId
        """
    )
    abstract suspend fun incrementPlayCount(
        mediaId: String,
        timestamp: Long
    )

    @Transaction
    open suspend fun recordPlay(mediaId: String) {
        ensureTrackState(TrackState(mediaId))
        incrementPlayCount(mediaId, System.currentTimeMillis())
    }

    @Insert
    abstract suspend fun insertPlaylist(playlist: UserPlaylist): Long

    @Query("UPDATE playlists SET name = :name WHERE id = :playlistId")
    abstract suspend fun renamePlaylist(playlistId: Long, name: String)

    @Query("DELETE FROM playlists WHERE id = :playlistId")
    abstract suspend fun deletePlaylist(playlistId: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun addPlaylistEntry(entry: PlaylistEntry)

    @Query(
        """
        DELETE FROM playlist_entries
        WHERE playlistId = :playlistId AND mediaId = :mediaId
        """
    )
    abstract suspend fun removePlaylistEntry(
        playlistId: Long,
        mediaId: String
    )
}

@Database(
    entities = [
        TrackState::class,
        UserPlaylist::class,
        PlaylistEntry::class
    ],
    version = 1,
    exportSchema = false
)
abstract class MusicDatabase : RoomDatabase() {
    abstract fun musicDao(): MusicDao
}
