package dev.spiegl.flyingcarpet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// The narrow-channel verdict (LinkDoctor.kt), against the readings taken on sktx on 2026-11-21.
class LinkStateUnitTest {

    // What the PC reported while the access point was feeding it on 20 MHz.
    private val narrow = "wifi=1\niface=wlp132s0f0\nchannel_mhz=160\nrx_mbps=172.0\nrx_mhz=20\n" +
        "tx_mbps=1152.8\ntx_mhz=160\nsignal=-70\n"

    // And after its Wi-Fi was reconnected.
    private val wide = "wifi=1\niface=wlp132s0f0\nchannel_mhz=160\nrx_mbps=864.6\nrx_mhz=160\n" +
        "tx_mbps=1152.8\ntx_mhz=160\nsignal=-67\n"

    @Test
    fun theReadingOfThatAfternoonIsNarrowForAPhoneThatSends() {
        val state = LinkState.parse(narrow)!!
        assertEquals(LinkRate(172.0, 20), state.rx)
        assertEquals(160, state.channelMhz)
        assertTrue(state.isNarrowFor(phoneSending = true))
        // The PC's own sending was on the full width all along.
        assertFalse(state.isNarrowFor(phoneSending = false))
    }

    @Test
    fun theReconnectedLinkIsNot() {
        val state = LinkState.parse(wide)!!
        assertFalse(state.isNarrowFor(phoneSending = true))
        assertFalse(state.isNarrowFor(phoneSending = false))
    }

    @Test
    fun halfTheWidthIsLeftAlone() {
        val state = LinkState.parse(wide.replace("rx_mhz=160", "rx_mhz=80"))!!
        assertFalse(state.isNarrowFor(phoneSending = true))
        assertTrue(LinkState.parse(wide.replace("rx_mhz=160", "rx_mhz=40"))!!.isNarrowFor(true))
    }

    @Test
    fun aNarrowNetworkIsNotANarrowHop() {
        // A 20 MHz network has nothing wider to fall back from.
        val state = LinkState.parse(narrow.replace("channel_mhz=160", "channel_mhz=20"))!!
        assertFalse(state.isNarrowFor(phoneSending = true))
    }

    @Test
    fun aWiredPeerOrAnUnreadableAnswerRaisesNothing() {
        assertFalse(LinkState.parse("wifi=0\niface=enp131s0\n")!!.isNarrowFor(true))
        assertNull(LinkState.parse("ok=0\nerror=nope\n"))
    }

    @Test
    fun theSentenceNamesTheDeviceTheWidthAndTheRate() {
        assertEquals(
            "sktx is receiving from the access point on a 20 MHz channel at 172 Mbit/s, " +
                "while the network runs on 160 MHz.",
            LinkState.parse(narrow)!!.sentence("sktx", phoneSending = true),
        )
    }

    @Test
    fun aReplyIsDoneRefusedOrAbsent() {
        assertTrue(ControlReply.parse("ok=1\n").ok)
        val refused = ControlReply.parse("ok=0\nerror=It is not connected over Wi-Fi.\n")
        assertFalse(refused.ok)
        assertEquals("It is not connected over Wi-Fi.", refused.error)
        assertFalse(ControlReply.parse(null).answered)
    }
}
