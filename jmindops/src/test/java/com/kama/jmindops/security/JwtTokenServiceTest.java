package com.kama.jmindops.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtTokenServiceTest {

    @Test
    void createsAndParsesTokenWithRequiredClaims() {
        JwtTokenService service = new JwtTokenService(new ObjectMapper(), strongTestKey(), 1);
        service.validateSecret();
        AuthenticatedUser expected = new AuthenticatedUser("user-id", "alice", "USER");

        String token = service.createToken(expected);

        assertThat(service.parse(token)).isEqualTo(expected);
    }

    @Test
    void rejectsWeakSecretsAndUnsafeTtl() {
        JwtTokenService weakSecret = new JwtTokenService(new ObjectMapper(), "short", 1);
        assertThatThrownBy(weakSecret::validateSecret)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 bytes");

        JwtTokenService excessiveTtl = new JwtTokenService(new ObjectMapper(), strongTestKey(), 169);
        assertThatThrownBy(excessiveTtl::validateSecret)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("between 1 and 168");
    }

    @Test
    void rejectsMalformedAndOversizedTokens() {
        JwtTokenService service = new JwtTokenService(new ObjectMapper(), strongTestKey(), 1);

        assertThatThrownBy(() -> service.parse("not-a-jwt"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.parse("x".repeat(8193)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private String strongTestKey() {
        return "test-only-key-material-".repeat(2);
    }
}
