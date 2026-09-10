package com.previsitcoordinator.auth;

import java.time.Instant;
import java.util.Optional;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;

/**
 * Reads and writes user accounts and activity in the SQLite database.
 */
@Repository
class UserRepository {

    private final JdbcTemplate jdbc;

    UserRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final String SELECT_COLS =
            "SELECT id, username, display_name, email, phone, password_hash, password_salt, "
                    + "status, otp_code, otp_expires_at, otp_attempts FROM users ";

    private static final org.springframework.jdbc.core.RowMapper<UserAccount> MAPPER = (rs, n) -> new UserAccount(
            rs.getLong("id"),
            rs.getString("username"),
            rs.getString("display_name"),
            rs.getString("email"),
            rs.getString("phone"),
            rs.getString("password_hash"),
            rs.getString("password_salt"),
            rs.getString("status"),
            rs.getString("otp_code"),
            rs.getString("otp_expires_at"),
            rs.getInt("otp_attempts"));

    Optional<UserAccount> findByUsername(String username) {
        try {
            return Optional.ofNullable(
                    jdbc.queryForObject(SELECT_COLS + "WHERE username = ?", MAPPER, username));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    boolean usernameExists(String username) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM users WHERE username = ?", Integer.class, username);
        return count != null && count > 0;
    }

    boolean emailExists(String email) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM users WHERE email = ?", Integer.class, email);
        return count != null && count > 0;
    }

    /**
     * Create a new account. The account starts as 'PENDING' with an email OTP
     * and is not usable until verified. The plaintext password is stored in
     * password_plain purely because this demo asked for a readable record --
     * never do this in a real system.
     */
    long createPendingUser(String username, String displayName, String email, String phone,
                           String passwordHash, String passwordSalt, String passwordPlain,
                           String otpCode, String otpExpiresAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        String now = Instant.now().toString();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO users(username, display_name, email, phone, password_hash, "
                            + "password_salt, password_plain, status, otp_code, otp_expires_at, "
                            + "otp_attempts, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, 'PENDING', ?, ?, 0, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, username);
            ps.setString(2, displayName);
            ps.setString(3, email);
            ps.setString(4, phone);
            ps.setString(5, passwordHash);
            ps.setString(6, passwordSalt);
            ps.setString(7, passwordPlain);
            ps.setString(8, otpCode);
            ps.setString(9, otpExpiresAt);
            ps.setString(10, now);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        return key == null ? -1L : key.longValue();
    }

    /** Store a freshly generated OTP and reset the attempt counter. */
    void setOtp(long userId, String otpCode, String otpExpiresAt) {
        jdbc.update("UPDATE users SET otp_code = ?, otp_expires_at = ?, otp_attempts = 0 WHERE id = ?",
                otpCode, otpExpiresAt, userId);
    }

    /** Record a failed verification attempt. */
    void incrementOtpAttempts(long userId) {
        jdbc.update("UPDATE users SET otp_attempts = otp_attempts + 1 WHERE id = ?", userId);
    }

    /** Mark the account verified and clear the OTP. */
    void activateUser(long userId) {
        jdbc.update("UPDATE users SET status = 'ACTIVE', otp_code = NULL, otp_expires_at = NULL, "
                + "otp_attempts = 0 WHERE id = ?", userId);
    }

    void touchLastLogin(long userId) {
        jdbc.update("UPDATE users SET last_login_at = ? WHERE id = ?",
                Instant.now().toString(), userId);
    }

    void recordLoginEvent(long userId, String event) {
        jdbc.update("INSERT INTO login_audit(user_id, event, at) VALUES (?, ?, ?)",
                userId, event, Instant.now().toString());
    }

    void recordCaseStart(long userId, String caseId, String patientReference) {
        jdbc.update("INSERT INTO case_audit(user_id, case_id, patient_reference, at) VALUES (?, ?, ?, ?)",
                userId, caseId, patientReference, Instant.now().toString());
    }

    int countCasesForUser(long userId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM case_audit WHERE user_id = ?", Integer.class, userId);
        return count == null ? 0 : count;
    }
}
