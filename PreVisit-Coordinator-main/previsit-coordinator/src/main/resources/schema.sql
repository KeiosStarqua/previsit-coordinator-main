-- Schema for the PreVisit Coordinator SQLite database.
-- Runs on startup (spring.sql.init.mode=always); IF NOT EXISTS keeps it idempotent.
--
-- NOTE on the plaintext password column (password_plain):
-- It is stored ONLY because this coursework demo explicitly asked for a
-- readable password record. A real system must NEVER store plaintext
-- passwords -- rely on password_hash / password_salt instead.

CREATE TABLE IF NOT EXISTS users (
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    username       TEXT    NOT NULL UNIQUE,
    display_name   TEXT    NOT NULL,
    email          TEXT,
    phone          TEXT,
    password_hash  TEXT    NOT NULL,
    password_salt  TEXT    NOT NULL,
    password_plain TEXT,                    -- demo-only readable password record
    status         TEXT    NOT NULL DEFAULT 'ACTIVE',  -- 'PENDING' until email OTP verified, then 'ACTIVE'
    otp_code       TEXT,                    -- current 6-digit email verification code
    otp_expires_at TEXT,                    -- ISO-8601 expiry for otp_code
    otp_attempts   INTEGER NOT NULL DEFAULT 0,
    created_at     TEXT    NOT NULL,
    last_login_at  TEXT
);

-- One row per login / logout event, for a simple activity trail.
CREATE TABLE IF NOT EXISTS login_audit (
    id       INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id  INTEGER NOT NULL,
    event    TEXT    NOT NULL,          -- 'LOGIN' | 'LOGOUT' | 'REGISTER' | 'VERIFY'
    at       TEXT    NOT NULL,
    FOREIGN KEY (user_id) REFERENCES users(id)
);

-- One row per intake case a signed-in user starts.
CREATE TABLE IF NOT EXISTS case_audit (
    id                INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id           INTEGER NOT NULL,
    case_id           TEXT    NOT NULL,
    patient_reference TEXT    NOT NULL,
    at                TEXT    NOT NULL,
    FOREIGN KEY (user_id) REFERENCES users(id)
);
