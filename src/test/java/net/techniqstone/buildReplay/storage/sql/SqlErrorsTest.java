package net.techniqstone.buildReplay.storage.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLSyntaxErrorException;
import java.sql.SQLTransactionRollbackException;
import java.sql.SQLTransientConnectionException;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;

class SqlErrorsTest {

    @Test
    void lostConnectionsAndPoolTimeoutsMeanTheDatabaseIsUnavailable() {
        assertTrue(SqlErrors.isConnectionProblem(new SQLTransientConnectionException("pool timeout")));
        assertTrue(SqlErrors.isConnectionProblem(new SQLNonTransientConnectionException("socket closed", "08000")));
        assertTrue(SqlErrors.isConnectionProblem(new SQLException("gone away", "HY000", 2006)));
        assertTrue(SqlErrors.isConnectionProblem(new SQLException("read only", "HY000", 1290)));
        assertTrue(SqlErrors.isConnectionProblem(new SQLException("wrapped", new java.net.ConnectException("refused"))));
        assertFalse(SqlErrors.isConnectionProblem(new SQLSyntaxErrorException("bad sql", "42000", 1064)));
    }

    @Test
    void deadlocksLockTimeoutsAndDuplicateKeysAreRetried() {
        assertTrue(SqlErrors.isRetryable(new SQLTransactionRollbackException("deadlock", "40001", 1213)));
        assertTrue(SqlErrors.isRetryable(new SQLException("lock wait", "HY000", 1205)));
        assertTrue(SqlErrors.isRetryable(new SQLIntegrityConstraintViolationException("dup", "23000", 1062)));
        assertFalse(SqlErrors.isRetryable(new SQLSyntaxErrorException("bad sql", "42000", 1064)));
        assertFalse(SqlErrors.isRetryable(new SQLException("data too long", "22001", 1406)));
    }

    @Test
    void descriptionsAreShortSingleLineAndNeverContainThePassword() {
        String text = SqlErrors.describe(new CompletionException(
                new SQLException("Access denied using s3cr3t\nmore detail")), "s3cr3t");
        assertEquals("SQLException: Access denied using ***", text);
        assertTrue(SqlErrors.describe(new SQLException("x".repeat(1000)), "").length() <= 303);
    }
}
