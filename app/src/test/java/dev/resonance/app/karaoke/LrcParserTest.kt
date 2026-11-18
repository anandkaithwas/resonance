package dev.resonance.app.karaoke

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LrcParserTest {
    @Test fun parsesCentisecondsAndSortsTimestamps() {
        val parsed = LrcParser.parse("[00:02.50]Second\n[00:01.25]First")
        assertEquals(listOf(1_250L, 2_500L), parsed.lines.map { it.timeMs })
        assertEquals("First", parsed.lines.first().text)
    }
    @Test fun supportsMultipleTimestampsOnOneLine() {
        val parsed = LrcParser.parse("[00:01.00][00:03.00]Repeated")
        assertEquals(listOf(1_000L, 3_000L), parsed.lines.map { it.timeMs })
    }
    @Test fun parsesOffsetAndMillisecondPrecision() {
        val parsed = LrcParser.parse("[offset:-150]\n[01:02.345]Line")
        assertEquals(-150L, parsed.offsetMs)
        assertEquals(62_345L, parsed.lines.single().timeMs)
        assertEquals(0, LrcParser.activeLineIndex(parsed.lines, 62_194L, parsed.offsetMs))
    }
    @Test fun ignoresMalformedAndUntimedLines() {
        val parsed = LrcParser.parse("plain text\n[00:77.10]bad\n[00:02.00]valid")
        assertEquals(1, parsed.lines.size)
        assertTrue(parsed.lines.single().text == "valid")
    }
    @Test fun findsNoActiveLineBeforeFirstTimestamp() {
        val lines = LrcParser.parse("[00:05.00]later").lines
        assertEquals(-1, LrcParser.activeLineIndex(lines, 1_000L))
    }
}
