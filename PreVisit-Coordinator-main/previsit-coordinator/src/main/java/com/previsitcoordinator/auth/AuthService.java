package com.previsitcoordinator.auth;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import jakarta.servlet.http.HttpSession;

/**
 * Registration, email OTP verification, login and the small amount of session
 * bookkeeping the demo needs. Accounts are created in a 'PENDING' state and
 * become usable only after the emailed 6-digit code is verified.
 */
@Service
public class AuthService {

    static final String SESSION_UID = "uid";
    static final String SESSION_USERNAME = "username";
    static final String SESSION_DISPLAY = "displayName";

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Duration OTP_TTL = Duration.ofMinutes(10);
    private static final int MAX_OTP_ATTEMPTS = 6;
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final Pattern E164 = Pattern.compile("^\\+[1-9]\\d{7,14}$");

    private final UserRepository users;
    private final EmailSender email;

    /**
     * When true and no Resend key is configured, the freshly generated code is
     * returned in the API response so the flow can be demoed / tested without a
     * real inbox. Never leave this on in production with real email disabled.
     */
    private final boolean devEcho;

    AuthService(UserRepository users, EmailSender email,
                @Value("${otp.dev-echo:true}") boolean devEcho) {
        this.users = users;
        this.email = email;
        this.devEcho = devEcho;
    }

    /**
     * Create a PENDING account and send an email OTP. Does NOT sign the user in;
     * the caller must verify the code first via {@link #verifyOtp}.
     */
    RegisterResult register(String username, String displayName, String password,
                            String emailAddr, String phone, HttpSession session) {
        String u = normalize(username);
        String name = displayName == null ? "" : displayName.trim();
        String mail = emailAddr == null ? "" : emailAddr.trim();
        String tel = phone == null ? "" : phone.trim();

        if (u.length() < 3) {
            throw badRequest("Username must be at least 3 characters");
        }
        if (name.isEmpty()) {
            throw badRequest("Display name is required");
        }
        if (!EMAIL.matcher(mail).matches()) {
            throw badRequest("A valid email address is required");
        }
        if (!E164.matcher(tel).matches()) {
            throw badRequest("Phone must be E.164 format, e.g. +6588044731");
        }
        if (password == null || password.length() < 6) {
            throw badRequest("Password must be at least 6 characters");
        }
        if (users.usernameExists(u)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That username is already taken");
        }
        if (users.emailExists(mail)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That email is already registered");
        }

        String salt = PasswordHasher.newSalt();
        String hash = PasswordHasher.hash(password, salt);
        String code = newOtpCode();
        String expires = Instant.now().plus(OTP_TTL).toString();
        // password stored plaintext (demo-only, per request) alongside the hash.
        long id = users.createPendingUser(u, name, mail, tel, hash, salt, password, code, expires);
        users.recordLoginEvent(id, "REGISTER");

        boolean sent = email.sendOtp(mail, name, code);
        return new RegisterResult("PENDING_VERIFICATION", u, mail, sent, devOtp(sent, code));
    }

    /** Verify the emailed code, activate the account, and open a session. */
    UserView verifyOtp(String username, String code, HttpSession session) {
        String u = normalize(username);
        UserAccount account = users.findByUsername(u)
                .orElseThrow(() -> badRequest("No account found for that username"));

        if ("ACTIVE".equalsIgnoreCase(account.status())) {
            // Already verified -- nothing to do; guide the user to sign in.
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This account is already verified. Please sign in.");
        }
        if (account.otpAttempts() >= MAX_OTP_ATTEMPTS) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Too many attempts. Request a new code.");
        }
        if (account.otpCode() == null || account.otpExpiresAt() == null) {
            throw badRequest("No active code. Request a new one.");
        }
        if (Instant.now().isAfter(Instant.parse(account.otpExpiresAt()))) {
            throw badRequest("That code has expired. Request a new one.");
        }
        String supplied = code == null ? "" : code.trim();
        if (!constantTimeEquals(supplied, account.otpCode())) {
            users.incrementOtpAttempts(account.id());
            throw badRequest("Incorrect code. Please try again.");
        }

