package net.techniqstone.buildReplay.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import net.techniqstone.buildReplay.command.CommandRequest.Invalid;
import org.junit.jupiter.api.Test;

class CommandArgumentsTest {

    private static CommandRequest parse(String... args) {
        return CommandArguments.parse(args);
    }

    @Test
    void noArgumentsAndHelpShowTheHelp() {
        assertEquals(new CommandRequest.Help(), parse());
        assertEquals(new CommandRequest.Help(), parse("help"));
        assertEquals(new CommandRequest.Help(), parse("HILFE"));
    }

    @Test
    void pagesAreEnteredOneBasedAndPassedOnZeroBased() {
        assertEquals(new CommandRequest.ListSessions(null, 0), parse("sessions"));
        assertEquals(new CommandRequest.ListSessions(null, 1), parse("sessions", "2"));
        assertEquals(OptionalInt.of(0), CommandArguments.parsePage("1"));
        assertEquals(OptionalInt.of(9_999), CommandArguments.parsePage("10000"));
        assertEquals(OptionalInt.empty(), CommandArguments.parsePage("0"));
        assertEquals(OptionalInt.empty(), CommandArguments.parsePage("10001"));
        assertEquals(OptionalInt.empty(), CommandArguments.parsePage("-3"));
        assertEquals(OptionalInt.empty(), CommandArguments.parsePage("99999999999"));
        assertEquals(OptionalInt.empty(), CommandArguments.parsePage("zwei"));
    }

    @Test
    void anInvalidPageNumberIsReportedWithTheAllowedRange() {
        Invalid invalid = assertInstanceOf(Invalid.class, parse("sessions", "0"));
        assertEquals(CommandTexts.INVALID_PAGE, invalid.text());
        assertEquals(java.util.List.of("input", "0", "max", "10000"), invalid.placeholders());
        assertEquals(CommandTexts.USAGE_SESSIONS, invalid.usage());
        assertEquals(CommandTexts.INVALID_PAGE, ((Invalid) parse("sessions", "-1")).text());
        assertEquals(CommandTexts.INVALID_PAGE, ((Invalid) parse("sessions", "Steve", "x")).text());
    }

    @Test
    void aSingleNonNumericArgumentAfterSessionsIsAPlayerName() {
        assertEquals(new CommandRequest.ListSessions("Steve", 0), parse("sessions", "Steve"));
        assertEquals(new CommandRequest.ListSessions("Steve", 2), parse("sessions", "Steve", "3"));
        assertEquals(new CommandRequest.ListSessions("123", 0), parse("sessions", "123", "1"),
                "reine Ziffernnamen über die Zwei-Argument-Form");
        assertEquals(new CommandRequest.ListSessions(".BedrockSteve", 0), parse("sitzungen", ".BedrockSteve"));
    }

    @Test
    void invalidPlayerNamesAreRejected() {
        assertTrue(CommandArguments.isValidPlayerName("Notch"));
        assertTrue(CommandArguments.isValidPlayerName("a_b_c_123456789X"));
        assertFalse(CommandArguments.isValidPlayerName("viel_zu_langer_name"));
        assertFalse(CommandArguments.isValidPlayerName("Böse"));
        assertFalse(CommandArguments.isValidPlayerName("a b"));
        assertFalse(CommandArguments.isValidPlayerName("&cRot"));
        Invalid invalid = assertInstanceOf(Invalid.class, parse("sessions", "&cRot"));
        assertEquals(CommandTexts.INVALID_PLAYER, invalid.text());
        assertEquals(CommandTexts.INVALID_PLAYER, ((Invalid) parse("session", "59dc83e5", "x y")).text());
    }

