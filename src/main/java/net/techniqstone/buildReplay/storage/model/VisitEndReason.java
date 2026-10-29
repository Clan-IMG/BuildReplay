package net.techniqstone.buildReplay.storage.model;

/** Warum ein Aufenthalt auf einem Server endete. */
public enum VisitEndReason {
    /** Spieler hat den Server verlassen (Serverwechsel oder Logout). */
    QUIT,
    /** Server bzw. Plugin wurde ordnungsgemäß beendet. */
    SHUTDOWN,
    /** Beim nächsten Start nach einem Absturz geschlossen. */
    RECOVERED
}
