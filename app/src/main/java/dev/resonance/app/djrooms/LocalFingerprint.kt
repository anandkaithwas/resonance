package dev.resonance.app.djrooms

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest

/** A short local-only fingerprint used to match the same audio file across devices. */
object LocalFingerprint {
    private const val SAMPLE_BYTES = 1024 * 1024

    suspend fun of(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
        val digest = MessageDigest.getInstance("SHA-256")
        context.contentResolver.openInputStream(uri).use { input ->
            require(input != null) { "Could not read the selected audio file" }
            val buffer = ByteArray(16 * 1024)
            var total = 0
            while (total < SAMPLE_BYTES) {
                val read = input.read(buffer, 0, minOf(buffer.size, SAMPLE_BYTES - total))
                if (read < 0) break
                digest.update(buffer, 0, read)
                total += read
            }
            digest.update(byteArrayOf((total and 0xFF).toByte(), ((total shr 8) and 0xFF).toByte()))
        }
        digest.digest().joinToString("") { "%02x".format(it) }.take(32)
    }
}
