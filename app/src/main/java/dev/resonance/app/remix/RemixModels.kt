package dev.resonance.app.remix

/** A quick on-device effect is never a generative remix. */
data class InstantEffectPreset(
    val id: String,
    val label: String,
    val speed: Float,
    val pitch: Float,
    val description: String
)

object InstantEffects {
    val presets = listOf(
        InstantEffectPreset("normal", "Original", 1.0f, 1.0f, "Playback at the source speed and pitch."),
        InstantEffectPreset("slowed", "Slowed", 0.75f, 1.0f, "Slower playback with pitch locked where supported."),
        InstantEffectPreset("nightcore", "Nightcore-style", 1.25f, 1.0f, "Faster playback with pitch locked where supported."),
        InstantEffectPreset("chipmunk", "Higher pitch", 1.0f, 1.18f, "Pitch shift only; no source audio is uploaded.")
    )
}

data class RemixPreset(
    val id: String,
    val label: String,
    val stylePrompt: String
)

object RemixPresets {
    val presets = listOf(
        RemixPreset("slowed_reverb", "Slow / slowed + reverb", "Slowed-down version with spacious reverb"),
        RemixPreset("lofi", "Lo-fi", "Lo-fi remix with warm, dusty drums"),
        RemixPreset("remix", "Remix", "Fresh electronic remix with a new groove"),
        RemixPreset("acoustic", "Acoustic", "Acoustic arrangement with organic instruments"),
        RemixPreset("edm", "EDM", "Festival EDM arrangement"),
        RemixPreset("chill", "Chill / ambient", "Relaxed ambient reimagining"),
        RemixPreset("nightcore", "Nightcore", "Fast, bright nightcore arrangement"),
        RemixPreset("instrumental", "Instrumental", "Instrumental version without lead vocals"),
        RemixPreset("karaoke", "Karaoke instrumental", "Karaoke-ready backing track"),
        RemixPreset("custom", "Custom prompt", "")
    )
}

enum class RemixStatus(val value: String) {
    QUEUED("queued"),
    RUNNING("running"),
    SUCCEEDED("succeeded"),
    FAILED("failed"),
    CANCELLED("cancelled");

    companion object {
        fun from(value: String?): RemixStatus = entries.firstOrNull { it.value.equals(value, true) } ?: FAILED
    }
}

data class RemixJob(
    val id: String = "",
    val sourceTrackId: String = "",
    val sourceTitle: String = "",
    val presetId: String = "",
    val prompt: String = "",
    val status: RemixStatus = RemixStatus.QUEUED,
    val progress: Int = 0,
    val provider: String = "",
    val resultFileName: String = "",
    val error: String = "",
    val createdAt: Long = System.currentTimeMillis()
)
