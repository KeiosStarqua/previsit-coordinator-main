package com.previsitcoordinator.auth;

import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Session-based authentication for the clinic dashboard.
 * Endpoints live under /api/auth and are exempt from the login guard.
 *
 * Flow: register (email + phone) -> emailed OTP -> verify-otp -> signed in.
 */
@RestController
@RequestMapping("/api/auth")
class AuthController {

    private final AuthService auth;

    AuthController(AuthService auth) {
        this.auth = auth;
    }

    @PostMapping("/register")
    AuthService.RegisterResult register(@Valid @RequestBody RegisterRequest request, HttpSession session) {
        return auth.register(request.username(), request.displayName(), request.password(),
                request.email(), request.phone(), session);
    }

    @PostMapping("/verify-otp")
    AuthService.UserView verifyOtp(@Valid @RequestBody VerifyRequest request, HttpSession session) {
        return auth.verifyOtp(request.username(), request.code(), session);
    }

    @PostMapping("/resend-otp")
    AuthService.RegisterResult resendOtp(@Valid @RequestBody ResendRequest request) {
        return auth.resendOtp(request.username());
    }

    @PostMapping("/login")
    AuthService.UserView login(@Valid @RequestBody LoginRequest request, HttpSession session) {
        return auth.login(request.username(), request.password(), session);
    }

    @PostMapping("/logout")
    void logout(HttpSession session) {
        auth.logout(session);
    }

    @GetMapping("/me")
    AuthService.UserView me(HttpSession session) {
        return auth.requireCurrent(session);
    }

    record RegisterRequest(
            @NotBlank String username,
            @NotBlank String displayName,
            @NotBlank @Email String email,
            @NotBlank String phone,
            @NotBlank String password) {
    }

    record VerifyRequest(
            @NotBlank String username,
            @NotBlank String code) {
    }

    record ResendRequest(
            @NotBlank String username) {
    }

    record LoginRequest(
            @NotBlank String username,
            @NotBlank String password) {
    }
}
