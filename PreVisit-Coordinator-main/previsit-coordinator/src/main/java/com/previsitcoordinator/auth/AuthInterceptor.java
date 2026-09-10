package com.previsitcoordinator.auth;

import java.io.IOException;

import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Requires a signed-in session for the coordination APIs. Public paths
 * (health, and everything under /api/auth) are allowed through, as are CORS
 * preflight (OPTIONS) requests.
 */
class AuthInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String path = request.getRequestURI();
        if (path.equals("/api/health") || path.startsWith("/api/auth/")) {
            return true;
        }
        if (AuthService.currentUserId(request.getSession(false)) != null) {
            return true;
        }
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.getWriter().write("{\"message\":\"Login required\"}");
        return false;
    }
}
