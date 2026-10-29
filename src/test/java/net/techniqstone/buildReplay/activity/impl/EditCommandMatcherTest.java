package net.techniqstone.buildReplay.activity.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class EditCommandMatcherTest {

    private final EditCommandMatcher matcher = new EditCommandMatcher(TestSupport.activity());

    @ParameterizedTest
    @ValueSource(strings = {
            "//set stone",
            "//wand",
            "//",
            "  //replace dirt grass  ",
            "/brush sphere stone 3",
            "/br",
            "/BRUSH cylinder",
            "/fill ~ ~ ~ ~5 ~5 ~5 stone",
            "/minecraft:fill ~ ~ ~ ~1 ~1 ~1 air",
            "/worldedit:/set stone",
            "/fastasyncworldedit:/paste -a",
            "/WorldEdit:brush sphere",
            "/setblock ~ ~ ~ stone",
            "/undo 3",
            "/tool none"
    })
    void editCommandsAreRecognised(String command) {
        assertTrue(matcher.matches(command), command);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/spawn",
            "/tp Steve",
            "/brushes",
            "/fillme",
            "/msg Alex //set",
            "/ fill ~ ~ ~",
            "/",
            "",
            "   ",
            "/worldedit:",
            "/buildreplay sessions"
    })
    void otherCommandsAreNotEditCommands(String command) {
        assertFalse(matcher.matches(command), command);
    }

    @Test
    void nullIsNeverAnEditCommand() {
        assertFalse(matcher.matches(null));
    }

    @Test
    void configuredValuesAreNormalised() {
        EditCommandMatcher custom = new EditCommandMatcher(List.of(" // ", "", "#"), Set.of("//Fill", " Paste ", "/"));

        assertEquals(List.of("//", "/#"), custom.prefixes());
        assertEquals(Set.of("fill", "paste"), custom.commands());
        assertTrue(custom.matches("/#hash"), "Präfix ohne Schrägstrich bekommt einen");
        assertTrue(custom.matches("/fill ~ ~ ~"));
        assertTrue(custom.matches("//fill"));
        assertTrue(custom.matches("/PASTE"));
        assertFalse(custom.matches("/brush"));
    }

    @Test
    void withoutPrefixesOnlyCommandNamesMatch() {
        EditCommandMatcher namesOnly = new EditCommandMatcher(List.of(), Set.of("set"));

        assertTrue(namesOnly.matches("//set stone"), "Befehlsname ohne führende Schrägstriche");
        assertTrue(namesOnly.matches("/set stone"));
        assertFalse(namesOnly.matches("//wand"));
    }

    @Test
    void namespacesAreOnlyStrippedWhenTheyLookLikeNamespaces() {
        assertEquals("/set", EditCommandMatcher.stripNamespace("worldedit:/set"));
        assertEquals("fill", EditCommandMatcher.stripNamespace("minecraft:fill"));
        assertEquals("/set", EditCommandMatcher.stripNamespace("/set"));
        assertEquals("a/b:c", EditCommandMatcher.stripNamespace("a/b:c"));
        assertEquals(":fill", EditCommandMatcher.stripNamespace(":fill"));
    }
}
