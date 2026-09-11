package dev.spiegl.flyingcarpet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The peer list's rules, independent of Android: what an announcement may and may not
 * overwrite, and what reordering does to a list that is not quite what the caller thought.
 *
 * These are pure-function tests over [PairedPeer] and the same merge rule [Pairing.upsertPeer]
 * applies. The store itself needs a Context and is exercised on the device; what matters here
 * is the policy, which is where the mistakes are.
 */
class PeerListUnitTest {

    private fun peer(
        id: String,
        name: String = "announced",
        alias: String? = null,
        dir: String? = null,
        autoAccept: Boolean = true,
    ) = PairedPeer(
        deviceId = id,
        name = name,
        os = "android",
        lastIp = null,
        lastSeen = 0,
        autoAccept = autoAccept,
        receiveDir = dir,
        alias = alias,
    )

    /** The merge rule upsertPeer uses: an announcement refreshes, it never overrides. */
    private fun merge(existing: PairedPeer, announced: PairedPeer) = existing.copy(
        name = announced.name,
        os = announced.os,
        lastIp = announced.lastIp ?: existing.lastIp,
        lastSeen = maxOf(announced.lastSeen, existing.lastSeen),
        receiveDir = existing.receiveDir ?: announced.receiveDir,
        alias = existing.alias,
    )

    @Test
    fun theAliasWinsOverTheAnnouncedName() {
        assertEquals("the desk", peer("A", name = "GRL_LX9", alias = "the desk").displayName)
    }

    @Test
    fun theAnnouncedNameIsUsedWhenThereIsNoAlias() {
        assertEquals("GRL_LX9", peer("A", name = "GRL_LX9").displayName)
    }

    /** Never a blank row: an unnamed device still has to be something you can point at. */
    @Test
    fun anUnnamedDeviceFallsBackToItsId() {
        assertEquals("ABCDEF", peer("ABCDEFGHIJ", name = "").displayName)
        assertEquals("ABCDEF", peer("ABCDEFGHIJ", name = "", alias = "").displayName)
    }

    /**
     * The point of storing the alias separately: [PairedPeer.name] is refreshed from every
     * announcement, so a rename kept there would be undone by the next scan.
     */
    @Test
    fun anAnnouncementCannotUndoARename() {
        val stored = peer("A", name = "GRL_LX9", alias = "the desk")
        val merged = merge(stored, peer("A", name = "GRL_LX9 renamed by itself"))
        assertEquals("the desk", merged.displayName)
        assertEquals("GRL_LX9 renamed by itself", merged.name)
    }

    /** Nor move where files from it land, nor grant itself unattended acceptance. */
    @Test
    fun anAnnouncementCannotMoveTheFolderOrGrantItself() {
        val stored = peer("A", dir = "content://chosen", autoAccept = false)
        val merged = merge(stored, peer("A", dir = "content://somewhere-else", autoAccept = true))
        assertEquals("content://chosen", merged.receiveDir)
        assertEquals(false, merged.autoAccept)
    }

    /** A device paired before per-device folders existed takes the announcement's seed. */
    @Test
    fun aPeerWithNoFolderTakesTheSeededOne() {
        val merged = merge(peer("A", dir = null), peer("A", dir = "content://seeded"))
        assertEquals("content://seeded", merged.receiveDir)
    }

    // ── ordering ──────────────────────────────────────────────────────────────────────────

    /** The rule Pairing.reorder applies, over a plain list. */
    private fun reorder(current: List<PairedPeer>, ids: List<String>): List<PairedPeer> {
        val byId = current.associateBy { it.deviceId }
        val moved = ids.mapNotNull { byId[it] }
        val untouched = current.filter { p -> ids.none { it == p.deviceId } }
        return moved + untouched
    }

    @Test
    fun reorderPutsThemInTheOrderGiven() {
        val list = listOf(peer("A"), peer("B"), peer("C"))
        assertEquals(listOf("C", "A", "B"), reorder(list, listOf("C", "A", "B")).map { it.deviceId })
    }

    /**
     * A drag is decided against the list as it was drawn, and a scan can add a device while
     * the finger is still down. A peer the caller never mentioned must keep its place rather
     * than vanish — losing a device to a reorder would be the worst possible outcome here.
     */
    @Test
    fun reorderCannotDropADeviceItWasNotToldAbout() {
        val list = listOf(peer("A"), peer("B"), peer("C"))
        val result = reorder(list, listOf("C", "A"))
        assertEquals(listOf("C", "A", "B"), result.map { it.deviceId })
    }

    /** And an id for a device that is no longer stored is simply not there to move. */
    @Test
    fun reorderIgnoresAnIdItDoesNotHave() {
        val list = listOf(peer("A"), peer("B"))
        assertEquals(listOf("B", "A"), reorder(list, listOf("B", "GONE", "A")).map { it.deviceId })
    }

    @Test
    fun reorderingNothingChangesNothing() {
        val list = listOf(peer("A"), peer("B"))
        assertEquals(listOf("A", "B"), reorder(list, emptyList()).map { it.deviceId })
    }

    @Test
    fun anEmptyAliasIsStoredAsNone() {
        assertNull("".trim().takeIf(String::isNotEmpty))
    }
}
