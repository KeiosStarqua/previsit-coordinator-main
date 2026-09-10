package com.previsitcoordinator.auth;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ApplicationArguments;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Adds columns introduced after the first release to an existing SQLite
 * database. schema.sql only CREATEs tables IF NOT EXISTS, so a database that
 * was created before the email / OTP / plaintext-password columns existed
 * would otherwise be missing them. This runner checks the live table shape via
 * PRAGMA table_info and ALTERs in whatever is missing -- it is idempotent and
 * non-destructive (existing accounts and their data are preserved).
 *
 * Runs after Spring's spring.sql.init step (which creates the tables) thanks to
 * the late @Order value.
 */
@Component
@Order(Integer.MAX_VALUE)
class SchemaMigration implements ApplicationRunner {

    private final JdbcTemplate jdbc;

    SchemaMigration(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        Set<String> cols = existingColumns("users");
        addIfMissing(cols, "email", "ALTER TABLE users ADD COLUMN email TEXT");
        addIfMissing(cols, "phone", "ALTER TABLE users ADD COLUMN phone TEXT");
        addIfMissing(cols, "password_plain", "ALTER TABLE users ADD COLUMN password_plain TEXT");
        addIfMissing(cols, "status", "ALTER TABLE users ADD COLUMN status TEXT NOT NULL DEFAULT 'ACTIVE'");
        addIfMissing(cols, "otp_code", "ALTER TABLE users ADD COLUMN otp_code TEXT");
        addIfMissing(cols, "otp_expires_at", "ALTER TABLE users ADD COLUMN otp_expires_at TEXT");
        addIfMissing(cols, "otp_attempts", "ALTER TABLE users ADD COLUMN otp_attempts INTEGER NOT NULL DEFAULT 0");
    }

    private Set<String> existingColumns(String table) {
        List<String> names = jdbc.query(
                "PRAGMA table_info(" + table + ")",
                (rs, n) -> rs.getString("name"));
        Set<String> set = new HashSet<>();
        for (String name : names) {
            if (name != null) {
                set.add(name.toLowerCase());
            }
        }
        return set;
    }

    private void addIfMissing(Set<String> existing, String column, String ddl) {
        if (!existing.contains(column.toLowerCase())) {
            jdbc.execute(ddl);
        }
    }
}
