package com.example.codreconciliation.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class SecurityConfigTest {

    private static final String VALID_HASH =
            "{bcrypt}$2a$10$/vh9Hv0bjpMiPeiGMBE3uOXmIpQAWxzhHdUcjqDEkvesZHfE3fg4a";

    @Test
    void acceptsConfiguredBcryptCredentials() {
        new SecurityConfig("test-admin", VALID_HASH, false);
    }

    @Test
    void rejectsMissingAdministratorConfiguration() {
        assertThatIllegalStateException()
                .isThrownBy(() -> new SecurityConfig("", "", false))
                .withMessageContaining("ADMIN_USERNAME");
    }

    @Test
    void rejectsNonBcryptPasswordHash() {
        assertThatIllegalStateException()
                .isThrownBy(() -> new SecurityConfig("test-admin", "plaintext", false))
                .withMessageContaining("{bcrypt}");
    }

    @Test
    void rejectsMalformedBcryptPasswordHash() {
        assertThatIllegalStateException()
                .isThrownBy(() -> new SecurityConfig("test-admin", "{bcrypt}not-a-hash", false))
                .withMessageContaining("valid BCrypt");
    }
}
