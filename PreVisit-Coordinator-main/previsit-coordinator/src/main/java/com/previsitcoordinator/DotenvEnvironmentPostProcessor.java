package com.previsitcoordinator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Loads a plain {@code .env} file (KEY=VALUE lines) into the Spring Environment
 * so values like RESEND_API_KEY and CALLE_API_KEY can live in .env without any
 * extra dependency. Looks in the working directory and its parent (the repo
 * keeps .env one level above the Maven module). Real environment variables and
 * command-line args still win over .env, so nothing here overrides an explicit
 * value.
 *
 * Registered via
 * META-INF/spring/org.springframework.boot.env.EnvironmentPostProcessor.imports
 */
public class DotenvEnvironmentPostProcessor implements EnvironmentPostProcessor {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Map<String, Object> values = new HashMap<>();
        for (String candidate : List.of(".env", "../.env")) {
            loadInto(Path.of(candidate), values);
        }
        if (!values.isEmpty()) {
            // Add with LOWEST precedence so real env vars / properties override it.
            environment.getPropertySources().addLast(new MapPropertySource("dotenvFile", values));
        }
    }

    private void loadInto(Path path, Map<String, Object> out) {
        if (!Files.isRegularFile(path)) {
            return;
        }
        try {
            for (String raw : Files.readAllLines(path)) {
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                int eq = line.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String key = line.substring(0, eq).trim();
                String value = line.substring(eq + 1).trim();
                if ((value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2)
                        || (value.startsWith("'") && value.endsWith("'") && value.length() >= 2)) {
                    value = value.substring(1, value.length() - 1);
                }
                // First file wins (working dir over parent); don't overwrite.
                out.putIfAbsent(key, value);
            }
        } catch (IOException ignored) {
            // A missing or unreadable .env is fine -- fall back to real env vars.
        }
    }
}
