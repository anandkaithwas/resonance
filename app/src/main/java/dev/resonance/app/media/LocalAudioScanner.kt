package dev.resonance.app.media

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class ResonanceTrack(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val uri: Uri,
    val isAiGenerated: Boolean = false
) {
    fun toMediaItem(): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .setAlbumTitle(album)
            .build()
        return MediaItem.Builder()
            .setMediaId(id)
            .setUri(uri)
            .setMediaMetadata(metadata)
            .build()
    }
}

object LocalAudioScanner {
    suspend fun scanAllTracks(context: Context): List<ResonanceTrack> = withContext(Dispatchers.IO) {
        val results = mutableListOf<ResonanceTrack>()
        val studioDir = File(context.filesDir, "ai_tracks").apply { mkdirs() }
        studioDir.listFiles { file -> file.extension.lowercase() in listOf("wav", "mp3", "ogg") }
            ?.sortedByDescending { it.lastModified() }
            ?.forEach { file ->
                results.add(
                    ResonanceTrack(
                        id = "ai_${file.name}",
                        title = file.nameWithoutExtension,
                        artist = "RESONANCE Studio",
                        album = "AI Creations",
                        uri = file.toUri(),
                        isAiGenerated = true
                    )
                )
            }
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM
        )
        runCatching {
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                "${MediaStore.Audio.Media.IS_MUSIC} != 0",
                null,
                "${MediaStore.Audio.Media.TITLE} ASC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    results.add(
                        ResonanceTrack(
                            id = id.toString(),
                            title = cursor.getString(titleCol) ?: "Unknown Track",
                            artist = cursor.getString(artistCol) ?: "Unknown Artist",
                            album = cursor.getString(albumCol) ?: "Unknown Album",
                            uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                        )
                    )
                }
            }
        }
        results
    }
}
