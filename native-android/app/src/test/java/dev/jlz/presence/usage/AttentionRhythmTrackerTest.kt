package dev.jlz.presence.usage

import org.junit.Assert.assertEquals
import org.junit.Test

class AttentionRhythmTrackerTest {
    @Test fun classifiesStructuredScrollRateWithoutPixels() {
        val tracker = AttentionRhythmAccumulator()
        val start = 1_000_000L
        repeat(8) { index -> tracker.observe("scroll", "feed.app", start + index * 4_000L) }
        val fast = tracker.snapshot(start + 32_000L)
        assertEquals("FAST_SCROLL", fast.classification)
        assertEquals(8, fast.scrollsPerMinute)

        repeat(12) { index -> tracker.observe("scroll", "feed.app", start + 33_000L + index * 1_000L) }
        val rapid = tracker.snapshot(start + 46_000L)
        assertEquals("RAPID_SCROLL", rapid.classification)
        assertEquals(false, rapid.toJson().getBoolean("contains_screen_text"))
    }

    @Test fun detectsFragmentedWindowSwitchingAndPrunesOldSamples() {
        val tracker = AttentionRhythmAccumulator()
        val start = 2_000_000L
        repeat(6) { index ->
            tracker.observe("window", "app.${index % 3}", start + index * 5_000L)
        }
        assertEquals("ATTENTION_FRAGMENTED", tracker.snapshot(start + 31_000L).classification)
        assertEquals("NORMAL", tracker.snapshot(start + 6 * 60_000L).classification)
    }
}
