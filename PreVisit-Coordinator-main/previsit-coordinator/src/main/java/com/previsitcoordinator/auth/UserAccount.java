package com.previsitcoordinator.auth;

/**
 * A stored user account row.
 *
 * {@code status} is 'PENDING' between registration and successful email OTP
 * verification, then 'ACTIVE'. {@code otpCode} / {@code otpExpiresAt} hold the
 * current verification code (null once verified or expired).
 */
record UserAccount(
        long id,
        String username,
        String displayName,
        String email,
        String phone,
        String passwordHash,
        String passwordSalt,
        String status,
        String otpCode,
        String otpExpiresAt,
        int otpAttempts) {
}