        users.activateUser(account.id());
        users.recordLoginEvent(account.id(), "VERIFY");
        openSession(session, account.id(), account.username(), account.displayName());
        users.touchLastLogin(account.id());
        users.recordLoginEvent(account.id(), "LOGIN");
        return new UserView(account.username(), account.displayName(), account.email(),
                users.countCasesForUser(account.id()));
    }

    /** Generate and send a fresh OTP for a pending account. */
    RegisterResult resendOtp(String username) {
        String u = normalize(username);
        UserAccount account = users.findByUsername(u)
                .orElseThrow(() -> badRequest("No account found for that username"));
        if ("ACTIVE".equalsIgnoreCase(account.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This account is already verified. Please sign in.");
        }
        String code = newOtpCode();
        String expires = Instant.now().plus(OTP_TTL).toString();
        users.setOtp(account.id(), code, expires);
        boolean sent = email.sendOtp(account.email(), account.displayName(), code);
        return new RegisterResult("PENDING_VERIFICATION", u, account.email(), sent, devOtp(sent, code));
    }

    /** Verify credentials and sign the user in on the given session. */
    UserView login(String username, String password, HttpSession session) {
        String u = normalize(username);
        UserAccount account = users.findByUsername(u)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid username or password"));
        if (password == null || !PasswordHasher.matches(password, account.passwordSalt(), account.passwordHash())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid username or password");
        }
        if (!"ACTIVE".equalsIgnoreCase(account.status())) {
            // Signal the frontend to show the OTP step for this username.
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "ACCOUNT_PENDING_VERIFICATION");
        }
        openSession(session, account.id(), account.username(), account.displayName());
        users.touchLastLogin(account.id());
        users.recordLoginEvent(account.id(), "LOGIN");
        return new UserView(account.username(), account.displayName(), account.email(),
                users.countCasesForUser(account.id()));
    }

    void logout(HttpSession session) {
        Long uid = currentUserId(session);
        if (uid != null) {
            users.recordLoginEvent(uid, "LOGOUT");
        }
        session.invalidate();
    }

    /** The signed-in user for a session, or 401 if there is none. */
    UserView requireCurrent(HttpSession session) {
        Long uid = currentUserId(session);
        if (uid == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not signed in");
        }
        String username = (String) session.getAttribute(SESSION_USERNAME);
        String display = (String) session.getAttribute(SESSION_DISPLAY);
        String mail = users.findByUsername(username).map(UserAccount::email).orElse(null);
        return new UserView(username, display, mail, users.countCasesForUser(uid));
    }

    /** Record that the signed-in user started an intake case (best-effort). */
    public void recordCaseStart(HttpSession session, String caseId, String patientReference) {
        Long uid = currentUserId(session);
        if (uid != null) {
            users.recordCaseStart(uid, caseId, patientReference);
        }
    }

    /** The signed-in user's display name, or null. */
    public String currentDisplayName(HttpSession session) {
        return session == null ? null : (String) session.getAttribute(SESSION_DISPLAY);
    }

    static Long currentUserId(HttpSession session) {
        if (session == null) {
            return null;
        }
        Object uid = session.getAttribute(SESSION_UID);
        return uid instanceof Long ? (Long) uid : null;
    }

    private void openSession(HttpSession session, long id, String username, String displayName) {
        session.setAttribute(SESSION_UID, id);
        session.setAttribute(SESSION_USERNAME, username);
        session.setAttribute(SESSION_DISPLAY, displayName);
    }

    private String normalize(String username) {
        return username == null ? "" : username.trim().toLowerCase();
    }

    private static String newOtpCode() {
        return String.format("%06d", RANDOM.nextInt(1_000_000));
    }

    /** Only reveal the code when it could not be emailed and dev-echo is on. */
    private String devOtp(boolean sent, String code) {
        return (!sent && devEcho) ? code : null;
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static boolean constantTimeEquals(String a, String b) {
        byte[] x = a.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] y = b.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (x.length != y.length) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < x.length; i++) {
            diff |= x[i] ^ y[i];
        }
        return diff == 0;
    }

    /** Public, non-sensitive view of the signed-in user. */
    public record UserView(String username, String displayName, String email, int caseCount) {
    }

    /**
     * Result of registration / resend. {@code devOtp} is non-null only in dev
     * mode when the email could not actually be sent (no Resend key).
     */
    public record RegisterResult(String status, String username, String email,
                                 boolean emailSent, String devOtp) {
    }
}
