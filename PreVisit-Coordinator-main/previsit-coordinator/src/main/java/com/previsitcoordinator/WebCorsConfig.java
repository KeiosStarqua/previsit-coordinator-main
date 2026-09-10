package com.previsitcoordinator;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Development CORS for the clinic dashboard.
 *
 * <p>When the dashboard is served by this backend (open http://localhost:8080/),
 * requests are same-origin and no CORS is involved. This configuration exists for
 * the other ways the demo frontend can run:</p>
 * <ul>
 *   <li>opened directly as a local file ({@code file://}), where the browser sends
 *       {@code Origin: null};</li>
 *   <li>served from a separate dev server (e.g. a Vite/Live Server on another port).</li>
 * </ul>
 *
 * <p>The prototype exchanges no cookies or credentials, so any origin may call the
 * {@code /api/**} endpoints. Tighten {@code allowedOriginPatterns} before any real
 * deployment.</p>
 */
@Configuration
class WebCorsConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(false)
                .maxAge(3600);
    }
}
