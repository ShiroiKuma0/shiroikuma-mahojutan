package dev.spiegl.flyingcarpet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// The speed check's arithmetic (LinkDoctor.kt), against what was measured on 2026-11-21.
class SpeedCheckUnitTest {

    private fun within(range: SpeedRange, vararg measured: Double) {
        for (m in measured) {
            assertTrue("$m outside $range", m >= range.low * 0.95 && m <= range.high * 1.05)
        }
    }

    @Test
    fun theEstimateHoldsTheFourMeasurements() {
        within(linkAllows(1225.0, 172.0)!!, 13.4, 14.4)   // phone → PC on the 20 MHz downlink
        within(linkAllows(1225.0, 576.0)!!, 28.5)          // after the reconnect, weaker moment
        within(linkAllows(1225.0, 865.0)!!, 31.3)          // and a stronger one
        within(linkAllows(1153.0, 1921.0)!!, 46.0, 51.4)  // PC → phone
    }

    @Test
    fun anUnknownHopGivesNoEstimate() {
        assertNull(linkAllows(0.0, 865.0))
    }

    @Test
    fun thatAfternoonReadsAsTheLinkBeingTheBrake() {
        val verdict = speedVerdict(13.4, linkAllows(1225.0, 172.0), 31.7, "sktx")
        assertEquals("The Wi-Fi link is the brake. Reconnecting sktx’s Wi-Fi may help.", verdict)
    }

    @Test
    fun afterTheReconnectItIsAsFastAsEver() {
        assertEquals(
            "This is as fast as these two have gone.",
            speedVerdict(31.0, linkAllows(1225.0, 700.0), 31.7, "sktx"),
        )
    }

    @Test
    fun aTransferFarBelowItsLinkPointsElsewhere() {
        assertTrue(speedVerdict(5.0, linkAllows(1225.0, 865.0), 31.7, "sktx").startsWith("The link could carry more."))
    }

    @Test
    fun aTransferOutrunningItsReadingIsNotJudgedByIt() {
        assertTrue(speedVerdict(35.0, linkAllows(1225.0, 172.0), 36.0, "sktx").contains("the reading is stale"))
    }

    @Test
    fun withNothingToCompareItSaysSo() {
        assertEquals("No earlier transfer to compare with.", speedVerdict(20.0, linkAllows(1225.0, 700.0), null, "sktx"))
    }

    @Test
    fun anIdleLinkIsJudgedAgainstTheBestToo() {
        assertTrue(speedVerdict(null, linkAllows(1225.0, 172.0), 31.7, "sktx").startsWith("The Wi-Fi link is slower"))
        assertEquals("The link is about as good as it has been.", speedVerdict(null, linkAllows(1225.0, 865.0), 31.7, "sktx"))
    }
}
