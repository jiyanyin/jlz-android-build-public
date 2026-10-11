package dev.jlz.presence.overlay

import org.junit.Assert.*
import org.junit.Test

class QAvatarPriorityTest {
    @Test fun exhaustedSmallPoolNeverImmediatelyRepeatsAfterCooldown() {
        var now = 100_000L
        val q = QAvatarStateMachine { now }
        val pool = listOf("a", "b")
        var previous = q.pickAsset(pool)
        repeat(20) {
            now += 120_000L
            val next = q.pickAsset(pool)
            assertNotEquals(previous, next)
            previous = next
        }
    }
    @Test fun userModesAndSafetyWinOverRandomOrEntertainment() {
        val q = QAvatarStateMachine()
        assertEquals(QAvatarState.HIDDEN, q.resolve(true, true, true, true, true, true, true, true))
        assertEquals(QAvatarState.SLEEP, q.resolve(false, true, true, true, true, true, true, true))
        assertEquals(QAvatarState.WATCHING, q.resolve(false, false, true, true, true, true, true, true))
        assertEquals(QAvatarState.STUDY, q.resolve(false, false, false, true, true, true, true, true))
        assertEquals(QAvatarState.ANNOYED, q.resolve(false, false, false, false, true, true, true, true))
        assertEquals(QAvatarState.OFFLINE, q.resolve(false, false, false, false, false, false, false, true))
    }
    @Test fun cooldownKeepsSamePickWithinBurst() {
        val q = QAvatarStateMachine(); val pool = listOf("a", "b", "c")
        val first = q.pickAsset(pool)
        repeat(10) { assertEquals(first, q.pickAsset(pool)) }
    }
}
