package com.example.codreconciliation.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.util.StringUtils;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final String adminUsername;
    private final String adminPasswordHash;
    private final boolean hstsEnabled;

    public SecurityConfig(@Value("${security.admin.username}") String adminUsername,
                          @Value("${security.admin.password-hash}") String adminPasswordHash,
                          @Value("${security.hsts.enabled:false}") boolean hstsEnabled) {
        validateAdminConfiguration(adminUsername, adminPasswordHash);
        this.adminUsername = adminUsername;
        this.adminPasswordHash = adminPasswordHash;
        this.hstsEnabled = hstsEnabled;
    }

    private void validateAdminConfiguration(String username, String passwordHash) {
        if (!StringUtils.hasText(username) || !StringUtils.hasText(passwordHash)) {
            throw new IllegalStateException(
                    "ADMIN_USERNAME and ADMIN_PASSWORD_HASH must be configured.");
        }
        if (!passwordHash.startsWith("{bcrypt}")) {
            throw new IllegalStateException(
                    "ADMIN_PASSWORD_HASH must use the {bcrypt} password format.");
        }
        try {
            String bcryptHash = passwordHash.substring("{bcrypt}".length());
            if (!bcryptHash.matches("\\$2[aby]\\$\\d{2}\\$[./A-Za-z0-9]{53}")) {
                throw new IllegalArgumentException("Invalid BCrypt format.");
            }
            new BCryptPasswordEncoder().matches("configuration-validation", bcryptHash);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException(
                    "ADMIN_PASSWORD_HASH is not a valid BCrypt hash.", ex);
        }
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    UserDetailsService userDetailsService() {
        UserDetails admin = User.withUsername(adminUsername)
                .password(adminPasswordHash)
                .roles("ADMIN")
                .build();
        return new InMemoryUserDetailsManager(admin);
    }

    @Bean
    CookieCsrfTokenRepository csrfTokenRepository() {
        return CookieCsrfTokenRepository.withHttpOnlyFalse();
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http,
                                            CookieCsrfTokenRepository csrfRepository) throws Exception {
        var csrfRequestHandler = new CsrfTokenRequestAttributeHandler();
        http
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfRepository)
                        .csrfTokenRequestHandler(csrfRequestHandler))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/api/health", "/", "/index.html", "/css/**", "/js/**", "/favicon.ico").permitAll()
                        .requestMatchers("/api/**").hasRole("ADMIN")
                        .anyRequest().permitAll())
                .httpBasic(Customizer.withDefaults());
        if (hstsEnabled) {
            http.headers(headers -> headers.httpStrictTransportSecurity(hsts -> hsts
                    .includeSubDomains(true).maxAgeInSeconds(31536000)));
        }
        return http.build();
    }
}
