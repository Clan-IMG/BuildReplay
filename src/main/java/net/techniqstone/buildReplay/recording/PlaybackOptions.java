package net.techniqstone.buildReplay.recording;

/**
 * @param showInitialState Baustand vom Abschnittsbeginn clientseitig einblenden (sonst zeigt AdvancedReplay die
 *                         heutige Welt, und gesetzte Blöcke sind von Anfang an sichtbar)
 * @param forceClientSideBlockChanges AdvancedReplays {@code real_changes} vor dem Start auf {@code false}
 *                                    setzen (sonst verändert die Wiedergabe die echte Welt)
 */
public record PlaybackOptions(boolean showInitialState, boolean forceClientSideBlockChanges) {
}
