package dev.spiegl.flyingcarpet

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which queued sends may start, given what is running — the rule TransferLanes.pump() applies.
 * The desktop keeps the same rule in core/src/lanes.rs.
 */
class LaneRulesUnitTest {

    private val max = TransferLanes.MAX_LANES

    @Test
    fun anIdleDeviceStartsAtOnce() {
        assertEquals(listOf(0), lanesToStart(emptyList(), listOf("a"), max))
    }

    /** The case the feature exists for: a long send to one device does not hold up another. */
    @Test
    fun aDifferentDeviceDoesNotWait() {
        assertEquals(listOf(0), lanesToStart(listOf("desktop"), listOf("phone"), max))
    }

    @Test
    fun theSameDeviceWaitsItsTurn() {
        assertEquals(emptyList<Int>(), lanesToStart(listOf("a"), listOf("a"), max))
    }

    /** A lane receiving from a device counts as that device being busy, too. */
    @Test
    fun twoQueuedForOneDeviceStartOneAtATime() {
        assertEquals(listOf(0), lanesToStart(emptyList(), listOf("a", "a"), max))
    }

    /** A send stuck behind its own device must not hold up a later one to another device. */
    @Test
    fun aWaitingSendDoesNotBlockTheQueue() {
        assertEquals(listOf(1), lanesToStart(listOf("a"), listOf("a", "b"), max))
    }

    @Test
    fun noMoreThanTheCapRunAtOnce() {
        assertEquals(4, max)
        assertEquals(emptyList<Int>(), lanesToStart(listOf("a", "b", "c", "d"), listOf("e"), max))
        assertEquals(listOf(0), lanesToStart(listOf("a", "b", "c"), listOf("e", "f"), max))
    }

    /** An incoming lane whose caller is not yet identified still takes a place. */
    @Test
    fun anUnidentifiedLaneCountsTowardsTheCap() {
        assertEquals(emptyList<Int>(), lanesToStart(listOf(null, "b", "c", "d"), listOf("e"), max))
        assertEquals(listOf(0), lanesToStart(listOf(null), listOf("e"), max))
    }
}
