package com.previsitcoordinator.auth;

import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * Salted PBKDF2 password hashing using only the JDK (no external security
 * dependency). Each account stores a random salt and the derived hash, both
 * Base64-encoded. Verification is constant-time.
 */
final class PasswordHasher {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int SALT_BYTES = 16;
    private static final int HASH_BITS = 256;
    private static final int ITERATIONS = 120_000;
    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";

    private PasswordHasher() {
    }

    /** A fresh random salt, Base64-encoded. */
    static String newSalt() {
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        return Base64.getEncoder().encodeToString(salt);
    }

    /** Derive a Base64 hash for the given password and Base64 salt. */
    static String hash(String password, String base64Salt) {
        byte[] salt = Base64.getDecoder().decode(base64Salt);
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, ITERATIONS, HASH_BITS);
            SecretKeyFactory factory = SecretKeyFactory.getInstance(ALGORITHM);
            byte[] hash = factory.generateSecret(spec).getEncoded();
            spec.clearPassword();
            return Base64.getEncoder().encodeToString(hash);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to hash password", e);
        }
    }

    /** Constant-time comparison of a candidate password against a stored hash. */
    static boolean matches(String password, String base64Salt, String expectedBase64Hash) {
        String candidate = hash(password, base64Salt);
        byte[] a = candidate.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] b = expectedBase64Hash.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (a.length != b.length) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < a.length; i++) {
            diff |= a[i] ^ b[i];
        }
        return diff == 0;
    }
}
