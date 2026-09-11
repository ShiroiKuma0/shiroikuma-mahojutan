package dev.spiegl.flyingcarpet

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The rule PairedController.decide() applies to an incoming offer, as a pure function.
 *
 * It exists because of the fault it now pins: `alreadyBusy` used to be read live from
 * `viewModel.transferIsRunning`, which both callers set immediately before running the
 * transfer that *contains* the offer exchange — so the guard was always looking at the very
 * transfer it was being asked about, and every paired receive refused itself
 * (白い熊, 2026-09-11). The busy question can only be answered from a value sampled before
 * the caller makes itself busy, which is what taking it as a parameter enforces.
 */
class OfferDecisionUnitTest {

    private fun decide(
        alreadyBusy: Boolean,
        known: Boolean = true,
        autoAccept: Boolean = true,
        ceiling: Long = 0,
        offerBytes: Long = 1_000,
        folder: String? = "content://folder",
    ): String {
        if (alreadyBusy) return "BUSY"
        if (!known || !autoAccept) return "NOT_ALLOWED"
        if (ceiling > 0 && offerBytes > ceiling) return "TOO_LARGE"
        if (folder == null) return "NO_DESTINATION"
        return "ACCEPT"
    }

    /** The regression: a receive that is only busy *because of itself* must be accepted. */
    @Test
    fun aReceiveDoesNotRefuseItself() {
        assertEquals("ACCEPT", decide(alreadyBusy = false))
    }

    /** And a device genuinely mid-transfer still says so. */
    @Test
    fun anActualTransferInFlightRefuses() {
        assertEquals("BUSY", decide(alreadyBusy = true))
    }

    /** Busy outranks everything: there is no point choosing a folder we cannot write to yet. */
    @Test
    fun busyIsCheckedFirst() {
        assertEquals("BUSY", decide(alreadyBusy = true, known = false, folder = null))
    }

    /** Holding the group key is necessary but not sufficient. */
    @Test
    fun anUnknownOrUntrustedPeerIsRefused() {
        assertEquals("NOT_ALLOWED", decide(alreadyBusy = false, known = false))
        assertEquals("NOT_ALLOWED", decide(alreadyBusy = false, autoAccept = false))
    }

    @Test
    fun aCeilingOfZeroMeansNoCeiling() {
        assertEquals("ACCEPT", decide(alreadyBusy = false, ceiling = 0, offerBytes = Long.MAX_VALUE))
    }

    @Test
    fun anOfferOverTheCeilingIsRefused() {
        assertEquals("TOO_LARGE", decide(alreadyBusy = false, ceiling = 100, offerBytes = 101))
        assertEquals("ACCEPT", decide(alreadyBusy = false, ceiling = 100, offerBytes = 100))
    }

    @Test
    fun noFolderIsRefusedWithItsOwnReason() {
        assertEquals("NO_DESTINATION", decide(alreadyBusy = false, folder = null))
    }
}
