package dev.spiegl.flyingcarpet

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Bluetooth doorbell carries eight bytes off the open air into a code path that raises a
 * hotspot, so what it will and will not accept is the whole of its security. These tests pin
 * that: a tag only a holder of the group key can make, a window narrow enough that a recording
 * stops working, and a size that fits the advertisement budget this fork has overrun before.
 */
class BleWakeUnitTest {

    private val groupKey = ByteArray(32) { 0xAB.toByte() }
    private val presenceKey = derivePresenceKey(groupKey)
    private val deviceId = ByteArray(16) { (it + 1).toByte() }

    /** Builds a payload the way WakeAdvertiser does, for a given minute. */
    private fun payloadFor(role: Byte, minute: Long, key: ByteArray = presenceKey): ByteArray {
        val material = "mahojutan wake v1".toByteArray(Charsets.UTF_8) +
            byteArrayOf(role, deviceId[0], deviceId[1]) +
            minute.toString().toByteArray(Charsets.UTF_8)
        return byteArrayOf(role, deviceId[0], deviceId[1]) +
            computeHmac(key, material).copyOf(5)
    }

    private fun nowMinute() = System.currentTimeMillis() / 60_000

    /**
     * Eight bytes. Flags take 3 of the advertisement's 31 and manufacturer data costs 2 + 2
     * on top of the payload, so this leaves 12 used of 31 — nowhere near the one-byte overrun
     * that made EMUI reject the fork's BLE advertisement outright with error 18.
     */
    @Test
    fun thePayloadFitsTheAdvertisementBudget() {
        val payload = payloadFor(1, nowMinute())
        assertEquals(8, payload.size)
        assertTrue("3 + 2 + 2 + payload must stay under 31", 3 + 2 + 2 + payload.size < 31)
    }

    @Test
    fun aGenuineRingIsAccepted() {
        val parsed = parseWake(payloadFor(1, nowMinute()), presenceKey)
        assertEquals(1.toByte(), parsed!!.first)
        assertArrayEquals(byteArrayOf(deviceId[0], deviceId[1]), parsed.second)
        assertTrue(wakeRoleIsSending(parsed.first))
    }

    /** Without the group key the tag cannot be produced, so a stranger cannot ring the bell. */
    @Test
    fun aStrangerCannotRing() {
        val theirs = derivePresenceKey(ByteArray(32) { 0x11 })
        assertNull(parseWake(payloadFor(1, nowMinute(), theirs), presenceKey))
    }

    /** A clock a minute out of step still works; the tolerance exists for exactly that. */
    @Test
    fun aMinuteOfClockSkewIsTolerated() {
        assertTrue(parseWake(payloadFor(2, nowMinute() - 1), presenceKey) != null)
        assertTrue(parseWake(payloadFor(2, nowMinute() + 1), presenceKey) != null)
    }

    /** And a recording made a few minutes ago does not. */
    @Test
    fun aReplayStopsWorking() {
        assertNull(parseWake(payloadFor(1, nowMinute() - 5), presenceKey))
        assertNull(parseWake(payloadFor(1, nowMinute() + 5), presenceKey))
    }

    @Test
    fun rubbishIsRejected() {
        assertNull(parseWake(null, presenceKey))
        assertNull(parseWake(ByteArray(4), presenceKey))
        // Role 0 is not a role; an unrecognised one must never fall through to a default.
        assertNull(parseWake(payloadFor(0, nowMinute()), presenceKey))
    }

    /** A flipped tag byte must fail, or the tag is decoration. */
    @Test
    fun aTamperedTagIsRejected() {
        val payload = payloadFor(1, nowMinute())
        payload[7] = (payload[7].toInt() xor 0xff).toByte()
        assertNull(parseWake(payload, presenceKey))
    }

    /**
     * The role is what decides which device raises the access point, so a ring for one role
     * must never verify as the other — the two halves would both host, or both join.
     */
    @Test
    fun theRoleIsBoundIntoTheTag() {
        val sending = payloadFor(1, nowMinute())
        val swapped = sending.copyOf()
        swapped[0] = 2
        assertNull(parseWake(swapped, presenceKey))
    }
}
