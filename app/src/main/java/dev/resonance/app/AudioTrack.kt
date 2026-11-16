package dev.resonance.app

import android.net.Uri

data class AudioTrack(
    val id: String,
    val title: String,
    val artist: String,
    val uri: Uri,
    val online: Boolean
)
