package com.example.codreconciliation.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class DatabaseSecurityValidatorTest {

    @Test
    void rejectsMissingDatabaseCredentials() {
        assertThatIllegalStateException()
                .isThrownBy(() -> new DatabaseSecurityValidator(
                        "", "", "", "production"))
                .withMessageContaining("DB_URL");
    }

    @Test
    void rejectsDisabledTls() {
        assertThatIllegalStateException()
                .isThrownBy(() -> new DatabaseSecurityValidator(
                        "jdbc:mysql://db/cod_reconciliation?sslMode=DISABLED",
                        "user", "password", "local"))
                .withMessageContaining("TLS");
    }

    @Test
    void rejectsMissingExplicitTlsMode() {
        assertThatIllegalStateException()
                .isThrownBy(() -> new DatabaseSecurityValidator(
                        "jdbc:mysql://db/cod_reconciliation", "user", "password", "local"))
                .withMessageContaining("sslMode");
    }

    @Test
    void productionRequiresIdentityVerification() {
        assertThatIllegalStateException()
                .isThrownBy(() -> new DatabaseSecurityValidator(
                        "jdbc:mysql://db/cod_reconciliation?sslMode=REQUIRED",
                        "user", "password", "production"))
                .withMessageContaining("VERIFY_IDENTITY");
    }
}
