package net.techniqstone.buildReplay.storage.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Instant;
import net.techniqstone.buildReplay.storage.model.SessionSource;
import net.techniqstone.buildReplay.storage.model.SessionStatus;
import org.junit.jupiter.api.Test;

class SessionStateTest {

    private static final Instant T = Instant.parse("2026-10-20T12:00:00Z");

    private static SessionState active() {
        return SessionState.initial(null, SessionSource.PROVISIONAL, T, null, null);
    }

    @Test
    void keepsTheEarliestLoginAndTheLatestLogout() {
        SessionState state = active()
                .merge(null, SessionSource.PROVISIONAL, T.minusSeconds(60), T.plusSeconds(100), 160L)
                .merge(null, SessionSource.PROVISIONAL, T.plusSeconds(30), T.plusSeconds(300), 360L);
        assertEquals(T.minusSeconds(60), state.loginTime());
        assertEquals(T.plusSeconds(300), state.logoutTime());
        assertEquals(360L, state.durationSeconds());
        assertEquals(SessionStatus.CLOSED, state.status());
    }

    @Test
    void nullValuesNeverEraseKnownTimes() {
        SessionState closed = active().merge(null, SessionSource.PROVISIONAL, null, T.plusSeconds(10), 10L);
        SessionState merged = closed.merge(null, SessionSource.PROVISIONAL, null, null, null);
        assertEquals(closed, merged);
    }

    @Test
    void anEarlierLogoutReplacesNeitherTheLogoutNorItsDuration() {
        SessionState state = active()
                .merge(null, SessionSource.PROVISIONAL, null, T.plusSeconds(500), 500L)
                .merge(null, SessionSource.PROVISIONAL, null, T.plusSeconds(100), 100L);
        assertEquals(T.plusSeconds(500), state.logoutTime());
        assertEquals(500L, state.durationSeconds());
    }

    @Test
    void aDurationFillsAMissingOneEvenFromAnOlderLogout() {
        SessionState state = active()
                .merge(null, SessionSource.PROVISIONAL, null, T.plusSeconds(500), null)
                .merge(null, SessionSource.PROVISIONAL, null, T.plusSeconds(100), 100L);
        assertEquals(T.plusSeconds(500), state.logoutTime());
        assertEquals(100L, state.durationSeconds());
    }

    @Test
    void simpleCloudWinsOverProvisionalInBothDirections() {
        assertEquals(SessionSource.SIMPLECLOUD,
                active().merge(null, SessionSource.SIMPLECLOUD, null, null, null).source());
        SessionState cloud = SessionState.initial("c", SessionSource.SIMPLECLOUD, T, null, null);
        assertEquals(SessionSource.SIMPLECLOUD, cloud.merge(null, SessionSource.PROVISIONAL, null, null, null).source());
    }

    @Test
    void aKnownCloudSessionIdIsNeverReplaced() {
        SessionState state = SessionState.initial("first", SessionSource.SIMPLECLOUD, T, null, null)
                .merge("second", SessionSource.SIMPLECLOUD, null, null, null);
        assertEquals("first", state.cloudSessionId());
        assertEquals("new", active().merge("new", SessionSource.SIMPLECLOUD, null, null, null).cloudSessionId());
    }

    @Test
    void mergedIsNeverDowngradedByALogout() {
        SessionState merged = new SessionState(null, SessionSource.PROVISIONAL, SessionStatus.MERGED, T, null, null);
        assertEquals(SessionStatus.MERGED,
                merged.merge(null, SessionSource.PROVISIONAL, null, T.plusSeconds(5), 5L).status());
    }

    @Test
    void withoutLogoutTheStatusStaysActive() {
        SessionState state = active().merge(null, SessionSource.SIMPLECLOUD, T.minusSeconds(1), null, null);
        assertEquals(SessionStatus.ACTIVE, state.status());
        assertNull(state.logoutTime());
    }

    @Test
    void comparesAtMillisecondPrecisionLikeTheDatabase() {
        SessionState a = SessionState.initial(null, SessionSource.PROVISIONAL, T.plusNanos(1_500_000), null, null);
        SessionState b = SessionState.initial(null, SessionSource.PROVISIONAL, T.plusMillis(1), null, null);
        assertEquals(a, b);
    }
}