    @Test
    void sessionReferencesAreNormalisedAndValidated() {
        assertEquals(Optional.of("59dc83e5"), CommandArguments.normalizeRef(" 59DC83E5 "));
        assertEquals(Optional.of("p1a2b3c4"), CommandArguments.normalizeRef("p1a2b3c4"));
        assertEquals(Optional.of("3c8a7573-8fc3-4084-a73c-b2583e618ec4"),
                CommandArguments.normalizeRef("3c8a7573-8fc3-4084-a73c-b2583e618ec4"));
        assertEquals(Optional.empty(), CommandArguments.normalizeRef("ab"), "zu kurz");
        assertEquals(Optional.empty(), CommandArguments.normalizeRef("-abc"));
        assertEquals(Optional.empty(), CommandArguments.normalizeRef("59dc;drop"));
        assertEquals(Optional.empty(), CommandArguments.normalizeRef("x".repeat(65)));
        assertEquals(new CommandRequest.ShowSession("59dc83e5", null), parse("session", "59DC83E5"));
        assertEquals(new CommandRequest.ShowSession("59dc83e5", "Steve"), parse("sitzung", "59dc83e5", "Steve"));
        Invalid invalid = assertInstanceOf(Invalid.class, parse("session", "%%"));
        assertEquals(CommandTexts.INVALID_REF, invalid.text());
        assertEquals(CommandTexts.USAGE_SESSION, ((Invalid) parse("session")).text());
    }

    @Test
    void playAcceptsASegmentIdOrAWholeSession() {
        assertEquals(new CommandRequest.PlaySegment(42), parse("play", "42"));
        assertEquals(new CommandRequest.PlaySegment(42), parse("play", "#42"));
        assertEquals(new CommandRequest.PlaySession("59dc83e5", null), parse("play", "session", "59dc83e5"));
        assertEquals(new CommandRequest.PlaySession("59dc83e5", "Alex"), parse("abspielen", "sitzung", "59dc83e5", "Alex"));
        assertEquals(OptionalLong.empty(), CommandArguments.parseSegmentId("0"));
        assertEquals(OptionalLong.empty(), CommandArguments.parseSegmentId("abc"));
        assertEquals(CommandTexts.INVALID_SEGMENT, ((Invalid) parse("play", "abc")).text());
        assertEquals(CommandTexts.USAGE_PLAY, ((Invalid) parse("play")).text());
        assertEquals(CommandTexts.USAGE_PLAY, ((Invalid) parse("play", "session")).text());
    }

    @Test
    void theDryRunFlagIsRecognisedAndUnknownFlagsAreRejected() {
        assertEquals(new CommandRequest.AdminCleanup(false), parse("admin", "cleanup"));
        assertEquals(new CommandRequest.AdminCleanup(true), parse("admin", "cleanup", "--dry-run"));
        assertEquals(new CommandRequest.AdminCleanup(true), parse("admin", "cleanup", "-n"));
        assertEquals(new CommandRequest.AdminCleanup(true), parse("admin", "aufräumen", "--DRY-RUN"));
        Invalid invalid = assertInstanceOf(Invalid.class, parse("admin", "cleanup", "--force"));
        assertEquals(CommandTexts.UNKNOWN_FLAG, invalid.text());
        assertEquals(CommandTexts.USAGE_CLEANUP, invalid.usage());
    }

    @Test
    void adminSubcommandsAreParsed() {
        assertEquals(new CommandRequest.AdminRecordings(), parse("admin", "recordings"));
        assertEquals(new CommandRequest.AdminInspect("Steve"), parse("admin", "inspect", "Steve"));
        assertEquals(new CommandRequest.AdminReload(), parse("admin", "reload"));
        assertEquals(new CommandRequest.AdminSync(), parse("admin", "sync"));
        assertEquals(CommandTexts.USAGE_INSPECT, ((Invalid) parse("admin", "inspect")).text());
        assertEquals(CommandTexts.USAGE_ADMIN, ((Invalid) parse("admin")).text());
        assertEquals(CommandTexts.UNKNOWN_SUBCOMMAND, ((Invalid) parse("admin", "explode")).text());
    }

    @Test
    void unknownSubcommandsAreReportedWithTheInput() {
        Invalid invalid = assertInstanceOf(Invalid.class, parse("fly"));
        assertEquals(CommandTexts.UNKNOWN_SUBCOMMAND, invalid.text());
        assertEquals(java.util.List.of("input", "fly"), invalid.placeholders());
        assertEquals(new CommandRequest.Stop(), parse("stop"));
        assertEquals(new CommandRequest.Status(), parse("status"));
    }
}
