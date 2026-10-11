package com.meta.inklab

import org.junit.Assert.*
import org.junit.Test

class ChildStreamHandoffTest {
    @Test fun secondFingerTakeoverCancelsChildThatReceivedFirstFingerOnce() {
        val handoff = ChildStreamHandoff()
        assertFalse(handoff.observe(startsSequence = true, endsSequence = false, claimedByHost = false))
        assertFalse(handoff.observe(startsSequence = false, endsSequence = false, claimedByHost = false))
        assertTrue(handoff.observe(startsSequence = false, endsSequence = false, claimedByHost = true))
        assertFalse(handoff.observe(startsSequence = false, endsSequence = false, claimedByHost = true))
        assertFalse(handoff.observe(startsSequence = false, endsSequence = true, claimedByHost = true))
    }

    @Test fun hostOwnedFromDownNeverCancelsChildren() {
        val handoff = ChildStreamHandoff()
        assertFalse(handoff.observe(startsSequence = true, endsSequence = false, claimedByHost = true))
        assertFalse(handoff.observe(startsSequence = false, endsSequence = true, claimedByHost = true))
    }

    @Test fun completedChildSequenceDoesNotLeakIntoNextHostGesture() {
        val handoff = ChildStreamHandoff()
        handoff.observe(startsSequence = true, endsSequence = false, claimedByHost = false)
        handoff.observe(startsSequence = false, endsSequence = true, claimedByHost = false)
        assertFalse(handoff.observe(startsSequence = true, endsSequence = false, claimedByHost = true))
    }
}
