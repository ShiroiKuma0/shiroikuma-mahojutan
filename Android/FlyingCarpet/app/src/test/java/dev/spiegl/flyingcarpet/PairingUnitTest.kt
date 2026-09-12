package dev.spiegl.flyingcarpet

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

/**
 * The cross-platform contract for paired devices. Every vector here is asserted identically
 * on the Rust side — the three key derivations in `core/src/noise.rs`
 * (`paired_derivations_known_answer`) and the presence record in `core/src/presence.rs`
 * (`presence_known_answer`). If either side moves without the other, a phone and the desktop
 * pair successfully and then never see or hear one another, which is the hardest kind of
 * failure to diagnose from the outside.
 */
class PairingUnitTest {

    private val groupKey = ByteArray(32) { 0xAB.toByte() }

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }

    // ── key derivation ────────────────────────────────────────────────────────────────────

    @Test
    fun presenceKeyMatchesRust() {
        assertEquals(
            "5678179a00a6f0f3a42e3c51a9e9ab1a26bb43bbb7613347e1c7c4634cd77bd6",
            hex(derivePresenceKey(groupKey)),
        )
    }

    @Test
    fun pairedPskMatchesRust() {
        assertEquals(
            "a93dc0ab95d012e32d5d98c447cfd72edb404faab555856cf7f8e8f80aaed594",
            hex(derivePairedPsk(groupKey)),
        )
    }

    @Test
    fun hotspotPasswordMatchesRust() {
        assertEquals("wy2MBG98PJ", deriveHotspotPassword(groupKey))
    }

    /** Each label must give a different key; a copy-paste slip would be invisible otherwise. */
    @Test
    fun theThreePairedKeysAreDistinct() {
        val presence = derivePresenceKey(groupKey)
        val psk = derivePairedPsk(groupKey)
        assertNotEquals(hex(presence), hex(psk))
        assertNotEquals(hex(presence), hex(groupKey))
        assertNotEquals(hex(psk), hex(groupKey))
    }

    /**
     * The derived credential has to be indistinguishable in shape from a generated one: the
     * SSID derivation, the WPA2 passphrase and the Wi-Fi Direct group name all take it as-is.
     */
    @Test
    fun theHotspotPasswordHasTheShapeTheHotspotCodeExpects() {
        val alphabet = "23456789abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ"
        for (seed in 0 until 32) {
            val password = deriveHotspotPassword(ByteArray(32) { seed.toByte() })
            assertEquals(10, password.length)
            assertTrue(
                "seed $seed produced $password, which is outside the alphabet",
                password.all { alphabet.contains(it) },
            )
        }
    }

    // ── base32 ────────────────────────────────────────────────────────────────────────────

    @Test
    fun base32RoundTrips() {
        val data = ByteArray(256) { it.toByte() }
        val decoded = base32Decode(base32Encode(data))!!
        assertArrayEquals(data, decoded.copyOf(data.size))
    }

    /** Spaces, hyphens and capitals are what a person typing a long key actually produces. */
    @Test
    fun base32ToleratesTypedInput() {
        val key = ByteArray(32) { 0x9A.toByte() }
        val encoded = base32Encode(key)
        val typed = encoded.substring(0, 26).lowercase() + "-" + encoded.substring(26).lowercase()
        assertArrayEquals(key, base32Decode(typed)!!.copyOf(32))
    }

    /**
     * O and 0 are not the same character but they are the same keystroke to someone copying
     * by eye, and a key that fails for that reason is indistinguishable from a broken feature.
     */
    @Test
    fun base32FoldsConfusableLetters() {
        val encoded = base32Encode(ByteArray(32))
        assertTrue(encoded.startsWith("0"))
        val mistyped = encoded.replaceFirst("0", "O")
        assertArrayEquals(base32Decode(encoded), base32Decode(mistyped))
    }

    @Test
    fun base32RejectsRubbish() {
        assertNull(base32Decode("not a key!"))
    }

    // ── the pairing code ──────────────────────────────────────────────────────────────────

    @Test
    fun pairUriRoundTripsWithAJapaneseName() {
        val key = ByteArray(32) { 0x42 }
        val id = ByteArray(16) { 0x17 }
        val uri = pairUri(key, base32Encode(id), "白い熊二代目")
        val code = parsePairUri(uri)!!
        assertArrayEquals(key, code.key)
        assertArrayEquals(id, code.deviceId)
        assertEquals("白い熊二代目", code.name)
    }

    /** The typed form is exactly what someone retyping from under a QR code produces. */
    @Test
    fun theTypedFormPairsWithoutTheUriWrapper() {
        val key = ByteArray(32) { 0x42 }
        val id = ByteArray(16) { 0x17 }
        val typed = typedCode(pairUri(key, base32Encode(id), "desk"))
        assertEquals(78, typed.replace(" ", "").length)
        val code = parsePairUri(typed.lowercase())!!
        assertArrayEquals(key, code.key)
        assertArrayEquals(id, code.deviceId)
        assertNull(code.name)
    }

    @Test
    fun aShortCodeIsRefused() {
        assertNull(parsePairUri(base32Encode(ByteArray(32))))
    }

    /** A group-model code must be refused, and recognisably so. */
    @Test
    fun anOldGroupCodeIsRefused() {
        val old = "mahojutan-pair:1:${base32Encode(ByteArray(32) { 1 })}:phone"
        assertNull(parsePairUri(old))
        assertTrue(isOldPairUri(old))
    }

    // ── keys ──────────────────────────────────────────────────────────────────────────────

    /** The key id, byte for byte what pairing.rs computes — see presence_known_answer. */
    @Test
    fun keyIdMatchesRust() {
        assertEquals("c179cecd", hex(keyId(groupKey)))
    }

    @Test
    fun theRingOpensOnlyWithTheRightKeyForTheRightDevice() {
        val me = ByteArray(16) { 7 }
        val mine = PairKey.bound(ByteArray(32) { 0x10 }, me)
        val someoneElses = PairKey.bound(ByteArray(32) { 0x20 }, ByteArray(16) { 3 })
        val pending = PairKey.pending(ByteArray(32) { 0x30 })
        val ring = KeyRing(listOf(mine, someoneElses, pending))
        assertEquals(listOf(mine), ring.candidates(me, mine.keyId))
        assertEquals(listOf(pending), ring.candidates(me, pending.keyId))
        assertTrue(ring.candidates(me, someoneElses.keyId).isEmpty())
    }

    /** A derived ring keeps the pair key's id: that is what travels on the wire. */
    @Test
    fun aPresenceRingKeepsThePairKeyIds() {
        val key = PairKey.bound(groupKey, ByteArray(16) { 7 })
        val presence = KeyRing(listOf(key)).forPresence().keys.single()
        assertArrayEquals(key.keyId, presence.keyId)
        assertArrayEquals(derivePresenceKey(groupKey), presence.key)
        assertArrayEquals(groupKey, KeyRing(listOf(key)).pairKeyBehind(presence.key))
    }

    /** Half an encoded kanji would make the presence record's name field invalid UTF-8. */
    @Test
    fun clampNameCutsBetweenGlyphs() {
        val long = "白".repeat(40) // 120 bytes
        val clamped = clampName(long)
        assertTrue(clamped.toByteArray(Charsets.UTF_8).size <= MAX_NAME_BYTES)
        assertEquals(MAX_NAME_BYTES / 3, clamped.length)
    }

    // ── the presence record ───────────────────────────────────────────────────────────────

    /**
     * The vector `core/src/presence.rs::presence_known_answer` asserts, byte for byte. The
     * name length is in BYTES, not characters: 白い熊 is three characters and nine bytes, and
     * getting that wrong is precisely the mistake that cost this fork its BLE advertisement.
     */
    @Test
    fun presenceRecordMatchesRust() {
        val key = ByteArray(32) { 0xAB.toByte() }
        val announcement = PresenceAnnouncement(
            flags = FLAG_PROBE or FLAG_UNATTENDED,
            os = PeerOs.LINUX,
            deviceId = byteArrayOf(
                0x00, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77,
                0x88.toByte(), 0x99.toByte(), 0xaa.toByte(), 0xbb.toByte(),
                0xcc.toByte(), 0xdd.toByte(), 0xee.toByte(), 0xff.toByte(),
            ),
            keyId = keyId(key),
            ipAddress = byteArrayOf(192.toByte(), 168.toByte(), 128.toByte(), 7),
            port = PRESENCE_PORT,
            timestamp = 1_700_000_000L,
            name = "白い熊",
        )
        assertEquals(
            "46435052" +                         // "FCPR"
                "0002" +                         // version 2
                "0003" +                         // FLAG_PROBE | FLAG_UNATTENDED
                "02" +                           // PeerOs.LINUX
                "00112233445566778899aabbccddeeff" +
                "c179cecd" +                     // key id
                "c0a88007" +                     // 192.168.128.7
                "0cdb" +                         // port 3291
                "000000006553f100" +             // timestamp 1700000000
                "09" +                           // name length in BYTES
                "e799bde38184e7868a" +           // 白い熊
                "24899f92f07f9065baa4562e1d61ca584c5c29e847b0e9bd3b9c2669b29fdcfe",
            hex(announcement.serialize(key)),
        )
    }

    @Test
    fun presenceRecordRoundTrips() {
        val key = derivePresenceKey(groupKey)
        val identity = LocalIdentity(
            deviceId = ByteArray(16) { 7 },
            name = "白い熊二代目",
            os = PeerOs.ANDROID,
            unattended = true,
        )
        val bytes = PresenceAnnouncement
            .create(identity, PairKey.pending(key), InetAddress.getByName("192.168.128.7"), probe = true)
            .serialize(key)
        val parsed = PresenceAnnouncement.deserialize(bytes, bytes.size, key)!!
        assertEquals("白い熊二代目", parsed.name)
        assertEquals(PeerOs.ANDROID, parsed.os)
        assertTrue(parsed.isProbe)
        assertTrue(parsed.isUnattended)
        assertTrue(parsed.isFresh())
    }

    @Test
    fun aWrongKeyIsRejected() {
        val identity = LocalIdentity(ByteArray(16), "x", PeerOs.ANDROID, false)
        val bytes = PresenceAnnouncement
            .create(identity, PairKey.pending(ByteArray(32) { 1 }), InetAddress.getByName("10.0.0.1"), probe = false)
            .serialize(ByteArray(32) { 1 })
        assertNull(PresenceAnnouncement.deserialize(bytes, bytes.size, ByteArray(32) { 2 }))
    }

    @Test
    fun aTamperedNameIsRejected() {
        val key = ByteArray(32) { 0x33 }
        val identity = LocalIdentity(ByteArray(16), "hello", PeerOs.ANDROID, false)
        val bytes = PresenceAnnouncement
            .create(identity, PairKey.pending(key), InetAddress.getByName("10.0.0.1"), probe = false)
            .serialize(key)
        bytes[PRESENCE_HEADER_SIZE] = (bytes[PRESENCE_HEADER_SIZE].toInt() xor 0xff).toByte()
        assertNull(PresenceAnnouncement.deserialize(bytes, bytes.size, key))
    }

    /** A forged length byte must fail the length check, before anything is sized from it. */
    @Test
    fun aLyingLengthByteIsRejected() {
        val key = ByteArray(32) { 0x44 }
        val identity = LocalIdentity(ByteArray(16), "hello", PeerOs.ANDROID, false)
        val bytes = PresenceAnnouncement
            .create(identity, PairKey.pending(key), InetAddress.getByName("10.0.0.1"), probe = false)
            .serialize(key)
        bytes[PRESENCE_HEADER_SIZE - 1] = 200.toByte()
        assertNull(PresenceAnnouncement.deserialize(bytes, bytes.size, key))
    }

    @Test
    fun anEmptyNameIsLegal() {
        val key = ByteArray(32) { 0x66 }
        val identity = LocalIdentity(ByteArray(16), "", PeerOs.ANDROID, false)
        val bytes = PresenceAnnouncement
            .create(identity, PairKey.pending(key), InetAddress.getByName("10.0.0.1"), probe = false)
            .serialize(key)
        assertEquals(PRESENCE_MIN_SIZE, bytes.size)
        assertEquals("", PresenceAnnouncement.deserialize(bytes, bytes.size, key)!!.name)
    }

    // ── the subnet ────────────────────────────────────────────────────────────────────────

    @Test
    fun subnetBroadcastIsTheAllOnesHost() {
        assertEquals(
            InetAddress.getByName("192.168.135.255"),
            presenceBroadcastAddress(InetAddress.getByName("192.168.128.7"), 21),
        )
        assertEquals(
            InetAddress.getByName("192.168.1.255"),
            presenceBroadcastAddress(InetAddress.getByName("192.168.1.5"), 24),
        )
        assertNull(presenceBroadcastAddress(InetAddress.getByName("10.0.0.1"), 31))
    }

    /**
     * 白い熊's own network is a /21, and it is the one where multicast between clients was
     * dropped (2026-08-10). The sweep that rescues it must therefore be allowed to run there.
     */
    @Test
    fun aSlash21IsStillSweepable() {
        val targets = presenceScanTargets(InetAddress.getByName("192.168.128.7"), 21)!!
        assertEquals(2045, targets.size) // 2046 hosts, minus ourselves
        assertTrue(targets.none { it == InetAddress.getByName("192.168.128.7") })
        assertNull(presenceScanTargets(InetAddress.getByName("10.0.0.1"), 8))
    }
}
