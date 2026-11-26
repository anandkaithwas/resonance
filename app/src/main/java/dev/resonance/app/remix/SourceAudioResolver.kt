package dev.resonance.app.remix

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** Copies a user-selected source to a temporary private file for an explicit upload. */
object SourceAudioResolver {
    const val MAX_SOURCE_BYTES = 100L * 1024L * 1024L

    suspend fun copyToTemp(context: Context, uri: Uri): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(context.cacheDir, "remix_sources").apply { mkdirs() }
            val file = File(dir, "source_${UUID.randomUUID()}.bin")
            val temp = File(dir, "${file.name}.part")
            try {
                context.contentResolver.openInputStream(uri).use { input ->
                    require(input != null) { "Could not read the selected audio file" }
                    temp.outputStream().buffered().use { output ->
                        val buffer = ByteArray(16 * 1024)
                        var total = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            total += read
                            require(total <= MAX_SOURCE_BYTES) { "Source audio exceeds the 100 MB upload limit" }
                            output.write(buffer, 0, read)
                        }
                        require(total > 0L) { "The selected audio file is empty" }
                    }
                }
                require(temp.renameTo(file)) { "Could not prepare the source audio" }
                file
            } finally {
                if (temp.isFile) temp.delete()
            }
        }
    }

    suspend fun durationMs(context: Context, uri: Uri): Long = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (_: Exception) {
            0L
        } finally {
            runCatching { retriever.release() }
        }
    }
}
