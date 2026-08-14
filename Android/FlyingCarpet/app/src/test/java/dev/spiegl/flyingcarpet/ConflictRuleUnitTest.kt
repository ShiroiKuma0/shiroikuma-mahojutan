package dev.spiegl.flyingcarpet

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * "Apply to all" as the sending device remembers it. The rule, not the answer: a sticky rename has
 * to name each file for itself, or every remaining file would be sent under the first one's name
 * and land on top of it.
 *
 * suggestRename() is twinned with suggest_rename() in core/src/utils.rs (tested there by
 * rename_suggestion_keeps_the_extension_last) and suggestRename() in Flying Carpet/src/main.js. All
 * three must answer the same, or the same transfer would name its files differently depending on
 * which app is sending.
 */
class ConflictRuleUnitTest {

    @Test
    fun renameSuggestionKeepsTheExtensionLast() {
        assertEquals("photo (copy).jpg", suggestRename("photo.jpg"))
        assertEquals("album/photo (copy).jpg", suggestRename("album/photo.jpg"))
        // no extension to keep
        assertEquals("notes (copy)", suggestRename("notes"))
        assertEquals("album/notes (copy)", suggestRename("album/notes"))
        // a leading dot is a dotfile, not an extension
        assertEquals(".bashrc (copy)", suggestRename(".bashrc"))
        assertEquals("home/.bashrc (copy)", suggestRename("home/.bashrc"))
        // only the last dot counts
        assertEquals("archive.tar (copy).gz", suggestRename("archive.tar.gz"))
    }

    @Test
    fun ruleSurvivesTheAnswerItCameFrom() {
        assertEquals(ConflictRule.Skip, ConflictRule.of(FileConflictChoice.Skip))
        assertEquals(ConflictRule.Overwrite, ConflictRule.of(FileConflictChoice.Overwrite))
        assertEquals(ConflictRule.Rename, ConflictRule.of(FileConflictChoice.Rename("anything.bin")))
    }

    @Test
    fun stickyRenameNamesEachFileForItself() {
        // The name typed for the first file is deliberately not reused.
        val rule = ConflictRule.of(FileConflictChoice.Rename("typed by hand.bin"))
        assertEquals(FileConflictChoice.Rename("one (copy).bin"), rule.apply("one.bin"))
        assertEquals(FileConflictChoice.Rename("two (copy).bin"), rule.apply("two.bin"))
        assertEquals(FileConflictChoice.Skip, ConflictRule.Skip.apply("one.bin"))
        assertEquals(FileConflictChoice.Overwrite, ConflictRule.Overwrite.apply("one.bin"))
    }
}
