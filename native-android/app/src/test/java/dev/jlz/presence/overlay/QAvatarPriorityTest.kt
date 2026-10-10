package dev.jlz.presence.overlay

import org.junit.Assert.*
import org.junit.Test

class QAvatarPriorityTest {
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
