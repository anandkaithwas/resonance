package dev.resonance.app.karaoke

data class LyricLine(val timeMs: Long, val text: String)
data class ParsedLyrics(val lines: List<LyricLine>, val offsetMs: Long = 0L)

object LrcParser {
    private val timestamp = Regex("""\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?]""")
    private val offset = Regex("""\[offset:([+-]?\d+)]""", RegexOption.IGNORE_CASE)

    fun parse(source: String): ParsedLyrics {
        var offsetMs = 0L
        val lines = mutableListOf<LyricLine>()
        source.lineSequence().forEach lineLoop@ { raw ->
            offset.find(raw)?.groupValues?.getOrNull(1)?.toLongOrNull()?.let { offsetMs = it }
            val matches = timestamp.findAll(raw).toList()
            if (matches.isEmpty()) return@lineLoop
            val lyric = timestamp.replace(raw, "").trim()
            if (lyric.isEmpty()) return@lineLoop
            matches.forEach matchLoop@ { match ->
                val minutes = match.groupValues[1].toLongOrNull() ?: return@matchLoop
                val seconds = match.groupValues[2].toLongOrNull() ?: return@matchLoop
                val fraction = match.groupValues[3]
                val fractionMs = when (fraction.length) {
                    1 -> (fraction.toLongOrNull() ?: 0L) * 100L
                    2 -> (fraction.toLongOrNull() ?: 0L) * 10L
                    3 -> fraction.toLongOrNull() ?: 0L
                    else -> 0L
                }
                if (seconds < 60) lines += LyricLine(minutes * 60_000L + seconds * 1_000L + fractionMs, lyric)
            }
        }
        return ParsedLyrics(lines.sortedBy { it.timeMs }, offsetMs)
    }

    fun activeLineIndex(lines: List<LyricLine>, positionMs: Long, offsetMs: Long = 0L): Int =
        lines.indexOfLast { it.timeMs <= positionMs - offsetMs }
}
