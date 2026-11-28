package com.powerbill.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ApiExceptionHandlerTests {

    @Test
    void redactsPersonalDataButKeepsTheStackTraceAndCauses() {
        var cause = new IllegalStateException("Key (email)=(ananya@example.test) already exists");
        var error = new RuntimeException("Insert failed for mobile 9876543210", cause);

        Throwable logged = ApiExceptionHandler.redacted(error, 0);

        assertThat(logged.getMessage()).isEqualTo("java.lang.RuntimeException: Insert failed for mobile [number]");
        assertThat(logged.getCause().getMessage()).doesNotContain("ananya@example.test").contains("=([redacted])");
        assertThat(logged.getStackTrace()).isEqualTo(error.getStackTrace());
        assertThat(logged.getCause().getStackTrace()).isEqualTo(cause.getStackTrace());
    }
}
