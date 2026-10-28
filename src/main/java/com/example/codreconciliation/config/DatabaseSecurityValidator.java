package com.example.codreconciliation.config;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@Profile("!test")
public class DatabaseSecurityValidator {

    private final String url;
    private final String username;
    private final String password;
    private final String environment;

    public DatabaseSecurityValidator(@Value("${spring.datasource.url}") String url,
                                     @Value("${spring.datasource.username}") String username,
                                     @Value("${spring.datasource.password}") String password,
                                     @Value("${app.environment}") String environment) {
        this.url = url;
        this.username = username;
        this.password = password;
        this.environment = environment;
        validate();
    }

    private void validate() {
        if (!StringUtils.hasText(url) || !StringUtils.hasText(username) || !StringUtils.hasText(password)) {
            throw new IllegalStateException("DB_URL, DB_USERNAME, and DB_PASSWORD must be configured.");
        }
        Map<String, String> parameters = parseQuery(url);
        String sslMode = parameters.getOrDefault("sslmode", "").toUpperCase();
        String useSsl = parameters.getOrDefault("usessl", "").toLowerCase();
        if ("false".equals(useSsl) || "DISABLED".equals(sslMode)) {
            throw new IllegalStateException("Database TLS must not be disabled.");
        }
        if (!"REQUIRED".equals(sslMode) && !"VERIFY_IDENTITY".equals(sslMode)) {
            throw new IllegalStateException("DB_URL must explicitly set sslMode=REQUIRED or sslMode=VERIFY_IDENTITY.");
        }
        if ("production".equalsIgnoreCase(environment) && !"VERIFY_IDENTITY".equals(sslMode)) {
            throw new IllegalStateException("Production DB_URL must use sslMode=VERIFY_IDENTITY.");
        }
    }

    private Map<String, String> parseQuery(String jdbcUrl) {
        int queryStart = jdbcUrl.indexOf('?');
        Map<String, String> result = new HashMap<>();
        if (queryStart < 0 || queryStart == jdbcUrl.length() - 1) {
            return result;
        }
        for (String pair : jdbcUrl.substring(queryStart + 1).split("&")) {
            int separator = pair.indexOf('=');
            String key = separator < 0 ? pair : pair.substring(0, separator);
            String value = separator < 0 ? "" : pair.substring(separator + 1);
            result.put(URLDecoder.decode(key, StandardCharsets.UTF_8).toLowerCase(),
                    URLDecoder.decode(value, StandardCharsets.UTF_8));
        }
        return result;
    }
}
