package net.techniqstone.buildReplay.command;

/** Berechtigungen von BuildReplay (siehe plugin.yml). */
public final class Permissions {

    /** Eigene Sitzungen ansehen. */
    public static final String USE = "buildreplay.use";
    /** Eigene Abschnitte abspielen. */
    public static final String PLAY = "buildreplay.play";
    /** Sitzungen/Replays anderer Spieler ansehen und abspielen. */
    public static final String OTHERS = "buildreplay.others";
    /** Status, Diagnose, Reload, Aufräumen. */
    public static final String ADMIN = "buildreplay.admin";
    /** Spieler wird nicht aufgezeichnet. */
    public static final String EXEMPT = "buildreplay.exempt";

    private Permissions() {
    }
}
